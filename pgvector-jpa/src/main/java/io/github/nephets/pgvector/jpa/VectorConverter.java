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

import io.github.nephets.pgvector.core.VectorCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA 的 {@code vector} ↔ {@code float[]} 属性转换器。
 *
 * <p>用法：
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "sku_embedding")
 * public class SkuEmbedding {
 *
 *   @Id
 *   private Long skuId;
 *
 *   // 列类型必须显式写成 vector(N)：JPA 不会从 float[] 推断出 vector
 *   @Column(name = "embedding", columnDefinition = "vector(1536)", nullable = false)
 *   @Convert(converter = VectorConverter.class)
 *   private float[] embedding;
 * }
 * }</pre>
 *
 * <h2>能力边界（请务必读完再用）</h2>
 *
 * <p>这个转换器只解决「实体的向量字段怎么存、怎么取」。<strong>它不能让你在 JPQL 里写距离算子</strong>： JPQL 没有 {@code <=>}
 * 这种运算符，Hibernate 也无从翻译。带向量近邻的查询请用 core 的 {@code HybridQuery} 走原生 SQL，再用 {@code
 * EntityManager#createNativeQuery} 取回。
 *
 * <p>另外，转换器把值以 pgvector 的文本表示绑定为字符串。PostgreSQL 对「字符串 → 任意类型」的 赋值转换走的是 I/O conversion，所以 INSERT /
 * UPDATE 能正常工作；但这也意味着它不适合出现在 WHERE 的比较表达式里——那种场景同样请走原生 SQL。
 */
@Converter(autoApply = false)
public class VectorConverter implements AttributeConverter<float[], String> {

    private static final Logger log = LoggerFactory.getLogger(VectorConverter.class);

    /** -1 表示不校验维度。 */
    public static final int NO_DIMENSION_CHECK = -1;

    private final int expectedDimensions;

    public VectorConverter() {
        this(NO_DIMENSION_CHECK);
    }

    /**
     * @param expectedDimensions 期望维度，通常等于建表时的 {@code vector(N)}； 传 {@link #NO_DIMENSION_CHECK}
     *     表示不校验
     */
    public VectorConverter(int expectedDimensions) {
        if (expectedDimensions != NO_DIMENSION_CHECK && expectedDimensions <= 0) {
            throw new IllegalArgumentException(
                    "期望维度必须为正数，或者传 NO_DIMENSION_CHECK("
                            + NO_DIMENSION_CHECK
                            + ") 表示不校验；实际为 "
                            + expectedDimensions);
        }
        this.expectedDimensions = expectedDimensions;
    }

    /** 期望维度；{@link #NO_DIMENSION_CHECK} 表示不校验。 */
    public int expectedDimensions() {
        return expectedDimensions;
    }

    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) {
            return null;
        }
        log.debug("convertToDatabaseColumn dims={}", attribute.length);
        if (expectedDimensions != NO_DIMENSION_CHECK) {
            VectorCodec.requireDimensions(attribute, expectedDimensions, "JPA vector 字段");
        }
        return VectorCodec.toLiteral(attribute);
    }

    @Override
    public float[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        log.debug("convertToEntityAttribute literalChars={}", dbData.length());
        float[] values = VectorCodec.fromLiteral(dbData);
        if (expectedDimensions != NO_DIMENSION_CHECK) {
            VectorCodec.requireDimensions(values, expectedDimensions, "JPA vector 字段");
        }
        return values;
    }
}
