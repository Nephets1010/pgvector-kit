/*
 * Copyright 2026 The pgvector-kit Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.pgvector.sample;

import io.github.nephets.pgvector.core.DistanceOperator;
import io.github.nephets.pgvector.core.IndexAdvice;
import io.github.nephets.pgvector.core.IndexAdvisor;
import io.github.nephets.pgvector.core.IndexRequest;
import io.github.nephets.pgvector.core.VectorIndex;
import io.github.nephets.pgvector.spring.PgVectorProperties;
import io.github.nephets.pgvector.spring.PgVectorTemplate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在<strong>真实的 PostgreSQL + pgvector</strong> 上验证混合查询。
 *
 * <p>这里断言的不是「我写的 SQL 长什么样」（那由单元测试覆盖），而是 「pgvector 真的按这个顺序返回了这些行」：
 *
 * <ul>
 *   <li>距离值与 Java 侧独立算出来的余弦距离 / 欧氏距离一致；
 *   <li>结果按距离升序；
 *   <li>标量过滤真的把另一仓库、下架、无库存的行排除掉；
 *   <li>分页不重不漏；
 *   <li>维度写错时业务行也一起回滚；
 *   <li>索引建议器给的 DDL 在真库上能执行、索引真的建出来。
 * </ul>
 *
 * <p><strong>本机没有 Docker 且没给 {@code -Dpgvector.jdbc.url} 时本类会被跳过</strong>—— 跳过不等于通过，CI 上会真跑。
 */
@DisplayName("真实 pgvector 上的混合查询（需要 Docker 或 -Dpgvector.jdbc.url）")
class PgVectorHybridQueryIT {

    private static final int DIMS = InventoryScenario.DIMENSIONS;
    private static final String QUERY_TEXT = "无线鼠标 黑色";

    private static DataSource dataSource;
    private static InventoryScenario scenario;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpDatabase() {
        dataSource = TestPostgres.start();
        // 复用 main/resources 下的 schema.sql：测试与 demo 绝不出现两份会各自漂移的建表语句
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        PgVectorTemplate vectorTemplate =
                new PgVectorTemplate(dataSource, new PgVectorProperties());
        scenario =
                new InventoryScenario(vectorTemplate, new DataSourceTransactionManager(dataSource));

        scenario.reset();
        scenario.seedInOneTransaction(InventoryScenario.demoSkus());
    }

    @AfterAll
    static void tearDown() {
        TestPostgres.stop();
    }

    private static InventoryScenario.SearchRequest request(
            String warehouse, DistanceOperator operator, VectorIndex index, int limit, int offset) {
        return new InventoryScenario.SearchRequest(
                warehouse,
                "ON_SALE",
                1,
                DeterministicEmbedding.of(QUERY_TEXT, DIMS),
                operator,
                index,
                limit,
                offset);
    }

    @Test
    @DisplayName("12 行数据在同一个事务里写入，业务表与向量表条数一致")
    void seedsInOneTransaction() {
        assertThat(scenario.countSkus()).isEqualTo(12);
        assertThat(scenario.countEmbeddings()).isEqualTo(12);
    }

    @Test
    @DisplayName("库里取回的向量与 Java 侧生成的逐位一致（往返无损）")
    void storedVectorIsLossless() {
        float[] expected = DeterministicEmbedding.of("无线鼠标 黑色", DIMS);
        float[] stored = scenario.embeddingOf("SKU-1001");
        assertThat(stored).hasSize(DIMS);
        for (int i = 0; i < DIMS; i++) {
            assertThat(Float.floatToRawIntBits(stored[i]))
                    .as("第 %d 个分量在库里被改写了", i)
                    .isEqualTo(Float.floatToRawIntBits(expected[i]));
        }
    }

