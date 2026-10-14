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
import io.github.nephets.pgvector.core.SortDirection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用 H2 验证模板的两件事：
 *
 * <ol>
 *   <li>标量部分真的能跑通、排序与分页结果是对的（这是「分开查再内存拼」最容易错的地方）；
 *   <li>带向量的查询在 H2 上必须明确失败，而不是假装通过 —— 这是本仓对 H2 能力边界的承诺。
 * </ol>
 */
@DisplayName("PgVectorTemplate：SQL 透传与 H2 边界")
class PgVectorTemplateTest {

    private DataSource dataSource;
    private PgVectorTemplate template;

    @BeforeEach
    void setUp() {
        dataSource =
                DataSourceBuilder.create()
                        .driverClassName("org.h2.Driver")
                        .url(
                                "jdbc:h2:mem:pgvector-template-"
                                        + System.nanoTime()
                                        + ";DB_CLOSE_DELAY=-1")
                        .username("sa")
                        .password("")
                        .build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sku (id INT, warehouse VARCHAR(10), stock INT)");
        // 两个仓库各 3 条，库存故意乱序，用来验证排序确实发生在数据库里
        jdbc.update("INSERT INTO sku VALUES (1, 'SH01', 30)");
        jdbc.update("INSERT INTO sku VALUES (2, 'SH01', 10)");
        jdbc.update("INSERT INTO sku VALUES (3, 'SH01', 20)");
        jdbc.update("INSERT INTO sku VALUES (4, 'BJ01', 99)");
        jdbc.update("INSERT INTO sku VALUES (5, 'BJ01', 5)");
        jdbc.update("INSERT INTO sku VALUES (6, 'BJ01', 50)");
        template = new PgVectorTemplate(dataSource, new PgVectorProperties());
    }

    @Test
    @DisplayName("标量过滤 + 排序：结果顺序由数据库给出")
    void scalarFilterAndOrder() {
        HybridQuery query =
                HybridQuery.builder()
                        .select("id", "warehouse", "stock")
                        .from("sku")
                        .where("warehouse = ?", "SH01")
                        .orderBy("stock", SortDirection.DESC)
                        .build();
        List<Integer> ids = template.query(query, (rs, row) -> rs.getInt("id"));
        assertThat(ids).containsExactly(1, 3, 2);
    }

    @Test
    @DisplayName("分页：LIMIT 2 OFFSET 0 与 OFFSET 2 拼起来正好是全量，不会出现重复或漏行")
    void paginationIsConsistent() {
        HybridQuery page1 =
                HybridQuery.builder()
                        .from("sku")
                        .where("warehouse = ?", "BJ01")
                        .orderBy("stock", SortDirection.DESC)
                        .limit(2)
                        .offset(0)
                        .build();
        HybridQuery page2 =
                HybridQuery.builder()
                        .from("sku")
                        .where("warehouse = ?", "BJ01")
                        .orderBy("stock", SortDirection.DESC)
                        .limit(2)
                        .offset(2)
                        .build();
        List<Integer> first = template.query(page1, (rs, row) -> rs.getInt("id"));
        List<Integer> second = template.query(page2, (rs, row) -> rs.getInt("id"));

        assertThat(first).containsExactly(4, 6);
        assertThat(second).containsExactly(5);
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    @DisplayName("count 用子查询包住原 SQL，条数与实际一致")
    void countMatchesRows() {
        HybridQuery query =
                HybridQuery.builder().from("sku").where("warehouse = ?", "SH01").build();
        assertThat(template.count(query)).isEqualTo(3L);
    }

    @Test
    @DisplayName("H2 上带向量的查询必须明确失败，并说明自己就是 H2")
    void vectorQueryFailsLoudlyOnH2() {
        HybridQuery query =
                HybridQuery.builder()
                        .from("sku")
                        .nearest("embedding", new float[] {1f, 2f, 3f}, DistanceOperator.COSINE)
                        .build();
        assertThatThrownBy(() -> template.query(query, (rs, row) -> rs.getInt("id")))
                .isInstanceOf(UnsupportedDatabaseException.class)
                .hasMessageContaining("H2")
                .hasMessageContaining("PostgreSQL");
    }

    @Test
    @DisplayName("异常里能取回数据库产品名，且 isPostgreSQL 判定为 false")
    void exposesProductName() {
        UnsupportedDatabaseException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        UnsupportedDatabaseException.class,
                        () ->
                                template.query(
                                        HybridQuery.builder()
                                                .from("sku")
                                                .nearest(
                                                        "embedding",
                                                        new float[] {1f},
                                                        DistanceOperator.L2)
                                                .build(),
                                        (rs, row) -> rs.getInt("id")));
        assertThat(thrown.databaseProductName()).isEqualTo("H2");
        assertThat(template.isPostgreSQL()).isFalse();
    }

    @Test
    @DisplayName("标量查询（不带向量）在 H2 上照常可用 —— 边界是清楚的，不是一刀切禁用")
    void scalarQueryStillWorksOnH2() {
        HybridQuery query = HybridQuery.builder().from("sku").where("stock > ?", 25).build();
        assertThat(template.query(query, (rs, row) -> rs.getInt("id"))).containsExactly(1, 4, 6);
    }

    @Test
    @DisplayName("底层 JdbcTemplate 暴露出来，方便写业务表（同一个 DataSource、同一个事务）")
    void exposesJdbcTemplate() {
        assertThat(template.jdbcTemplate().getDataSource()).isSameAs(dataSource);
        assertThat(template.properties().getDefaultOperator()).isEqualTo(DistanceOperator.COSINE);
    }
}
