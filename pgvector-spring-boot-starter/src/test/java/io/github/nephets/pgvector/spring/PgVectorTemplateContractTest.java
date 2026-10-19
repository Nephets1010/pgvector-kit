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
import io.github.nephets.pgvector.core.HybridQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模板的入口契约：构造期 null 拒绝、count 与 query 的能力边界一致。
 *
 * <p>H2 内存库只用于满足构造器对 DataSource 的要求——这些断言都发生在接触数据库之前， 或只依赖 H2 的产品名。
 */
@DisplayName("PgVectorTemplate：构造期与 count 契约")
class PgVectorTemplateContractTest {

    private DataSource dataSource;
    private PgVectorTemplate template;

    @BeforeEach
    void setUp() {
        dataSource =
                DataSourceBuilder.create()
                        .driverClassName("org.h2.Driver")
                        .url("jdbc:h2:mem:pgvector-contract-" + System.nanoTime())
                        .username("sa")
                        .password("")
                        .build();
        template = new PgVectorTemplate(dataSource, new PgVectorProperties());
    }

    @Test
    @DisplayName("null DataSource：构造期 NPE，消息直接说明缺什么")
    void rejectsNullDataSource() {
        assertThatThrownBy(() -> new PgVectorTemplate(null, new PgVectorProperties()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DataSource 不能为 null");
    }

    @Test
    @DisplayName("null Properties：构造期 NPE")
    void rejectsNullProperties() {
        assertThatThrownBy(() -> new PgVectorTemplate(dataSource, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PgVectorProperties 不能为 null");
    }

    @Test
    @DisplayName("构造合法时原样持有：properties() 返回的就是传进去的那个实例")
    void holdsWhatItWasGiven() {
        PgVectorProperties properties = new PgVectorProperties();
        PgVectorTemplate created = new PgVectorTemplate(dataSource, properties);
        assertThat(created.properties()).isSameAs(properties);
        assertThat(created.jdbcTemplate().getDataSource()).isSameAs(dataSource);
    }

    @Test
    @DisplayName("count 带向量查询在 H2 上必须明确失败：与 query 的能力边界一致，不给一句看不懂的语法错误")
    void countWithVectorFailsLoudlyOnH2() {
        HybridQuery query =
                HybridQuery.builder()
                        .from("sku")
                        .nearest("embedding", new float[] {1f, 2f, 3f}, DistanceOperator.COSINE)
                        .build();
        assertThatThrownBy(() -> template.count(query))
                .isInstanceOf(UnsupportedDatabaseException.class)
                .hasMessageContaining("H2")
                .hasMessageContaining("PostgreSQL");
    }

    @Test
    @DisplayName("count 纯标量查询在 H2 上照常工作：边界只画在向量算子上")
    void countScalarStillWorksOnH2() {
        HybridQuery query = HybridQuery.builder().from("sku").build();
        // 表不存在会抛 SQL 异常，但那是 H2 的语法层错误，不是 UnsupportedDatabaseException
        assertThatThrownBy(() -> template.count(query))
                .isNotInstanceOf(UnsupportedDatabaseException.class);
    }

    @Test
    @DisplayName("isPostgreSQL 重复调用结果稳定（惰性探测缓存不漂移）")
    void postgresqlDetectionIsStable() {
        boolean first = template.isPostgreSQL();
        assertThat(template.isPostgreSQL()).isEqualTo(first);
        assertThat(first).isFalse();
    }
}
