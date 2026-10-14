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

import java.util.Locale;

/**
 * demo 用的「假 embedding」。
 *
 * <p><strong>它不是向量模型</strong>，也请千万不要拿它当模型用。它存在的唯一理由是让 demo 零依赖、零 API
 * Key、可复现：输入同一个商品名永远得到同一个向量，名字越像的两个商品 向量越接近，于是你能一眼看出排序结果对不对。
 *
 * <p>实现是字符 uni-gram + bi-gram 的直方图投影到 N 维再 L2 归一化。 真实项目请把这里换成你自己的 embedding 服务输出（维度记得和 {@code
 * vector(N)} 对齐）。
 */
public final class DeterministicEmbedding {

    private DeterministicEmbedding() {}

    /**
     * @param text 商品名之类的文本
     * @param dimensions 目标维度
     * @return L2 归一化后的向量
     */
    public static float[] of(String text, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("维度必须为正数，实际为 " + dimensions);
        }
        float[] values = new float[dimensions];
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);

        for (int i = 0; i < normalized.length(); i++) {
            values[Math.floorMod(normalized.charAt(i), dimensions)] += 0.5f;
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            int hash = normalized.charAt(i) * 31 + normalized.charAt(i + 1);
            values[Math.floorMod(hash, dimensions)] += 1.0f;
        }

        return normalize(values);
    }

    private static float[] normalize(float[] values) {
        double sumOfSquares = 0.0;
        for (float v : values) {
            sumOfSquares += (double) v * v;
        }
        if (sumOfSquares == 0.0) {
            // 空文本：退化成第一个分量为 1 的单位向量，至少保证维度与归一化的承诺成立
            values[0] = 1.0f;
            return values;
        }
        double norm = Math.sqrt(sumOfSquares);
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) (values[i] / norm);
        }
        return values;
    }

    /** 余弦相似度，demo 里用于自检「名字越像越接近」。 */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致：" + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 欧氏距离。 */
    public static double l2Distance(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** 内积。 */
    public static double innerProduct(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
