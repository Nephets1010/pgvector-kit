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

import io.github.nephets.pgvector.core.HybridQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入口参数 fail-fast：null 查询 / null 行映射器必须立刻报错，而不是带进 JDBC 层变成一个难懂的 NPE。
 *
 * <p>H2 内存库只用于满足 {@link PgVectorTemplate} 构造器对 DataSource 的要求 —— 校验发生在任何数据库访问之前。
 */
@DisplayName("PgVectorTemplate 入口参数校验")
class PgVectorTemplateNullArgTest {

    private PgVectorTemplate template;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                DataSourceBuilder.create()
                        .driverClassName("org.h2.Driver")
                        .url("jdbc:h2:mem:pgvector-nullarg-" + System.nanoTime())
                        .username("sa")
                        .password("")
                        .build();
        template = new PgVectorTemplate(dataSource, new PgVectorProperties());
    }

    @Test
    @DisplayName("query(null, mapper) 抛带消息的 NPE")
    void rejectsNullQuery() {
        assertThatThrownBy(() -> template.query(null, (rs, row) -> rs.getInt("id")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("HybridQuery");
    }

    @Test
    @DisplayName("query(query, null) 抛带消息的 NPE")
    void rejectsNullRowMapper() {
        HybridQuery query = HybridQuery.builder().from("sku").build();
        assertThatThrownBy(() -> template.query(query, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("RowMapper");
    }

    @Test
    @DisplayName("count(null) 抛带消息的 NPE")
    void rejectsNullCountQuery() {
        assertThatThrownBy(() -> template.count(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("HybridQuery");
    }
}
