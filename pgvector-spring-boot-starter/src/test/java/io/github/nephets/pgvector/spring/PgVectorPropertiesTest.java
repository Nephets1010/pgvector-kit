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
package io.github.nephets.pgvector.spring;

import io.github.nephets.pgvector.core.DistanceOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置边界：非法值回退到安全默认（而不是把 null / 负维度泄给查询路径）。 */
@DisplayName("PgVectorProperties 配置边界")
class PgVectorPropertiesTest {

    @Test
    @DisplayName("默认值：启用、COSINE、不校验维度、算子不配套即失败")
    void defaults() {
        PgVectorProperties properties = new PgVectorProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDefaultOperator()).isEqualTo(DistanceOperator.COSINE);
        assertThat(properties.getDefaultDimensions()).isZero();
        assertThat(properties.isFailOnOperatorIndexMismatch()).isTrue();
    }

    @Test
    @DisplayName("null 算子回退到 COSINE（不抛异常，应用仍能启动）")
    void nullOperatorFallsBack() {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setDefaultOperator(null);
        assertThat(properties.getDefaultOperator()).isEqualTo(DistanceOperator.COSINE);
    }

    @Test
    @DisplayName("负数维度回退到 0（0 = 不校验，是合法语义）")
    void negativeDimensionsFallsBackToZero() {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setDefaultDimensions(-5);
        assertThat(properties.getDefaultDimensions()).isZero();
    }

    @Test
    @DisplayName("0 维度是「不校验」的合法语义，原样保留")
    void zeroDimensionsIsLegal() {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setDefaultDimensions(0);
        assertThat(properties.getDefaultDimensions()).isZero();
    }

    @Test
    @DisplayName("正常值原样透传")
    void validValuesPassThrough() {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setDefaultOperator(DistanceOperator.L2);
        properties.setDefaultDimensions(1536);
        assertThat(properties.getDefaultOperator()).isEqualTo(DistanceOperator.L2);
        assertThat(properties.getDefaultDimensions()).isEqualTo(1536);
    }
}
