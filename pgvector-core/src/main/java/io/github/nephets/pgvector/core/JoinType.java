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
 * JOIN 类型。
 *
 * <p>刻意没有 CROSS：{@code CROSS JOIN} 不带 {@code ON}，而 {@link HybridQuery.Join} 强制要求 ON 条件， 放进 CROSS
 * 只会生成一条语法错误的 SQL。
 */
public enum JoinType {
    INNER("INNER"),
    LEFT("LEFT"),
    RIGHT("RIGHT");

    private final String keyword;

    JoinType(String keyword) {
        this.keyword = keyword;
    }

    public String keyword() {
        return keyword;
    }
}
