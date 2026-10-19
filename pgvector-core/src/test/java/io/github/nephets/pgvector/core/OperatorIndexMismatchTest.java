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

@DisplayName("算子与索引 opclass 不匹配：必须报错，不能静默退化成全表扫描")
class OperatorIndexMismatchTest {

    private static final VectorIndex COSINE_HNSW =
            new VectorIndex(
                    "idx_sku_embedding",
                    "sku_embedding",
                    "embedding",
                    IndexType.HNSW,
                    DistanceOperator.COSINE,
                    3);

    @Nested
    @DisplayName("算子校验")
    class Operator {

        @Test
        @DisplayName("算子与 opclass 一致时放行")
        void acceptsMatchingOperator() {
            HybridQuery q =
                    HybridQuery.builder()
                            .from("sku_embedding c")
                            .nearest(
                                    "c.embedding",
                                    new float[] {1f, 2f, 3f},
                                    DistanceOperator.COSINE)
                            .withIndex(COSINE_HNSW)
                            .build();
            assertThat(q.sql()).contains("<=> ?::vector");
        }

        @Test
        @DisplayName("余弦索引上跑 L2 查询：抛错，绝不静默")
        void rejectsL2OnCosineIndex() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("sku_embedding c")
                                            .nearest(
                                                    "c.embedding",
                                                    new float[] {1f, 2f, 3f},
                                                    DistanceOperator.L2)
                                            .withIndex(COSINE_HNSW)
                                            .build())
                    .isInstanceOf(OperatorIndexMismatchException.class)
                    .hasMessageContaining("vector_cosine_ops")
                    .hasMessageContaining("vector_l2_ops")
                    .hasMessageContaining("全表扫描");
        }

        @Test
        @DisplayName("余弦索引上跑内积查询：抛错")
        void rejectsInnerProductOnCosineIndex() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("sku_embedding c")
                                            .nearest(
                                                    "c.embedding",
                                                    new float[] {1f, 2f, 3f},
                                                    DistanceOperator.INNER_PRODUCT)
                                            .withIndex(COSINE_HNSW)
                                            .build())
                    .isInstanceOf(OperatorIndexMismatchException.class)
                    .hasMessageContaining("vector_ip_ops");
        }

        @Test
        @DisplayName("异常里能取回索引与算子，便于程序化处理")
        void exposesContext() {
            OperatorIndexMismatchException thrown =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            OperatorIndexMismatchException.class,
                            () ->
                                    HybridQuery.builder()
                                            .from("sku_embedding c")
                                            .nearest(
                                                    "c.embedding",
                                                    new float[] {1f, 2f, 3f},
                                                    DistanceOperator.L2)
                                            .withIndex(COSINE_HNSW)
                                            .build());
            assertThat(thrown.index()).isEqualTo(COSINE_HNSW);
            assertThat(thrown.queryOperator()).isEqualTo(DistanceOperator.L2);
        }

        @Test
        @DisplayName("直接调用 VectorIndex#requireOperator 也要拦住")
        void guardsAtVectorIndexLevel() {
            assertThatThrownBy(() -> COSINE_HNSW.requireOperator(DistanceOperator.L2))
                    .isInstanceOf(OperatorIndexMismatchException.class);
            assertThatThrownBy(() -> COSINE_HNSW.requireOperator(null))
                    .isInstanceOf(OperatorIndexMismatchException.class)
                    .hasMessageContaining("没有指定距离算子");
        }
    }

    @Nested
    @DisplayName("维度校验")
    class Dimensions {

        @Test
        @DisplayName("维度一致时放行，并自动继承索引维度")
        void acceptsMatchingDimensions() {
            HybridQuery q =
                    HybridQuery.builder()
                            .from("sku_embedding c")
                            .nearest(
                                    "c.embedding",
                                    new float[] {1f, 2f, 3f},
                                    DistanceOperator.COSINE)
                            .withIndex(COSINE_HNSW)
                            .build();
            assertThat(q.sql()).isNotNull();
        }

        @Test
        @DisplayName("查询向量维度与索引维度不符：报错，消息含期望与实际")
        void rejectsDimensionMismatch() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("sku_embedding c")
                                            .nearest(
                                                    "c.embedding",
                                                    new float[] {1f, 2f},
                                                    DistanceOperator.COSINE)
                                            .withIndex(COSINE_HNSW)
                                            .build())
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 3 维")
                    .hasMessageContaining("实际 2 维")
                    .hasMessageContaining("c.embedding");
        }

        @Test
        @DisplayName("不挂索引时也能用 expectDimensions 单独校验")
        void checksDimensionsWithoutIndex() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("sku_embedding c")
                                            .nearest(
                                                    "c.embedding",
                                                    new float[] {1f, 2f},
                                                    DistanceOperator.COSINE)
                                            .expectDimensions(1536)
                                            .build())
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 1536 维")
                    .hasMessageContaining("实际 2 维");
        }
    }

    @Nested
    @DisplayName("VectorIndex 自身的构造校验")
    class IndexDeclaration {

        @Test
        @DisplayName("超过 2000 维的索引声明直接报错：这种索引根本建不出来")
        void rejectsUnbuildableDimensions() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx",
                                            "doc",
                                            "embedding",
                                            IndexType.HNSW,
                                            DistanceOperator.COSINE,
                                            4096))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("2000")
                    .hasMessageContaining("降维");
        }

        @Test
        @DisplayName("2000 维（上限）可以声明")
        void acceptsMaxIndexableDimensions() {
            VectorIndex index =
                    new VectorIndex(
                            "idx",
                            "doc",
                            "embedding",
                            IndexType.HNSW,
                            DistanceOperator.COSINE,
                            PgVectorLimits.MAX_INDEXABLE_DIMENSIONS);
            assertThat(index.operatorClass()).isEqualTo("vector_cosine_ops");
        }

        @Test
        @DisplayName("索引名、表名、列名同样要过标识符白名单")
        void rejectsBadIdentifiers() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx;--",
                                            "doc",
                                            "embedding",
                                            IndexType.HNSW,
                                            DistanceOperator.COSINE,
                                            3))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("索引类型与算子不能为 null")
        void rejectsNulls() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx",
                                            "doc",
                                            "embedding",
                                            null,
                                            DistanceOperator.COSINE,
                                            3))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx", "doc", "embedding", IndexType.HNSW, null, 3))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("维度必须为正")
        void rejectsNonPositiveDimensions() {
            assertThatThrownBy(
                            () ->
                                    new VectorIndex(
                                            "idx",
                                            "doc",
                                            "embedding",
                                            IndexType.HNSW,
                                            DistanceOperator.COSINE,
                                            0))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("必须为正数");
        }
    }
}
