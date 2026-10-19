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

import io.github.nephets.pgvector.core.VectorDimensionMismatchException;
import io.github.nephets.pgvector.core.VectorFormatException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.AttributeConverter;

@DisplayName("JPA vector AttributeConverter")
class VectorConverterTest {

    private static final float[] VECTOR = {1.0f, -2.5f, 0.0f, 3.25f};
    private static final String LITERAL = "[1.0,-2.5,0.0,3.25]";

    @Nested
    @DisplayName("写库")
    class ToDatabase {

        @Test
        @DisplayName("转成 pgvector 文本表示")
        void convertsToLiteral() {
            assertThat(new VectorConverter().convertToDatabaseColumn(VECTOR)).isEqualTo(LITERAL);
        }

        @Test
        @DisplayName("null 字段转成 null（可空列）")
        void convertsNullToNull() {
            assertThat(new VectorConverter().convertToDatabaseColumn(null)).isNull();
        }

        @Test
        @DisplayName("声明维度后维度不符直接报错，不会写出一条会被数据库拒绝的 SQL")
        void checksDimensions() {
            assertThatThrownBy(
                            () -> new VectorConverter(1536).convertToDatabaseColumn(new float[768]))
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 1536 维")
                    .hasMessageContaining("实际 768 维")
                    .hasMessageContaining("JPA vector 字段");
        }

        @Test
        @DisplayName("维度一致时放行")
        void passesWhenMatching() {
            assertThat(new VectorConverter(4).convertToDatabaseColumn(VECTOR)).isEqualTo(LITERAL);
        }

        @Test
        @DisplayName("默认不校验维度（维度由数据库列类型把关）")
        void doesNotCheckByDefault() {
            assertThat(new VectorConverter().convertToDatabaseColumn(new float[] {1f}))
                    .isEqualTo("[1.0]");
        }
    }

    @Nested
    @DisplayName("读库")
    class FromDatabase {

        @Test
        @DisplayName("从 pgvector 文本表示转回 float[]")
        void convertsFromLiteral() {
            assertThat(new VectorConverter().convertToEntityAttribute(LITERAL))
                    .containsExactly(VECTOR);
        }

        @Test
        @DisplayName("pgvector 实际返回的是带空格的")
        void handlesSpaces() {
            assertThat(new VectorConverter().convertToEntityAttribute(" [1.0, -2.5] "))
                    .containsExactly(1.0f, -2.5f);
        }

        @Test
        @DisplayName("NULL 列 -> null")
        void convertsNullToNull() {
            assertThat(new VectorConverter().convertToEntityAttribute(null)).isNull();
            assertThat(new VectorConverter().convertToEntityAttribute("   ")).isNull();
        }

        @Test
        @DisplayName("读出来的维度与声明不符时报错：说明有人改过列类型")
        void checksDimensionsOnRead() {
            assertThatThrownBy(
                            () -> new VectorConverter(1536).convertToEntityAttribute("[1.0,2.0]"))
                    .isInstanceOf(VectorDimensionMismatchException.class);
        }

        @Test
        @DisplayName("脏文本报错而不是返回半个向量")
        void rejectsMalformed() {
            assertThatThrownBy(() -> new VectorConverter().convertToEntityAttribute("[1.0,abc]"))
                    .isInstanceOf(VectorFormatException.class);
        }
    }

    @Nested
    @DisplayName("往返与 JPA 契约")
    class Contract {

        @Test
        @DisplayName("往返无损")
        void roundTrip() {
            VectorConverter converter = new VectorConverter();
            assertThat(
                            converter.convertToEntityAttribute(
                                    converter.convertToDatabaseColumn(VECTOR)))
                    .containsExactly(VECTOR);
        }

        @Test
        @DisplayName("确实实现了 JPA 的 AttributeConverter<float[], String>")
        void implementsJpaContract() {
            AttributeConverter<float[], String> converter = new VectorConverter();
            assertThat(converter.convertToDatabaseColumn(VECTOR)).isEqualTo(LITERAL);
        }

        @Test
        @DisplayName("构造器参数校验")
        void constructorValidation() {
            assertThatThrownBy(() -> new VectorConverter(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new VectorConverter(VectorConverter.NO_DIMENSION_CHECK).expectedDimensions())
                    .isEqualTo(-1);
            assertThat(new VectorConverter(1536).expectedDimensions()).isEqualTo(1536);
        }
    }
}
