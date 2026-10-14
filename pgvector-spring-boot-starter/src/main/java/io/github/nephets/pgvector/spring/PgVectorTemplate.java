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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * 执行 {@link HybridQuery} 的入口。
 *
 * <p>它只做三件事，其余一概不管——这是刻意的：
 *
 * <ol>
 *   <li>把 DSL 生成的 SQL 与绑定参数原样交给 JDBC（不做任何改写，所以你看到的 SQL 就是执行的 SQL）；
 *   <li>带向量近邻的查询先确认后端是 PostgreSQL，不是就直接抛 {@link UnsupportedDatabaseException}；
 *   <li>把结果交给调用方给的 {@link RowMapper}。
 * </ol>
 *
 * <p>它<strong>不是 ORM</strong>：不做实体映射、不做缓存、不管理关联。 业务行的写入照旧用你的 MyBatis / JPA /
 * JdbcTemplate，本组件只负责那条混合查询。
 */
public class PgVectorTemplate {

    private static final Logger log = LoggerFactory.getLogger(PgVectorTemplate.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final PgVectorProperties properties;

    /** 惰性探测结果：null 表示还没探测过。 */
    private volatile Boolean postgresql;

    public PgVectorTemplate(DataSource dataSource, PgVectorProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 底层 {@link JdbcTemplate}，用于写入业务表 / 向量表这类普通 SQL。 */
    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public PgVectorProperties properties() {
        return properties;
    }

    /**
     * 执行一条混合查询。
     *
     * @param query 由 {@link HybridQuery.Builder} 生成的查询
     * @param rowMapper 行映射器；距离列的列名见 {@link HybridQuery#distanceAlias()}
     * @return 结果，顺序即数据库返回的顺序（距离升序 = 最相似在前）
     * @throws UnsupportedDatabaseException 带向量近邻但后端不是 PostgreSQL
     */
    public <T> List<T> query(HybridQuery query, RowMapper<T> rowMapper) {
        Objects.requireNonNull(query, "HybridQuery 不能为 null");
        Objects.requireNonNull(rowMapper, "RowMapper 不能为 null");
        log.info("executing hybrid query usesVector={}", query.usesVector());
        log.debug("hybrid query sql={} boundParams={}", query.sql(), query.params().size());
        if (query.usesVector()) {
            requirePostgreSQL();
        }
        return jdbcTemplate.query(query.sql(), rowMapper, query.params().toArray());
    }

    /**
     * 查询条数（用于分页的 {@code total}）。
     *
     * <p>刻意不复用带向量的那条 SQL：算总数不需要排序，带上距离算子只会让它变慢。
     */
    public long count(HybridQuery query) {
        Objects.requireNonNull(query, "HybridQuery 不能为 null");
        log.info("count query usesVector={}", query.usesVector());
        log.debug("count query sql={} boundParams={}", query.sql(), query.params().size());
        String sql = "SELECT count(*) FROM (" + query.sql() + ") AS pgvector_kit_counted";
        Long total = jdbcTemplate.queryForObject(sql, Long.class, query.params().toArray());
        if (total == null) {
            // count(*) 理论上永不返回 null；真出现了说明驱动/方言有问题，降级为 0 但必须留痕
            log.warn("count 查询返回 null，降级为 0（请检查驱动对 count(*) 的返回处理）");
            return 0L;
        }
        return total;
    }

    /** 数据库是不是 PostgreSQL（惰性探测并缓存）。 */
    public boolean isPostgreSQL() {
        Boolean cached = postgresql;
        if (cached == null) {
            cached = "PostgreSQL".equalsIgnoreCase(detectDatabaseProductName());
            postgresql = cached;
            log.debug("detected database productName -> isPostgreSQL={}", cached);
        }
        return cached;
    }

    private void requirePostgreSQL() {
        if (!isPostgreSQL()) {
            String product = detectDatabaseProductName();
            log.warn(
                    "拒绝执行带向量近邻的查询：后端不是 PostgreSQL（实际 productName={}），"
                            + "向量算子与 ::vector 语法只有 PostgreSQL + pgvector 支持",
                    product);
            throw new UnsupportedDatabaseException(product);
        }
    }

    private String detectDatabaseProductName() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            log.error(
                    "failed to detect database product name, vector capability cannot be determined",
                    e);
            throw new IllegalStateException("无法获取数据库产品名，vector 能力无法判定", e);
        }
    }
}
