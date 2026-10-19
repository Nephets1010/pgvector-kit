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
 * {@code vector} 的文本表示无法解析，或分量不是有限浮点数。
 *
 * <p>继承 {@link IllegalArgumentException} 而不是自造 checked exception：这类错误是调用方传错了数据，
 * 让它往上抛、由事务边界统一回滚，比强迫每一层都写 try-catch 更接近真实用法。
 */
public class VectorFormatException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public VectorFormatException(String message) {
        super(message);
    }

    public VectorFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
