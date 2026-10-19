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
package io.github.nephets.pgvector.jpa;

import io.github.nephets.pgvector.core.VectorFormatException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA 转换器的边角用例：{@code VectorConverterTest} 覆盖主路径， 这里补「带维度声明的往返」与「坏数据在写库前就被拒」。
 *
 * <p>转换器是脏数据进数据库的最后一道 Java 侧闸门，它放行的每个畸形值都会变成一条 数据库里的坏行——所以这里的断言全部指向「明确拒绝」。
 */
@DisplayName("JPA 转换器：往返与坏数据拒绝")
class VectorConverterContractTest {

    private static final float[] VECTOR = {1.0f, -2.5f, 0.0f, 3.25f};
    private static final String LITERAL = "[1.0,-2.5,0.0,3.25]";

    @Test
    @DisplayName("带维度声明的完整往返：写出去再读回来，逐位相等")
    void roundTripWithDeclaredDimensions() {
        VectorConverter converter = new VectorConverter(4);
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(VECTOR)))
                .containsExactly(VECTOR);
    }

    @Test
    @DisplayName("读库时空格变体也走同一条解析：pgvector 各版本返回的空格不一致")
    void readsSpacedLiteral() {
        assertThat(new VectorConverter(2).convertToEntityAttribute(" [1.0,  2.0] "))
                .containsExactly(1.0f, 2.0f);
    }

    @Test
    @DisplayName("写库时遇到 NaN：VectorFormatException，不会生成一条数据库要拒绝的 SQL")
    void rejectsNaNOnWrite() {
        assertThatThrownBy(
                        () ->
                                new VectorConverter()
                                        .convertToDatabaseColumn(new float[] {1f, Float.NaN}))
                .isInstanceOf(VectorFormatException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    @DisplayName("读库时遇到空括号 []：0 维拒绝")
    void rejectsEmptyLiteralOnRead() {
        assertThatThrownBy(() -> new VectorConverter().convertToEntityAttribute("[]"))
                .isInstanceOf(VectorFormatException.class)
                .hasMessageContaining("0 维");
    }

    @Test
    @DisplayName("默认期望维度就是 NO_DIMENSION_CHECK（-1）：默认把维度交给数据库列类型把关")
    void defaultsToNoDimensionCheck() {
        assertThat(new VectorConverter().expectedDimensions())
                .isEqualTo(VectorConverter.NO_DIMENSION_CHECK);
        assertThat(VectorConverter.NO_DIMENSION_CHECK).isEqualTo(-1);
    }

    @Test
    @DisplayName("1 维向量的声明与转换都合法（vector(1) 是合法列类型）")
    void handlesSingleDimension() {
        VectorConverter converter = new VectorConverter(1);
        assertThat(converter.convertToDatabaseColumn(new float[] {0.5f})).isEqualTo("[0.5]");
        assertThat(converter.convertToEntityAttribute("[0.5]")).containsExactly(0.5f);
    }
}
