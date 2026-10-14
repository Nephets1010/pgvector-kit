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

/** 拼进 SQL 的标识符或子句没有通过白名单校验。 */
public class InvalidSqlIdentifierException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidSqlIdentifierException(String role, String value, String rule) {
        super("非法的 " + role + "：\"" + value + "\" —— " + rule);
    }
}
