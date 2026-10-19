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
import io.github.nephets.pgvector.core.IndexType;
import io.github.nephets.pgvector.core.VectorCodec;
import io.github.nephets.pgvector.core.VectorIndex;
import io.github.nephets.pgvector.spring.PgVectorTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 「库存业务 + 向量」的完整场景：写入在同一事务、查询在同一条 SQL。
 *
 * <p>这是本仓想说明的全部事情，浓缩在一个类里：
 *
 * <ol>
 *   <li>{@link #seedInOneTransaction(List)}：业务行与向量行在同一个数据库事务里写入。
 *       向量维度写错时整批回滚——不会出现「商品建好了但没有向量」这种脏数据。
 *   <li>{@link #search(SearchRequest)}：标量过滤（仓库 / 状态 / 库存）+ 向量近邻 + 业务表 JOIN， 由 {@link HybridQuery}
 *       生成<strong>一条</strong> SQL，分页与排序都在数据库里完成。
 * </ol>
 */
public class InventoryScenario {

    private static final Logger log = LoggerFactory.getLogger(InventoryScenario.class);

    /** 与 schema.sql 里的 {@code VECTOR(8)} 对齐。改这里必须同时改建表语句。 */
    public static final int DIMENSIONS = 8;

    /** 建表时就按余弦建的 HNSW 索引；查询算子必须与它配套，否则会静默退化成全表扫描。 */
    public static final VectorIndex EMBEDDING_INDEX =
            new VectorIndex(
                    "idx_sku_embedding_cosine",
                    "sku_embedding",
                    "embedding",
                    IndexType.HNSW,
                    DistanceOperator.COSINE,
                    DIMENSIONS);

    /** L2 索引（demo 的 schema.sql 里没建，测试需要时按 IndexAdvisor 的 DDL 现建）。 */
    public static final VectorIndex L2_INDEX =
            new VectorIndex(
                    "idx_sku_embedding_l2",
                    "sku_embedding",
                    "embedding",
                    IndexType.HNSW,
                    DistanceOperator.L2,
                    DIMENSIONS);

    /** 内积索引，同上。 */
    public static final VectorIndex INNER_PRODUCT_INDEX =
            new VectorIndex(
                    "idx_sku_embedding_ip",
                    "sku_embedding",
                    "embedding",
                    IndexType.HNSW,
                    DistanceOperator.INNER_PRODUCT,
                    DIMENSIONS);

    /**
     * 一次混合查询的全部入参。
     *
     * @param warehouse 仓库
     * @param status 上架状态
     * @param minStock 最低库存
     * @param queryVector 查询向量
     * @param operator 距离算子
     * @param index 期望命中的索引；为 null 表示不做算子/opclass 校验
     * @param limit 每页条数
     * @param offset 偏移量
     */
    public record SearchRequest(
            String warehouse,
            String status,
            int minStock,
            float[] queryVector,
            DistanceOperator operator,
            VectorIndex index,
            int limit,
            int offset) {

        /** 用默认（余弦）索引、默认分页的简写。 */
        public static SearchRequest of(String warehouse, String queryVectorText) {
            return new SearchRequest(
                    warehouse,
                    "ON_SALE",
                    1,
                    DeterministicEmbedding.of(queryVectorText, DIMENSIONS),
                    DistanceOperator.COSINE,
                    EMBEDDING_INDEX,
                    10,
                    0);
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final PgVectorTemplate vectorTemplate;
    private final TransactionTemplate transactionTemplate;

    public InventoryScenario(
            PgVectorTemplate vectorTemplate, PlatformTransactionManager transactionManager) {
        this.vectorTemplate = vectorTemplate;
        this.jdbcTemplate = vectorTemplate.jdbcTemplate();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** demo 用的一批商品，跨两个仓库、两种状态。 */
    public static List<Sku> demoSkus() {
        return List.of(
                new Sku("SKU-1001", "无线鼠标 黑色", "SH01", "ON_SALE", 120),
                new Sku("SKU-1002", "无线鼠标 白色", "SH01", "ON_SALE", 8),
                new Sku("SKU-1003", "无线鼠标 静音版", "SH01", "ON_SALE", 64),
                new Sku("SKU-1004", "机械键盘 87键", "SH01", "ON_SALE", 31),
                new Sku("SKU-1005", "机械键盘 104键", "SH01", "OFF_SHELF", 0),
                new Sku("SKU-1006", "蓝牙耳机 入耳式", "SH01", "ON_SALE", 210),
                new Sku("SKU-2001", "无线鼠标 黑色", "BJ01", "ON_SALE", 45),
                new Sku("SKU-2002", "无线鼠标 静音版", "BJ01", "ON_SALE", 0),
                new Sku("SKU-2003", "显示器 27寸 2K", "BJ01", "ON_SALE", 12),
                new Sku("SKU-2004", "显示器 32寸 4K", "BJ01", "OFF_SHELF", 3),
                new Sku("SKU-2005", "蓝牙耳机 头戴式", "BJ01", "ON_SALE", 77),
                new Sku("SKU-2006", "USB-C 扩展坞", "BJ01", "ON_SALE", 5));
    }

    /** 清空两张表。 */
    public void reset() {
        log.info("reset: truncating sku_embedding and sku tables");
        jdbcTemplate.execute("TRUNCATE TABLE sku_embedding, sku RESTART IDENTITY CASCADE");
    }

    /**
     * 把一批商品连同它们的向量写进数据库，<strong>同一个事务</strong>。
     *
     * @param skus 商品列表；向量由 {@link DeterministicEmbedding} 从商品名生成
     */
    public void seedInOneTransaction(List<Sku> skus) {
        log.info("seeding {} skus into one transaction (business rows + vectors)", skus.size());
        transactionTemplate.executeWithoutResult(
                status -> {
                    for (Sku sku : skus) {
                        long skuId = insertBusinessRow(sku);
                        insertVectorRow(skuId, sku.name());
                    }
                });
        log.info("seeded {} skus in one transaction", skus.size());
    }

    /**
     * 写入一行但故意用错误的向量维度——用于验证事务回滚。
     *
     * <p>Java 侧的维度校验先抛错；即使绕过它，PostgreSQL 也会拒绝。 两条路的结果一致：业务行同样不会留下。
     */
    public void seedWithWrongDimensions(List<Sku> skus, int wrongDimensions) {
        log.info(
                "seeding {} skus with wrong dimensions={} (expect whole transaction rollback)",
                skus.size(),
                wrongDimensions);
        transactionTemplate.executeWithoutResult(
                status -> {
                    for (Sku sku : skus) {
                        long skuId = insertBusinessRow(sku);
                        float[] bad = new float[wrongDimensions];
                        for (int i = 0; i < bad.length; i++) {
                            bad[i] = 0.1f * (i + 1);
                        }
                        // requireDimensions 在写库之前拦下，异常冒泡出事务 -> 整批回滚
                        VectorCodec.requireDimensions(bad, DIMENSIONS, "sku_embedding.embedding");
                        jdbcTemplate.update(
                                "INSERT INTO sku_embedding (sku_id, embedding) VALUES (?, ?::vector)",
                                skuId,
                                VectorCodec.toLiteral(bad));
                    }
                });
    }

    /** 业务行条数。 */
    public long countSkus() {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM sku", Long.class);
        long count = total == null ? 0L : total;
        log.debug("countSkus={}", count);
        return count;
    }

    /** 向量行条数。 */
    public long countEmbeddings() {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM sku_embedding", Long.class);
        long count = total == null ? 0L : total;
        log.debug("countEmbeddings={}", count);
        return count;
    }

    /** 按商品名取回库里存的向量，用于和 Java 侧的计算结果对账。 */
    public float[] embeddingOf(String skuCode) {
        log.debug("embeddingOf skuCode={}", skuCode);
        String literal =
                jdbcTemplate.queryForObject(
                        "SELECT e.embedding::text FROM sku_embedding e JOIN sku s ON s.id = e.sku_id"
                                + " WHERE s.sku_code = ?",
                        String.class,
                        skuCode);
        return VectorCodec.fromLiteral(literal);
    }

    private long insertBusinessRow(Sku sku) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO sku (sku_code, name, warehouse, status, stock)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                sku.skuCode(),
                sku.name(),
                sku.warehouse(),
                sku.status(),
                sku.stock());
    }

    private void insertVectorRow(long skuId, String text) {
        jdbcTemplate.update(
                "INSERT INTO sku_embedding (sku_id, embedding) VALUES (?, ?::vector)",
                skuId,
                VectorCodec.toLiteral(DeterministicEmbedding.of(text, DIMENSIONS)));
    }

    /**
     * 构造混合查询：标量过滤 + 向量近邻 + 业务表 JOIN，一条 SQL。
     *
     * @param request 查询入参
     */
    public static HybridQuery buildQuery(SearchRequest request) {
        HybridQuery.Builder builder =
                HybridQuery.builder()
                        .select("s.sku_code", "s.name", "s.warehouse", "s.stock")
                        .from("sku_embedding e")
                        .join("sku s", "s.id = e.sku_id")
                        .where("s.warehouse = ?", request.warehouse())
                        .where("s.status = ?", request.status())
                        .where("s.stock >= ?", request.minStock())
                        .nearest("e.embedding", request.queryVector(), request.operator())
                        .limit(request.limit())
                        .offset(request.offset());
        if (request.index() != null) {
            builder.withIndex(request.index());
        }
        return builder.build();
    }

    /** 执行 {@link #buildQuery(SearchRequest)} 并把结果映射成 {@link SearchHit}。 */
    public List<SearchHit> search(SearchRequest request) {
        log.info(
                "search warehouse={} status={} minStock={} operator={} limit={} offset={}",
                request.warehouse(),
                request.status(),
                request.minStock(),
                request.operator(),
                request.limit(),
                request.offset());
        List<SearchHit> hits =
                vectorTemplate.query(
                        buildQuery(request),
                        (rs, row) ->
                                new SearchHit(
                                        rs.getString("sku_code"),
                                        rs.getString("name"),
                                        rs.getString("warehouse"),
                                        rs.getInt("stock"),
                                        rs.getDouble("distance")));
        log.info("search returned {} hits", hits.size());
        return hits;
    }

    /** 底层模板，demo 与测试共用。 */
    public PgVectorTemplate vectorTemplate() {
        return vectorTemplate;
    }
}
