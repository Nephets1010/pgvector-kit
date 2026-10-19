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
package io.github.nephets.pgvector.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 一张 pgvector 索引的声明。
 *
 * <p>把它建模成一个对象而不是一个字符串，是为了让 {@link HybridQuery} 能在生成 SQL 之前 做两件 PostgreSQL 自己不会替你做的事：
 *
 * <ol>
 *   <li>查询算子的 opclass 与索引的 opclass 是否一致（不一致会静默退化成全表扫描）；
 *   <li>查询向量的维度与索引列维度是否一致。
 * </ol>
 *
 * @param name 索引名
 * @param table 被索引的表名
 * @param column 被索引的列名
 * @param type 索引类型
 * @param operator 索引建立时所配套的算子（决定 opclass）
 * @param dimensions 被索引列的维度
 */
public record VectorIndex(
        String name,
        String table,
        String column,
        IndexType type,
        DistanceOperator operator,
        int dimensions) {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

    public VectorIndex {
        name = SqlIdentifier.requireIdentifier(name, "索引名");
        table = SqlIdentifier.requireIdentifier(table, "索引所在表名");
        column = SqlIdentifier.requireIdentifier(column, "索引所在列名");
        type = Objects.requireNonNull(type, "索引类型不能为 null");
        operator = Objects.requireNonNull(operator, "索引配套的算子不能为 null");
        if (dimensions <= 0) {
            log.warn("拒绝创建索引 {}：维度必须为正数，实际为 {}", name, dimensions);
            throw new VectorFormatException("索引维度必须为正数，实际为 " + dimensions);
        }
        if (dimensions > PgVectorLimits.MAX_INDEXABLE_DIMENSIONS) {
            log.warn(
                    "拒绝创建索引 {}：维度 {} 超过 pgvector 可索引上限 {}",
                    name,
                    dimensions,
                    PgVectorLimits.MAX_INDEXABLE_DIMENSIONS);
            throw new VectorFormatException(
                    "维度 "
                            + dimensions
                            + " 超过了 pgvector 可索引的上限 "
                            + PgVectorLimits.MAX_INDEXABLE_DIMENSIONS
                            + " 维：HNSW 与 IVFFlat 都只能索引 ≤"
                            + PgVectorLimits.MAX_INDEXABLE_DIMENSIONS
                            + " 维的列。请先降维（或改用 PCA / 更小输出的 embedding 模型），"
                            + "否则这张表永远只能全表扫描。");
        }
    }

    /**
     * 索引的 opclass。
     *
     * <p>它是 {@code CREATE INDEX ... USING hnsw (col vector_cosine_ops)} 里的那一半。
     */
    public String operatorClass() {
        return operator.operatorClass();
    }

    /** 校验一个查询算子能否用上这张索引。 */
    public void requireOperator(DistanceOperator queryOperator) {
        if (queryOperator == null) {
            log.warn("索引 {} 拒绝查询：没有指定距离算子", name);
            throw new OperatorIndexMismatchException(this, null, "查询没有指定距离算子");
        }
        if (!operator.matchesOperatorClass(queryOperator.operatorClass())) {
            // ★ 这道闸的意义就在日志里：不一致时 PostgreSQL 不会报错，只会悄悄全表扫描
            log.warn(
                    "索引 {} 与查询算子不配套：索引 opclass={} 查询算子 opclass={}（拒绝执行，不退化成全表扫描）",
                    name,
                    operator.operatorClass(),
                    queryOperator.operatorClass());
            throw new OperatorIndexMismatchException(this, queryOperator, null);
        }
    }

    /**
     * 校验一个查询向量的维度能否用上这张索引。
     *
     * <p>维度不符的留痕在 {@link VectorCodec#requireDimensions}（它抛的异常携带 subject）， 这里只做委托，避免同一事件打两遍日志。
     */
    public void requireDimensions(float[] queryVector) {
        VectorCodec.requireDimensions(queryVector, dimensions, table + "." + column);
    }
}
