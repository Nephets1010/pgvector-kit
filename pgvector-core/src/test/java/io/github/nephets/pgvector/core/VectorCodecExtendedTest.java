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
 * 编解码的边角用例：{@code VectorCodecTest} 覆盖主干，这里补<strong>畸形文本</strong>与边界。
 *
 * <p>重点在「坏输入必须被明确拒绝」：pgvector 的文本表示是手工拼/拆的， 任何一个被静默放过的畸形值，都会在几周后变成一次「召回怎么这么差」的事故。
 */
@DisplayName("vector 编解码：畸形输入与边界")
class VectorCodecExtendedTest {

    @Nested
    @DisplayName("解码：畸形文本必须明确拒绝")
    class MalformedLiterals {

        @Test
        @DisplayName("尾随逗号：[1.0,] 会拆出空串，必须报错而不是补一个 0")
        void rejectsTrailingComma() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1.0,]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("空字符串");
        }

        @Test
        @DisplayName("前导逗号：[,1.0] 同样拆出空串")
        void rejectsLeadingComma() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[,1.0]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("第 0 个分量");
        }

        @Test
        @DisplayName("一个 token 里两个小数点：[1.0.0] 不是浮点数")
        void rejectsDoubleDecimalPoint() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1.0.0]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不是合法浮点数");
        }

        @Test
        @DisplayName("残缺科学计数法：[1e] 不是浮点数")
        void rejectsTruncatedExponent() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1e]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不是合法浮点数");
        }

        @Test
        @DisplayName("只有空白：[ ] 等于 0 维，报错")
        void rejectsWhitespaceOnly() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[ ]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("0 维");
        }

        @Test
        @DisplayName("嵌套括号：[[1.0]] 的第一项不是浮点数")
        void rejectsNestedBrackets() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[[1.0]]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不是合法浮点数");
        }

        @Test
        @DisplayName("没有括号的裸列表：1.0,2.0 拒绝")
        void rejectsMissingBrackets() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("1.0,2.0"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("必须以 [ 开头");
        }

        @Test
        @DisplayName("只有左括号：[1.0 拒绝")
        void rejectsOnlyLeftBracket() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[1.0"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("必须以 [ 开头");
        }

        @Test
        @DisplayName("正无穷文本拒绝：parseFloat 会放过它，这里补上有限性检查")
        void rejectsPositiveInfinityLiteral() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[Infinity]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("Infinity");
        }

        @Test
        @DisplayName("负无穷文本拒绝")
        void rejectsNegativeInfinityLiteral() {
            assertThatThrownBy(() -> VectorCodec.fromLiteral("[-Infinity]"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("Infinity");
        }

        @Test
        @DisplayName("显式正号是合法浮点数：+1.0 放行（parseFloat 的既有语义，不擅自收紧）")
        void acceptsExplicitPlusSign() {
            assertThat(VectorCodec.fromLiteral("[+1.0]")).containsExactly(1.0f);
        }

        @Test
        @DisplayName("逗号后的任意空白都容忍：pgvector 各版本返回的空格数量不一致")
        void toleratesArbitrarySpacesAfterComma() {
            assertThat(VectorCodec.fromLiteral("[1.0,  2.0,\t3.0]")).containsExactly(1f, 2f, 3f);
        }
    }

    @Nested
    @DisplayName("编码：边界")
    class EncodeBoundaries {

        @Test
        @DisplayName("1 维向量也合法（pgvector 接受 vector(1)）")
        void encodesSingleDimension() {
            assertThat(VectorCodec.toLiteral(new float[] {0.5f})).isEqualTo("[0.5]");
        }

        @Test
        @DisplayName("16000 维（上限）可以编码")
        void encodesMaxDimensions() {
            float[] values = new float[PgVectorLimits.MAX_DIMENSIONS];
            values[0] = 1.5f;
            assertThat(VectorCodec.toLiteral(values)).startsWith("[1.5,").endsWith("]");
        }

        @Test
        @DisplayName("NaN 的下标准确报出：第 2 个分量")
        void reportsExactIndexOfNaN() {
            assertThatThrownBy(() -> VectorCodec.toLiteral(new float[] {1f, 2f, Float.NaN, 4f}))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("第 2 个分量");
        }

        @Test
        @DisplayName("Float.toString 的最短往返表示：0.1f 编出来就是 0.1，不多不少")
        void usesShortestRoundTripRepresentation() {
            assertThat(VectorCodec.toLiteral(new float[] {0.1f})).isEqualTo("[0.1]");
        }
    }

    @Nested
    @DisplayName("requireDimensions：定位信息")
    class DimensionSubject {

        @Test
        @DisplayName("subject 为 null 时消息不带括号定位，但不许 NPE")
        void acceptsNullSubject() {
            assertThatThrownBy(() -> VectorCodec.requireDimensions(new float[] {1f, 2f}, 3, null))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 3 维")
                    .hasMessageContaining("实际 2 维")
                    .hasMessageNotContaining("（");
        }

        @Test
        @DisplayName("subject 为空白时同样不带定位括号")
        void acceptsBlankSubject() {
            assertThatThrownBy(() -> VectorCodec.requireDimensions(new float[] {1f}, 3, "   "))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageNotContaining("（");
        }

        @Test
        @DisplayName("values 为 null：报 VectorFormatException 而不是 NPE")
        void rejectsNullValues() {
            assertThatThrownBy(() -> VectorCodec.requireDimensions(null, 3, "sku.embedding"))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("不能为 null");
        }

        @Test
        @DisplayName("维度一致时静默通过（返回 void，什么都不做）")
        void passesWhenMatching() {
            VectorCodec.requireDimensions(new float[] {1f, 2f, 3f}, 3, "sku.embedding");
        }

        @Test
        @DisplayName("两参重载的默认 subject 是 vector")
        void defaultSubjectIsVector() {
            assertThatThrownBy(() -> VectorCodec.requireDimensions(new float[] {1f}, 2))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("（vector）");
        }
    }
}
