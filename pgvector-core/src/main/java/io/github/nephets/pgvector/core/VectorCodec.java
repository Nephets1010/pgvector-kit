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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code float[]} 与 pgvector 的 {@code vector} 文本表示之间的编解码。
 *
 * <p>pgvector 在线路上用的文本格式是 {@code [1,2,3]}。JDBC 驱动没有为它提供任何类型映射， 于是每个接 pgvector 的项目都要手写一遍「拼字符串 /
 * 拆字符串」，而这段手写代码通常有三个通病：
 *
 * <ol>
 *   <li>用 {@code String.valueOf(float)} 以外的格式化方式丢精度；
 *   <li>不校验维度，{@code vector(3)} 里塞进 4 维向量时靠数据库报错才发现；
 *   <li>不拒绝 {@code NaN} / {@code Infinity}，让脏数据一路写到磁盘。
 * </ol>
 *
 * <p>这里一次解决三件事，全部 fail-fast。
 */
public final class VectorCodec {

    private static final Logger log = LoggerFactory.getLogger(VectorCodec.class);

    private VectorCodec() {}

    /**
     * 把 {@code float[]} 编成 pgvector 的文本表示。
     *
     * @param values 分量，不能为 null、不能为空、不能有 NaN/Infinity、维度不超过 {@link PgVectorLimits#MAX_DIMENSIONS}
     * @return 形如 {@code [0.1,-0.2,0.3]} 的字符串
     */
    public static String toLiteral(float[] values) {
        if (values == null) {
            throw new VectorFormatException("vector 的分量数组不能为 null");
        }
        if (values.length == 0) {
            throw new VectorFormatException("vector 不能是 0 维：pgvector 不接受空向量");
        }
        if (values.length > PgVectorLimits.MAX_DIMENSIONS) {
            throw new VectorFormatException(
                    "vector 维度 "
                            + values.length
                            + " 超过 pgvector 上限 "
                            + PgVectorLimits.MAX_DIMENSIONS
                            + " 维");
        }
        log.debug("encode vector literal with {} dimensions", values.length);
        StringBuilder sb = new StringBuilder(values.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            float component = values[i];
            if (!Float.isFinite(component)) {
                throw new VectorFormatException(
                        "vector 第 " + i + " 个分量是 " + component + "：pgvector 不接受 NaN / Infinity");
            }
            if (i > 0) {
                sb.append(',');
            }
            // Float.toString 是 Java 保证的最短往返表示：float -> String -> float 必然得到同一个值。
            // 用 %.6f 之类的方式格式化会丢精度，是手写转换里最常见的坑。
            sb.append(Float.toString(component));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 把 pgvector 的文本表示解析成 {@code float[]}。
     *
     * @param literal 形如 {@code [0.1,-0.2,0.3]} 的字符串，允许两端空白与逗号后的空格
     * @return 分量数组
     * @throws VectorFormatException 括号不匹配、为空、含非数字、含 NaN/Infinity、维度超上限
     */
    public static float[] fromLiteral(String literal) {
        if (literal == null) {
            throw new VectorFormatException("vector 的文本表示不能为 null");
        }
        String trimmed = literal.trim();
        if (trimmed.length() < 2
                || trimmed.charAt(0) != '['
                || trimmed.charAt(trimmed.length() - 1) != ']') {
            throw new VectorFormatException(
                    "不是合法的 vector 文本表示（必须以 [ 开头、以 ] 结尾）：\"" + trimmed + "\"");
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            throw new VectorFormatException("vector 不能是 0 维：文本表示为 \"[]\"");
        }
        String[] parts = body.split(",", -1);
        if (parts.length > PgVectorLimits.MAX_DIMENSIONS) {
            throw new VectorFormatException(
                    "vector 维度 "
                            + parts.length
                            + " 超过 pgvector 上限 "
                            + PgVectorLimits.MAX_DIMENSIONS
                            + " 维");
        }
        log.debug(
                "decode vector literal ({} chars) into {} components",
                trimmed.length(),
                parts.length);
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isEmpty()) {
                throw new VectorFormatException("vector 第 " + i + " 个分量是空字符串：\"" + trimmed + "\"");
            }
            float parsed;
            try {
                parsed = Float.parseFloat(token);
            } catch (NumberFormatException e) {
                log.error(
                        "failed to parse vector component \"{}\" from literal \"{}\"",
                        token,
                        trimmed,
                        e);
                throw new VectorFormatException(
                        "vector 第 " + i + " 个分量 \"" + token + "\" 不是合法浮点数：\"" + trimmed + "\"", e);
            }
            if (!Float.isFinite(parsed)) {
                throw new VectorFormatException(
                        "vector 第 " + i + " 个分量是 " + parsed + "：pgvector 不接受 NaN / Infinity");
            }
            values[i] = parsed;
        }
        return values;
    }

    /**
     * 校验维度，不符立即抛错。
     *
     * <p>刻意不做「截断到 expected 维」或「补零到 expected 维」：那两种静默修正会让查询结果 看起来正常、实际全错，而且错误要在几周后才因为「召回怎么这么差」被发现。
     *
     * @param values 待校验的向量
     * @param expectedDimensions 期望维度
     * @param subject 出错时用于定位的说明（列名、参数名等），允许为 null
     */
    public static void requireDimensions(float[] values, int expectedDimensions, String subject) {
        if (values == null) {
            throw new VectorFormatException("vector 的分量数组不能为 null");
        }
        log.debug(
                "requireDimensions subject={} expected={} actual={}",
                subject,
                expectedDimensions,
                values.length);
        if (values.length != expectedDimensions) {
            throw new VectorDimensionMismatchException(subject, expectedDimensions, values.length);
        }
    }

    /** 校验维度，{@code subject} 缺省为 {@code "vector"}。 */
    public static void requireDimensions(float[] values, int expectedDimensions) {
        requireDimensions(values, expectedDimensions, "vector");
    }
}