    @Test
    @DisplayName("余弦混合查询：结果按距离升序，且全是 SH01 / 在售 / 有库存")
    void cosineHybridQuery() {
        List<SearchHit> hits =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                10,
                                0));

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.warehouse()).isEqualTo("SH01"));
        assertThat(hits).allSatisfy(hit -> assertThat(hit.stock()).isGreaterThan(0));

        List<Double> distances = hits.stream().map(SearchHit::distance).toList();
        assertThat(distances).isSorted();
    }

    @Test
    @DisplayName("余弦距离与 Java 侧独立计算的结果一致（误差 < 1e-4）")
    void cosineDistanceMatchesJavaComputation() {
        float[] queryVector = DeterministicEmbedding.of(QUERY_TEXT, DIMS);
        List<SearchHit> hits =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                10,
                                0));

        assertThat(hits).isNotEmpty();
        for (SearchHit hit : hits) {
            float[] stored = scenario.embeddingOf(hit.skuCode());
            double expected = 1.0 - DeterministicEmbedding.cosineSimilarity(queryVector, stored);
            assertThat(hit.distance())
                    .as("%s 的距离：pgvector 给的与 Java 算的不一致", hit.skuCode())
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    @Test
    @DisplayName("L2 距离同样与 Java 侧一致 —— 说明换算子真的换了算法，不是换个写法")
    void l2DistanceMatchesJavaComputation() {
        createIndexFor(InventoryScenario.L2_INDEX);
        float[] queryVector = DeterministicEmbedding.of(QUERY_TEXT, DIMS);
        List<SearchHit> hits =
                scenario.search(
                        request("SH01", DistanceOperator.L2, InventoryScenario.L2_INDEX, 10, 0));

        assertThat(hits).isNotEmpty();
        for (SearchHit hit : hits) {
            float[] stored = scenario.embeddingOf(hit.skuCode());
            double expected = DeterministicEmbedding.l2Distance(queryVector, stored);
            assertThat(hit.distance())
                    .as("%s 的 L2 距离", hit.skuCode())
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    @Test
    @DisplayName("内积算子 <#> 返回负内积，ASC 排序得到的是内积最大的 —— 这是最容易踩的一个方向坑")
    void innerProductSortsByLargestInnerProduct() {
        createIndexFor(InventoryScenario.INNER_PRODUCT_INDEX);
        float[] queryVector = DeterministicEmbedding.of(QUERY_TEXT, DIMS);
        List<SearchHit> hits =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.INNER_PRODUCT,
                                InventoryScenario.INNER_PRODUCT_INDEX,
                                10,
                                0));

        assertThat(hits).isNotEmpty();
        List<Double> innerProducts = new ArrayList<>();
        for (SearchHit hit : hits) {
            float[] stored = scenario.embeddingOf(hit.skuCode());
            double innerProduct = DeterministicEmbedding.innerProduct(queryVector, stored);
            // pgvector 的 <#> 返回的是负内积
            assertThat(hit.distance())
                    .isCloseTo(-innerProduct, org.assertj.core.data.Offset.offset(1e-4));
            innerProducts.add(innerProduct);
        }
        // ASC 排序 = 内积从大到小
        assertThat(innerProducts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("标量过滤真的排除了另一个仓库的行（不是查完再在内存里筛）")
    void scalarFilterExcludesOtherWarehouses() {
        List<SearchHit> shanghai =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                20,
                                0));
        List<SearchHit> beijing =
                scenario.search(
                        request(
                                "BJ01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                20,
                                0));

        assertThat(shanghai).isNotEmpty();
        assertThat(beijing).isNotEmpty();
        // 「无线鼠标 黑色」在两个仓库都有（SKU-1001 / SKU-2001），过滤必须把它们分开
        assertThat(shanghai).allSatisfy(hit -> assertThat(hit.warehouse()).isEqualTo("SH01"));
        assertThat(beijing).allSatisfy(hit -> assertThat(hit.warehouse()).isEqualTo("BJ01"));
        assertThat(shanghai.stream().map(SearchHit::skuCode))
                .doesNotContainAnyElementsOf(beijing.stream().map(SearchHit::skuCode).toList());
    }

    @Test
    @DisplayName("分页：第一页 2 条 + 第二页 2 条 = 不重不漏地覆盖前 4 条")
    void paginationCoversRowsWithoutOverlap() {
        List<SearchHit> all =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                10,
                                0));
        assertThat(all.size()).isGreaterThanOrEqualTo(3);

        List<SearchHit> page1 =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                2,
                                0));
        List<SearchHit> page2 =
                scenario.search(
                        request(
                                "SH01",
                                DistanceOperator.COSINE,
                                InventoryScenario.EMBEDDING_INDEX,
                                2,
                                2));

        assertThat(page1).hasSize(2);
        assertThat(page2).isNotEmpty();
        assertThat(page1.stream().map(SearchHit::skuCode))
                .doesNotContainAnyElementsOf(page2.stream().map(SearchHit::skuCode).toList());
        // 两页拼起来就是全量结果的前 4 条，顺序也一致 —— 这正是「内存里拼分页」做不到的
        List<String> combined = new ArrayList<>();
        page1.forEach(hit -> combined.add(hit.skuCode()));
        page2.forEach(hit -> combined.add(hit.skuCode()));
        assertThat(combined)
                .isEqualTo(all.stream().map(SearchHit::skuCode).limit(combined.size()).toList());
    }

    @Test
    @DisplayName("维度写错时整批回滚：业务行一条都不会留下")
    void wrongDimensionsRollBackBusinessRowsToo() {
        long before = scenario.countSkus();
        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.nephets.pgvector.core.VectorDimensionMismatchException.class,
                () ->
                        scenario.seedWithWrongDimensions(
                                List.of(new Sku("SKU-9999", "维度错误测试品", "SH01", "ON_SALE", 1)), 4));
        assertThat(scenario.countSkus()).as("业务行不该留下").isEqualTo(before);
        assertThat(scenario.countEmbeddings()).isEqualTo(before);
    }

    @Test
    @DisplayName("索引建议器给出的 DDL 是真能执行的，索引也真的建出来了")
    void indexAdviceDdlActuallyRuns() {
        IndexAdvice advice =
                IndexAdvisor.advise(
                        IndexRequest.of(
                                50_000, DIMS, DistanceOperator.L2, "sku_embedding", "embedding"));
        assertThat(advice.indexable()).isTrue();
        assertThat(advice.ddl()).contains("USING hnsw").contains("vector_l2_ops");

        String indexName = "idx_sku_embedding_l2";
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + indexName);
        jdbcTemplate.execute(advice.ddl().replace(advice.indexName(), indexName));

        Integer found =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                        Integer.class,
                        indexName);
        assertThat(found).isEqualTo(1);
    }

    @Test
    @DisplayName("pgvector 扩展确实装上了，且能查到版本")
    void pgvectorExtensionIsInstalled() {
        String version =
                jdbcTemplate.queryForObject(
                        "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                        String.class);
        assertThat(version).isNotBlank();
    }

    @Test
    @DisplayName("算子与索引 opclass 不匹配时，SQL 根本不会发到数据库")
    void operatorMismatchIsRejectedBeforeExecution() {
        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.nephets.pgvector.core.OperatorIndexMismatchException.class,
                () ->
                        InventoryScenario.buildQuery(
                                request(
                                        "SH01",
                                        DistanceOperator.L2,
                                        InventoryScenario.EMBEDDING_INDEX,
                                        10,
                                        0)));
    }

    private static void createIndexFor(VectorIndex index) {
        Integer existing =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                        Integer.class,
                        index.name());
        if (existing != null && existing > 0) {
            return;
        }
        IndexAdvice advice =
                IndexAdvisor.advise(
                        IndexRequest.of(
                                50_000, DIMS, index.operator(), index.table(), index.column()));
        jdbcTemplate.execute(advice.ddl().replace(advice.indexName(), index.name()));
    }
}
