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

/**
 * 当前数据库撑不起 {@code vector} 能力。
 *
 * <p>这个异常是刻意存在的：H2 / MySQL / Oracle 都没有 {@code vector} 类型， 如果这里不拦，你会得到一个「SQL 语法错误」或者更糟——一个看起来跑通了、
 * 实际把向量当成字符串比较的查询。与其让人猜，不如直说。
 */
public class UnsupportedDatabaseException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient String databaseProductName;

    public UnsupportedDatabaseException(String databaseProductName) {
        super(
                "pgvector-kit 的向量能力需要 PostgreSQL + pgvector 扩展，当前连接的数据库是 "
                        + databaseProductName
                        + "。它没有 vector 类型，本组件也不会假装支持："
                        + "① 标量过滤部分的 HybridQuery 仍然可用（那是普通 SQL）；"
                        + "② 任何带 nearest(...) 的查询都会在这里失败，而不是返回一组错误的结果；"
                        + "③ 想在本机跑真实用例，请用仓库里的 docker-compose（postgres + pgvector 官方镜像）或 Testcontainers。");
        this.databaseProductName = databaseProductName;
    }

    /** 数据库自报的产品名。 */
    public String databaseProductName() {
        return databaseProductName;
    }
}
