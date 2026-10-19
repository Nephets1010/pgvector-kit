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

/**
 * 向量维度与声明不符。
 *
 * <p>这个异常是本项目存在的核心理由之一：pgvector 的 {@code vector(1536)} 把维度写进了列类型， 维度对不上时 PostgreSQL 会报错，但报错信息是
 * {@code expected 1536 dimensions, not 768} 这种 数据库原话，且发生在 SQL 执行阶段——离写出这段向量的代码可能隔了三层调用。
 *
 * <p>这里的策略是：在 Java 侧就 fail-fast，并且把「期望多少 / 实际多少 / 是谁」写进消息里。
 */
public class VectorDimensionMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient String subject;
    private final transient int expectedDimensions;
    private final transient int actualDimensions;

    public VectorDimensionMismatchException(
            String subject, int expectedDimensions, int actualDimensions) {
        super(
                "向量维度不符"
                        + (subject == null || subject.isBlank() ? "" : "（" + subject + "）")
                        + "：期望 "
                        + expectedDimensions
                        + " 维，实际 "
                        + actualDimensions
                        + " 维。pgvector 的 vector(N) 维度是列类型的一部分，不会静默截断；"
                        + "请检查 embedding 模型的输出维度与建表时的 vector(N) 是否一致。");
        this.subject = subject;
        this.expectedDimensions = expectedDimensions;
        this.actualDimensions = actualDimensions;
    }

    /** 出错的那个向量是干什么用的（列名、参数名等），便于定位。 */
    public String subject() {
        return subject;
    }

    public int expectedDimensions() {
        return expectedDimensions;
    }

    public int actualDimensions() {
        return actualDimensions;
    }
}
