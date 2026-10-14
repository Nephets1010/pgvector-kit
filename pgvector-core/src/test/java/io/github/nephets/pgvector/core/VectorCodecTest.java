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

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("vector 文本表示编解码")
class VectorCodecTest {

    @Nested
    @DisplayName("编码：float[] -> [..]")
    class Encode {

        @Test
        @DisplayName("三维小数的文本表示就是 [1.0,2.0,3.0]")
        void encodesSimpleValues() {
            assertThat(VectorCodec.toLiteral(new float[] {1.0f, 2.0f, 3.0f}))
                    .isEqualTo("[1.0,2.0,3.0]");
        }

        @Test
        @DisplayName("负数与小数都保留")
        void encodesNegativeAndFraction() {
            assertThat(VectorCodec.toLiteral(new float[] {-0.5f, 0.25f})).isEqualTo("[-0.5,0.25]");
        }

        @Test
        @DisplayName("零写成 0.0，不是 0")
        void encodesZero() {
            assertThat(VectorCodec.toLiteral(new float[] {0f})).isEqualTo("[0.0]");
        }

        @Test
        @DisplayName("科学计数法是 float 的合法最短表示，pgvector 能解析")
        void encodesScientificNotation() {
            assertThat(VectorCodec.toLiteral(new float[] {1.0e-8f})).isEqualTo("[1.0E-8]");
        }

        @Test
        @DisplayName("null 数组直接报错")
        void rejectsNull() {
            assertThatThrownBy(() -> VectorCodec.toLiteral(null))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不能为 null");
        }

        @Test
        @DisplayName("0 维向量报错：pgvector 不接受空向量")
        void rejectsEmpty() {
            assertThatThrownBy(() -> VectorCodec.toLiteral(new float[0]))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("0 维");
        }

        @Test
        @DisplayName("NaN 必须拦下，否则脏数据会一路写进磁盘")
        void rejectsNaN() {
            assertThatThrownBy(() -> VectorCodec.toLiteral(new float[] {1f, Float.NaN}))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("第 1 个分量")
                    .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("正无穷必须拦下")
        void rejectsPositiveInfinity() {
            assertThatThrownBy(() -> VectorCodec.toLiteral(new float[] {Float.POSITIVE_INFINITY}))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("Infinity");
        }

        @Test
        @DisplayName("负无穷必须拦下")
        void rejectsNegativeInfinity() {
            assertThatThrownBy(() -> VectorCodec.toLiteral(new float[] {Float.NEGATIVE_INFINITY}))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("Infinity");
        }

        @Test
        @DisplayName("超过 16000 维报错，并给出上限数字")
        void rejectsTooManyDimensions() {
            assertThatThrownBy(
                            () ->
                                    VectorCodec.toLiteral(
                                            new float[PgVectorLimits.MAX_DIMENSIONS + 1]))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("16000");
        }

        @Test
        @DisplayName("16000 维（上限）可以编码")
        void acceptsMaxDimensions() {
            float[] values = new float[PgVectorLimits.MAX_DIMENSIONS];
            String literal = VectorCodec.toLiteral(values);
            assertThat(literal).startsWith("[0.0,").endsWith("]");
        }
    }

    @Nested
    @DisplayName("解码：[..] -> float[]")
    class Decode {

        @Test
        @DisplayName("解析标准表示")
        void parsesSimple() {
            assertThat(VectorCodec.fromLiteral("[1.0,2.0,3.0]")).containsExactly(1.0f, 2.0f, 3.0f);
        }

        @Test
        @DisplayName("pgvector 真实返回的是带空格的，必须能解析")
        void parsesWithSpaces() {
            assertThat(VectorCodec.fromLiteral(" [1.0, 2.0, 3.0] "))
                    .containsExactly(1.0f, 2.0f, 3.0f);
        }

        @Test
        @DisplayName("解析负数与科学计数法")
        void parsesNegativeAndScientific() {
            assertThat(VectorCodec.fromLiteral("[-1.5,2.5E-3]")).containsExactly(-1.5f, 2.5e-3f);
        }

