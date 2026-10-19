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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VectorIndex} 声明与两道闸门（算子配套、维度一致）的逐分支覆盖。
 *
 * <p>{@code OperatorIndexMismatchTest} 从 {@link HybridQuery} 的视角验证「查询被拦下」， 这个文件直接对 {@link
 * VectorIndex} 的 record 契约与两个 require 方法逐分支断言。
 */
@DisplayName("VectorIndex：声明契约与两道闸门")
class VectorIndexTest {

    private static VectorIndex index(DistanceOperator operator, int dimensions) {
        return new VectorIndex(
                "idx_sku_embedding",
                "sku_embedding",
                "embedding",
                IndexType.HNSW,
                operator,
                dimensions);
    }

    @Nested
    @DisplayName("record 契约")
    class Record {

        @Test
        @DisplayName("访问器原样返回构造参数")
        void accessors() {
            VectorIndex index = index(DistanceOperator.L2, 768);
            assertThat(index.name()).isEqualTo("idx_sku_embedding");
            assertThat(index.table()).isEqualTo("sku_embedding");
            assertThat(index.column()).isEqualTo("embedding");
            assertThat(index.type()).isEqualTo(IndexType.HNSW);
            assertThat(index.operator()).isEqualTo(DistanceOperator.L2);
            assertThat(index.dimensions()).isEqualTo(768);
            assertThat(index.operatorClass()).isEqualTo("vector_l2_ops");
        }

        @Test
        @DisplayName("record 的值相等性：同一份声明拿来比较应该相等")
        void valueEquality() {
            assertThat(index(DistanceOperator.COSINE, 3))
                    .isEqualTo(index(DistanceOperator.COSINE, 3))
                    .hasSameHashCodeAs(index(DistanceOperator.COSINE, 3))
                    .isNotEqualTo(index(DistanceOperator.L2, 3));
        }

        @Test
        @DisplayName("每个算子的 operatorClass 就是 CREATE INDEX 里那一半")
        void operatorClassPerOperator() {
            assertThat(index(DistanceOperator.COSINE, 3).operatorClass())
                    .isEqualTo("vector_cosine_ops");
            assertThat(index(DistanceOperator.L2, 3).operatorClass()).isEqualTo("vector_l2_ops");
            assertThat(index(DistanceOperator.INNER_PRODUCT, 3).operatorClass())
                    .isEqualTo("vector_ip_ops");
        }
    }

    @Nested
    @DisplayName("构造期维度闸门")
    class Dimensions {

        @Test
        @DisplayName("2000 维（可索引上限）可以声明")
        void acceptsMaxIndexable() {
            assertThat(
                            index(DistanceOperator.COSINE, PgVectorLimits.MAX_INDEXABLE_DIMENSIONS)
                                    .dimensions())
                    .isEqualTo(2000);
        }

        @Test
        @DisplayName("2001 维拒绝，并说明为什么（HNSW/IVFFlat 都建不出来）")
        void rejectsAboveIndexableLimit() {
            assertThatThrownBy(
                            () ->
                                    index(
                                            DistanceOperator.COSINE,
                                            PgVectorLimits.MAX_INDEXABLE_DIMENSIONS + 1))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("2001")
                    .hasMessageContaining("2000")
                    .hasMessageContaining("降维");
        }

        @Test
        @DisplayName("0 与负维度拒绝")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> index(DistanceOperator.COSINE, 0))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("必须为正数");
            assertThatThrownBy(() -> index(DistanceOperator.COSINE, -768))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("必须为正数");
        }
    }

    @Nested
    @DisplayName("构造期标识符闸门")
    class Identifiers {

        @Test
        @DisplayName("索引名带注入载荷拒绝")
        void rejectsBadIndexName() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx; DROP TABLE sku",
                                            "sku_embedding",
                                            "embedding",
                                            IndexType.HNSW,
                                            DistanceOperator.COSINE,
                                            3))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("表名带空格拒绝")
        void rejectsBadTable() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx",
                                            "sku embedding",
                                            "embedding",
                                            IndexType.HNSW,
                                            DistanceOperator.COSINE,
                                            3))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("列名为 null 拒绝")
        void rejectsNullColumn() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx",
                                            "sku_embedding",
                                            null,
                                            IndexType.HNSW,
                                            DistanceOperator.COSINE,
                                            3))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
        }
    }

    @Nested
    @DisplayName("requireOperator：算子与 opclass 必须配套")
    class OperatorGate {

        @Test
        @DisplayName("三个算子各自配套自己的索引，全部放行")
        void acceptsMatchingOperators() {
            for (DistanceOperator operator : DistanceOperator.values()) {
                index(operator, 3).requireOperator(operator);
            }
        }

        @Test
        @DisplayName("不配套时报错，消息同时给出索引 opclass 与查询 opclass，并点名全表扫描")
        void rejectsMismatch() {
            VectorIndex cosine = index(DistanceOperator.COSINE, 3);
            assertThatThrownBy(() -> cosine.requireOperator(DistanceOperator.L2))
                    .isInstanceOf(OperatorIndexMismatchException.class)
                    .hasMessageContaining("vector_cosine_ops")
                    .hasMessageContaining("vector_l2_ops")
                    .hasMessageContaining("全表扫描");
        }

        @Test
        @DisplayName("null 算子拒绝，消息说明查询没有指定算子")
        void rejectsNullOperator() {
            assertThatThrownBy(() -> index(DistanceOperator.COSINE, 3).requireOperator(null))
                    .isInstanceOf(OperatorIndexMismatchException.class)
                    .hasMessageContaining("没有指定距离算子");
        }
    }

    @Nested
    @DisplayName("requireDimensions：维度必须一致")
    class DimensionGate {

        @Test
        @DisplayName("一致时静默通过")
        void passesWhenMatching() {
            index(DistanceOperator.COSINE, 3).requireDimensions(new float[] {1f, 2f, 3f});
        }

        @Test
        @DisplayName("不一致时报错，且 subject 是「表.列」而不是光秃秃的 vector")
        void reportsTableDotColumn() {
            assertThatThrownBy(
                            () ->
                                    index(DistanceOperator.COSINE, 3)
                                            .requireDimensions(new float[] {1f}))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("sku_embedding.embedding")
                    .hasMessageContaining("期望 3 维")
                    .hasMessageContaining("实际 1 维");
        }

        @Test
        @DisplayName("null 向量拒绝，且不是裸 NPE")
        void rejectsNullVector() {
            assertThatThrownBy(() -> index(DistanceOperator.COSINE, 3).requireDimensions(null))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不能为 null");
        }
    }
}
