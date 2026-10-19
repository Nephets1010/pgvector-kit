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

/**
 * 异常类型的自身契约：继承关系、消息格式、访问器。
 *
 * <p>这些异常是<strong>给运维看的</strong>——消息里少一个数字，半夜排查就多一小时。 所以消息格式本身就是要保护的契约。
 */
@DisplayName("异常契约：继承、消息、访问器")
class ExceptionContractTest {

    private static final VectorIndex COSINE_INDEX =
            new VectorIndex(
                    "idx_sku_embedding",
                    "sku_embedding",
                    "embedding",
                    IndexType.HNSW,
                    DistanceOperator.COSINE,
                    3);

    @Nested
    @DisplayName("InvalidSqlIdentifierException")
    class InvalidIdentifier {

        @Test
        @DisplayName("是非受检异常：调用方不需要为它写 try-catch，事务边界统一回滚")
        void isUnchecked() {
            assertThat(new InvalidSqlIdentifierException("role", "value", "rule"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("消息格式：非法的 <role>：\"<value>\" —— <rule>")
        void messageFormat() {
            assertThat(new InvalidSqlIdentifierException("FROM 的表引用", "sku; DROP", "只允许表名"))
                    .hasMessage("非法的 FROM 的表引用：\"sku; DROP\" —— 只允许表名");
        }
    }

    @Nested
    @DisplayName("VectorFormatException")
    class Format {

        @Test
        @DisplayName("单参构造")
        void singleArg() {
            assertThat(new VectorFormatException("坏数据")).hasMessage("坏数据");
        }

        @Test
        @DisplayName("带 cause 的构造保留原异常（解析浮点数失败的根因不能丢）")
        void withCause() {
            RuntimeException cause = new NumberFormatException("For input string: \"abc\"");
            assertThat(new VectorFormatException("包装消息", cause)).hasMessage("包装消息").hasCause(cause);
        }

        @Test
        @DisplayName("是非受检异常")
        void isUnchecked() {
            assertThat(new VectorFormatException("x")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("VectorDimensionMismatchException")
    class DimensionMismatch {

        @Test
        @DisplayName("消息含期望、实际与定位；访问器能取回三个字段")
        void messageAndAccessors() {
            VectorDimensionMismatchException e =
                    new VectorDimensionMismatchException("sku.embedding", 1536, 768);
            assertThat(e)
                    .hasMessageContaining("期望 1536 维")
                    .hasMessageContaining("实际 768 维")
                    .hasMessageContaining("sku.embedding")
                    .hasMessageContaining("vector(N)");
            assertThat(e.subject()).isEqualTo("sku.embedding");
            assertThat(e.expectedDimensions()).isEqualTo(1536);
            assertThat(e.actualDimensions()).isEqualTo(768);
        }

        @Test
        @DisplayName("subject 为 null 时消息不带定位括号，访问器返回 null")
        void nullSubject() {
            VectorDimensionMismatchException e = new VectorDimensionMismatchException(null, 3, 4);
            assertThat(e).hasMessageNotContaining("（");
            assertThat(e.subject()).isNull();
        }

        @Test
        @DisplayName("是非受检异常")
        void isUnchecked() {
            assertThat(new VectorDimensionMismatchException("s", 1, 2))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("OperatorIndexMismatchException")
    class OperatorMismatch {

        @Test
        @DisplayName("完整消息：点名索引、opclass、查询算子，并解释 PostgreSQL 为什么不报错")
        void fullMessage() {
            OperatorIndexMismatchException e =
                    new OperatorIndexMismatchException(COSINE_INDEX, DistanceOperator.L2, null);
            assertThat(e)
                    .hasMessageContaining("idx_sku_embedding")
                    .hasMessageContaining("vector_cosine_ops")
                    .hasMessageContaining("vector_l2_ops")
                    .hasMessageContaining("PostgreSQL 不会报错")
                    .hasMessageContaining("重建一张索引");
            assertThat(e.index()).isEqualTo(COSINE_INDEX);
            assertThat(e.queryOperator()).isEqualTo(DistanceOperator.L2);
        }

        @Test
        @DisplayName("查询算子为 null：消息说明查询没有指定算子")
        void nullQueryOperator() {
            OperatorIndexMismatchException e =
                    new OperatorIndexMismatchException(COSINE_INDEX, null, null);
            assertThat(e).hasMessageContaining("没有指定距离算子");
            assertThat(e.queryOperator()).isNull();
        }

        @Test
        @DisplayName("索引为 null：消息说明索引信息缺失，而不是 NPE")
        void nullIndex() {
            OperatorIndexMismatchException e =
                    new OperatorIndexMismatchException(null, DistanceOperator.L2, null);
            assertThat(e).hasMessageContaining("索引信息缺失");
            assertThat(e.index()).isNull();
        }

        @Test
        @DisplayName("extraDetail 追加在消息末尾（调用方补一句现场信息）")
        void extraDetailAppended() {
            assertThat(
                            new OperatorIndexMismatchException(
                                    COSINE_INDEX, DistanceOperator.L2, "查询来自搜索页"))
                    .hasMessageEndingWith("查询来自搜索页");
        }

        @Test
        @DisplayName("是 IllegalStateException：状态错误（索引与查询不配套）而不是参数错误")
        void isIllegalState() {
            assertThat(new OperatorIndexMismatchException(COSINE_INDEX, DistanceOperator.L2, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