        @Test
        @DisplayName("缺右括号报错，并把原文放进消息里")
        void rejectsMissingBracket() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1.0,2.0"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("[1.0,2.0");
        }

        @Test
        @DisplayName("空括号 [] 就是 0 维，报错")
        void rejectsEmptyBrackets() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("0 维");
        }

        @Test
        @DisplayName("非数字分量报错，并指出是第几个")
        void rejectsNonNumeric() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1.0,abc]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("第 1 个分量");
        }

        @Test
        @DisplayName("分量之间多打一个逗号会解析出空串，必须报错")
        void rejectsEmptyComponent() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1.0,,2.0]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("空字符串");
        }

        @Test
        @DisplayName("文本里的 NaN 也要拦下（parseFloat 会放过它）")
        void rejectsNaNLiteral() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[NaN]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("null 文本报错")
        void rejectsNullLiteral() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral(null))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不能为 null");
        }
    }

    @Nested
    @DisplayName("往返：精度必须无损")
    class RoundTrip {

        @Test
        @DisplayName("1000 个随机向量往返后逐位相等（不是近似相等）")
        void isLosslessForRandomVectors() {
            Random random = new Random(20261011L);
            for (int round = 0; round < 1000; round++) {
                float[] original = new float[8];
                for (int i = 0; i < original.length; i++) {
                    original[i] = (random.nextFloat() - 0.5f) * 2000f;
                }
                float[] decoded = VectorCodec.fromLiteral(VectorCodec.toLiteral(original));
                // 用 float 的位模式比较：assertEquals(float) 有 delta，位模式没有
                for (int i = 0; i < original.length; i++) {
                    assertThat(Float.floatToRawIntBits(decoded[i]))
                            .as("第 %d 轮、第 %d 个分量往返后位模式不同", round, i)
                            .isEqualTo(Float.floatToRawIntBits(original[i]));
                }
            }
        }

        @Test
        @DisplayName("极端小值与极端大值往返无损")
        void isLosslessForExtremeMagnitudes() {
            float[] original = {
                Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1.0e-30f, 1.0e30f
            };
            float[] decoded = VectorCodec.fromLiteral(VectorCodec.toLiteral(original));
            for (int i = 0; i < original.length; i++) {
                assertThat(Float.floatToRawIntBits(decoded[i]))
                        .isEqualTo(Float.floatToRawIntBits(original[i]));
            }
        }
    }

    @Nested
    @DisplayName("维度校验：必须 fail-fast")
    class DimensionCheck {

        @Test
        @DisplayName("维度一致时什么都不做")
        void passesWhenMatching() {
            VectorCodec.requireDimensions(new float[] {1f, 2f, 3f}, 3, "sku.embedding");
        }

        @Test
        @DisplayName("维度不符时报错，且消息里同时含期望与实际维度")
        void failsFastOnMismatch() {
            assertThatThrownBy(
                            () ->
                                    VectorCodec.requireDimensions(
                                            new float[] {1f, 2f}, 3, "sku.embedding"))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 3 维")
                    .hasMessageContaining("实际 2 维")
                    .hasMessageContaining("sku.embedding");
        }

        @Test
        @DisplayName("维度不符时绝不能静默截断：异常里能取回原始维度")
        void doesNotTruncateSilently() {
            VectorDimensionMismatchException thrown =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            VectorDimensionMismatchException.class,
                            () ->
                                    VectorCodec.requireDimensions(
                                            new float[768], 1536, "doc.embedding"));
            assertThat(thrown.expectedDimensions()).isEqualTo(1536);
            assertThat(thrown.actualDimensions()).isEqualTo(768);
            assertThat(thrown.subject()).isEqualTo("doc.embedding");
        }

        @Test
        @DisplayName("维度少了也报错（不是只拦超长）")
        void failsWhenTooShort() {
            assertThatThrownBy(() -> VectorCodec.requireDimensions(new float[] {1f}, 1536))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 1536 维")
                    .hasMessageContaining("实际 1 维");
        }
    }
}
