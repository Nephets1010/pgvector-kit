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
package io.github.nephets.pgvector.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("demo 用的确定性假 embedding")
class DeterministicEmbeddingTest {

    private static final int DIMS = 8;

    @Test
    @DisplayName("同一个文本永远得到同一个向量（demo 可复现的前提）")
    void isDeterministic() {
        assertThat(DeterministicEmbedding.of("无线鼠标 黑色", DIMS))
                .containsExactly(DeterministicEmbedding.of("无线鼠标 黑色", DIMS));
    }

    @Test
    @DisplayName("不同文本得到不同向量")
    void differsAcrossTexts() {
        assertThat(DeterministicEmbedding.of("无线鼠标 黑色", DIMS))
                .isNotEqualTo(DeterministicEmbedding.of("显示器 27寸 2K", DIMS));
    }

    @Test
    @DisplayName("维度就是要求的维度")
    void respectsDimensions() {
        assertThat(DeterministicEmbedding.of("abc", DIMS)).hasSize(DIMS);
        assertThat(DeterministicEmbedding.of("abc", 1536)).hasSize(1536);
    }

    @Test
    @DisplayName("结果是单位向量：余弦相似度才有意义")
    void isUnitLength() {
        float[] v = DeterministicEmbedding.of("蓝牙耳机 入耳式", DIMS);
        double norm = 0.0;
        for (float component : v) {
            norm += (double) component * component;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("自己和自己的余弦相似度是 1")
    void selfSimilarityIsOne() {
        float[] v = DeterministicEmbedding.of("机械键盘 87键", DIMS);
        assertThat(DeterministicEmbedding.cosineSimilarity(v, v))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("名字越像，相似度越高 —— demo 的排序才看得懂")
    void similarNamesAreCloser() {
        float[] mouseBlack = DeterministicEmbedding.of("无线鼠标 黑色", DIMS);
        float[] mouseWhite = DeterministicEmbedding.of("无线鼠标 白色", DIMS);
        float[] monitor = DeterministicEmbedding.of("显示器 27寸 2K", DIMS);
        double mousePair = DeterministicEmbedding.cosineSimilarity(mouseBlack, mouseWhite);
        double mouseToMonitor = DeterministicEmbedding.cosineSimilarity(mouseBlack, monitor);
        assertThat(mousePair).isGreaterThan(mouseToMonitor);
    }

    @Test
    @DisplayName("空文本也能得到一个合法的单位向量，不会返回全 0")
    void handlesEmptyText() {
        float[] v = DeterministicEmbedding.of("", DIMS);
        assertThat(v).hasSize(DIMS);
        assertThat(Math.sqrt(DeterministicEmbedding.innerProduct(v, v)))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("null 文本等价于空文本")
    void handlesNullText() {
        assertThat(DeterministicEmbedding.of(null, DIMS))
                .containsExactly(DeterministicEmbedding.of("", DIMS));
    }

    @Test
    @DisplayName("L2 距离与内积的计算自洽：|a-b|^2 = |a|^2 + |b|^2 - 2ab")
    void distancesAreConsistent() {
        float[] a = DeterministicEmbedding.of("无线鼠标 黑色", DIMS);
        float[] b = DeterministicEmbedding.of("无线鼠标 白色", DIMS);
        double l2 = DeterministicEmbedding.l2Distance(a, b);
        double expected =
                DeterministicEmbedding.innerProduct(a, a)
                        + DeterministicEmbedding.innerProduct(b, b)
                        - 2 * DeterministicEmbedding.innerProduct(a, b);
        assertThat(l2 * l2).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("维度必须为正")
    void rejectsNonPositiveDimensions() {
        assertThatThrownBy(() -> DeterministicEmbedding.of("abc", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("维度不一致时拒绝计算相似度")
    void rejectsDimensionMismatchInSimilarity() {
        assertThatThrownBy(
                        () -> DeterministicEmbedding.cosineSimilarity(new float[3], new float[4]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度不一致");
    }
}
