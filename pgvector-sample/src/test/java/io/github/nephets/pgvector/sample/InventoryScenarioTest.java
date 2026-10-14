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
import io.github.nephets.pgvector.core.HybridQuery;
import io.github.nephets.pgvector.core.OperatorIndexMismatchException;
import io.github.nephets.pgvector.core.VectorDimensionMismatchException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不需要数据库的场景测试：SQL 长什么样、什么情况下必须报错。
 *
 * <p>真实的 pgvector 行为由 {@code PgVectorHybridQueryIT} 在 CI 上验证。
 */
@DisplayName("库存场景：SQL 生成与前置校验（不需要数据库）")
class InventoryScenarioTest {

    private static final String QUERY_TEXT = "无线鼠标 黑色";

    private static InventoryScenario.SearchRequest request(DistanceOperator operator) {
        return new InventoryScenario.SearchRequest(
                "SH01",
                "ON_SALE",
                1,
                DeterministicEmbedding.of(QUERY_TEXT, InventoryScenario.DIMENSIONS),
                operator,
                InventoryScenario.EMBEDDING_INDEX,
                10,
                0);
    }

    @Test
    @DisplayName("生成的就是一条 SQL：业务列 + 距离 + JOIN + 三条标量条件 + 排序 + 分页")
    void generatesOneSql() {
        HybridQuery query = InventoryScenario.buildQuery(request(DistanceOperator.COSINE));

        assertThat(query.sql())
                .isEqualTo(
                        "SELECT s.sku_code, s.name, s.warehouse, s.stock,"
                                + " (e.embedding <=> ?::vector) AS distance\n"
                                + "FROM sku_embedding e\n"
                                + "INNER JOIN sku s ON s.id = e.sku_id\n"
                                + "WHERE s.warehouse = ? AND s.status = ? AND s.stock >= ?\n"
                                + "ORDER BY e.embedding <=> ?::vector ASC\n"
                                + "LIMIT 10\n"
                                + "OFFSET 0");
    }

    @Test
    @DisplayName("绑定参数顺序：先距离列，再标量条件，最后是 ORDER BY 里的距离")
    void bindsParametersInSqlOrder() {
        HybridQuery query = InventoryScenario.buildQuery(request(DistanceOperator.COSINE));
        assertThat(query.params()).hasSize(5);
        assertThat(query.params().get(0)).isEqualTo(query.params().get(4));
        assertThat(query.params().get(1)).isEqualTo("SH01");
        assertThat(query.params().get(2)).isEqualTo("ON_SALE");
        assertThat(query.params().get(3)).isEqualTo(1);
    }

    @Test
    @DisplayName("余弦索引上跑 L2 查询：直接报 OperatorIndexMismatchException，不会静默全表扫描")
    void rejectsMismatchedOperator() {
        assertThatThrownBy(() -> InventoryScenario.buildQuery(request(DistanceOperator.L2)))
                .isInstanceOf(OperatorIndexMismatchException.class)
                .hasMessageContaining("vector_cosine_ops")
                .hasMessageContaining("vector_l2_ops");
    }

    @Test
    @DisplayName("余弦索引上跑内积查询：同样拦下")
    void rejectsInnerProductOnCosineIndex() {
        assertThatThrownBy(
                        () -> InventoryScenario.buildQuery(request(DistanceOperator.INNER_PRODUCT)))
                .isInstanceOf(OperatorIndexMismatchException.class)
                .hasMessageContaining("vector_ip_ops");
    }

    @Test
    @DisplayName("查询向量维度不对时，连 SQL 都生成不出来")
    void rejectsWrongQueryDimensions() {
        InventoryScenario.SearchRequest bad =
                new InventoryScenario.SearchRequest(
                        "SH01",
                        "ON_SALE",
                        1,
                        new float[4],
                        DistanceOperator.COSINE,
                        InventoryScenario.EMBEDDING_INDEX,
                        10,
                        0);
        assertThatThrownBy(() -> InventoryScenario.buildQuery(bad))
                .isInstanceOf(VectorDimensionMismatchException.class)
                .hasMessageContaining("期望 8 维")
                .hasMessageContaining("实际 4 维");
    }

    @Test
    @DisplayName("不指定索引时不做算子校验（对应还没建索引的场景）")
    void skipsOperatorCheckWithoutIndex() {
        InventoryScenario.SearchRequest noIndex =
                new InventoryScenario.SearchRequest(
                        "SH01",
                        "ON_SALE",
                        1,
                        DeterministicEmbedding.of(QUERY_TEXT, InventoryScenario.DIMENSIONS),
                        DistanceOperator.L2,
                        null,
                        5,
                        0);
        HybridQuery query = InventoryScenario.buildQuery(noIndex);
        assertThat(query.sql()).contains("<-> ?::vector");
    }

    @Test
    @DisplayName("demo 数据里两个仓库各有 6 个商品，覆盖在售/下架与有货/无货")
    void demoDataCoversInterestingCases() {
        var skus = InventoryScenario.demoSkus();
        assertThat(skus).hasSize(12);
        assertThat(skus.stream().filter(s -> "SH01".equals(s.warehouse())).count()).isEqualTo(6);
        assertThat(skus.stream().filter(s -> "ON_SALE".equals(s.status())).count()).isEqualTo(10);
        assertThat(skus.stream().filter(s -> s.stock() == 0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("简写入口 SearchRequest.of 用余弦 + 在售 + 有库存")
    void shortcutRequest() {
        InventoryScenario.SearchRequest request =
                InventoryScenario.SearchRequest.of("BJ01", QUERY_TEXT);
        assertThat(request.warehouse()).isEqualTo("BJ01");
        assertThat(request.status()).isEqualTo("ON_SALE");
        assertThat(request.minStock()).isEqualTo(1);
        assertThat(request.operator()).isEqualTo(DistanceOperator.COSINE);
        assertThat(request.index()).isEqualTo(InventoryScenario.EMBEDDING_INDEX);
        assertThat(request.queryVector()).hasSize(InventoryScenario.DIMENSIONS);
    }

    @Test
    @DisplayName("SearchHit 的 toString 是可直接贴进文档的一行")
    void searchHitPrintsAsOneLine() {
        SearchHit hit = new SearchHit("SKU-1001", "无线鼠标 黑色", "SH01", 120, 0.0231);
        assertThat(hit.toString())
                .contains("SKU-1001")
                .contains("无线鼠标 黑色")
                .contains("SH01")
                .contains("库存=120")
                .contains("distance=0.023100");
    }
}
