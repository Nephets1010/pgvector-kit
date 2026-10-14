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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 混合查询：标量过滤 + 向量近邻 + 业务表 JOIN，最后生成<strong>一条</strong> SQL。
 *
 * <p>为什么必须是「一条」：分开查再在内存里拼，会在三个地方出错，而且都很隐蔽——
 *
 * <ol>
 *   <li><strong>分页错</strong>：先 {@code LIMIT 20} 拿到 20 条向量命中，再按业务条件过滤，可能只剩 3 条， 而第 21~40 条里还有 17
 *       条是符合的。用户看到的是「每页条数忽多忽少」。
 *   <li><strong>排序错</strong>：两段结果各自的排序规则不同，内存里再排一次必然丢掉数据库那边的稳定顺序。
 *   <li><strong>事务错</strong>：两次查询之间数据变了，业务行的状态和向量行的相似度来自两个时间点。
 * </ol>
 *
 * <p>用法：
 *
 * <pre>{@code
 * HybridQuery q = HybridQuery.builder()
 *     .select("c.id", "s.sku_code", "s.stock")
 *     .from("sku_embedding c")
 *     .join(JoinType.INNER, "sku s", "s.id = c.sku_id")
 *     .where("s.warehouse = ?", "SH01")
 *     .where("s.stock > 0")
 *     .nearest("c.embedding", queryVector, DistanceOperator.COSINE)
 *     .withIndex(INDEX)
 *     .limit(20)
 *     .build();
 * }</pre>
 */
public final class HybridQuery {

    private static final Logger log = LoggerFactory.getLogger(HybridQuery.class);

    /** 一次 JOIN。{@code on} 里不允许出现 {@code ?}，避免出现无法推断的参数顺序。 */
    public record Join(JoinType type, String tableRef, String on) {
        public Join {
            type = Objects.requireNonNull(type, "JOIN 类型不能为 null");
            tableRef = SqlIdentifier.requireTableRef(tableRef, "JOIN 的表引用");
            on = SqlIdentifier.requireClause(on, "JOIN 的 ON 条件", false);
        }

        /** 渲染成 {@code INNER JOIN sku s ON s.id = c.sku_id}。 */
        public String toSql() {
            return type.keyword() + " JOIN " + tableRef + " ON " + on;
        }
    }

    private final String sql;
    private final List<Object> params;
    private final DistanceOperator operator;
    private final float[] queryVector;
    private final String distanceAlias;

    private HybridQuery(
            String sql,
            List<Object> params,
            DistanceOperator operator,
            float[] queryVector,
            String distanceAlias) {
        this.sql = sql;
        this.params = Collections.unmodifiableList(params);
        this.operator = operator;
        this.queryVector = queryVector;
        this.distanceAlias = distanceAlias;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 生成的 SQL。只有 {@code ?} 占位符，没有拼接进来的值。 */
    public String sql() {
        return sql;
    }

    /**
     * 与 {@link #sql()} 里的 {@code ?} 一一对应的绑定参数，顺序即绑定顺序。
     *
     * <p>向量参数以 pgvector 文本表示（{@code String}）出现，可以直接 {@code PreparedStatement#setString}。 因为 DSL
     * 在算子后面补了 {@code ::vector}，任何 JDBC 驱动都能正确绑定，不需要 PGobject。
     */
    public List<Object> params() {
        return params;
    }

    /** 这次查询用的距离算子；没有向量近邻时为 null。 */
    public DistanceOperator operator() {
        return operator;
    }

    /** 查询向量的副本；没有向量近邻时为 null。 */
    public float[] queryVector() {
        return queryVector == null ? null : queryVector.clone();
    }

    /** 距离列的别名，默认 {@code distance}。 */
    public String distanceAlias() {
        return distanceAlias;
    }

    /** 是否包含向量近邻。 */
    public boolean usesVector() {
        return operator != null;
    }

    @Override
    public String toString() {
        return sql;
    }

    /** {@link HybridQuery} 的构造器。所有校验都推迟到 {@link #build()}，以便一次报出完整的状态。 */
    public static final class Builder {

        private final List<String> selectItems = new ArrayList<>();
        private final List<Join> joins = new ArrayList<>();
        private final List<String> whereClauses = new ArrayList<>();
        private final List<Object> whereParams = new ArrayList<>();
        private final List<String> orderByItems = new ArrayList<>();

        private String from;
        private String vectorColumn;
        private float[] queryVector;
        private DistanceOperator operator;
        private String distanceAlias = "distance";
        private int expectedDimensions = -1;
        private VectorIndex index;
        private int limit = -1;
        private int offset = -1;

        private Builder() {}

        /**
         * 选择列。可以写 {@code c.id}、{@code s.sku_code AS code}、{@code *}、{@code count(*)}。 不写时默认 {@code
         * *}。
         */
        public Builder select(String... items) {
            Objects.requireNonNull(items, "select 列表不能为 null");
            for (String item : items) {
                selectItems.add(SqlIdentifier.requireSelectItem(item, "SELECT 列表项"));
            }
            return this;
        }

        /** {@code FROM} 的表引用，可以带别名：{@code sku_embedding c}。 */
        public Builder from(String tableRef) {
            this.from = SqlIdentifier.requireTableRef(tableRef, "FROM 的表引用");
            return this;
        }

        /** 补一个 JOIN。多次调用按顺序追加。 */
        public Builder join(JoinType type, String tableRef, String on) {
            joins.add(new Join(type, tableRef, on));
            return this;
        }

        /** 补一个 INNER JOIN。 */
        public Builder join(String tableRef, String on) {
            return join(JoinType.INNER, tableRef, on);
        }

        /**
         * 补一个标量过滤条件。多次调用之间用 {@code AND} 连接。
         *
         * <p>字面量必须用 {@code ?} 绑定：这里刻意禁止在谓词里出现引号。 {@code ?} 的个数必须与 {@code params} 一致，不一致立即报错——
         * 少一个参数在 JDBC 那边只是「绑定下标越界」，多一个则是静默忽略，两者都很难在日志里看出来。
         */
        public Builder where(String predicate, Object... params) {
            String clause = SqlIdentifier.requireClause(predicate, "WHERE 条件", true);
            Object[] bound = params == null ? new Object[0] : params;
            int placeholders = SqlIdentifier.countPlaceholders(clause);
            if (placeholders != bound.length) {
                // 少一个参数在 JDBC 那边只是「绑定下标越界」，多一个则是静默忽略 —— 两者都难在日志里看出来，所以这里必须留痕
                log.warn(
                        "WHERE 条件与绑定参数数量不匹配：占位符 {} 个，参数 {} 个，clause=[{}]",
                        placeholders,
                        bound.length,
                        clause);
                throw new InvalidSqlIdentifierException(
                        "WHERE 条件",
                        clause,
                        "里面有 " + placeholders + " 个 ? 占位符，但传进来 " + bound.length + " 个绑定参数");
            }
            whereClauses.add(clause);
            whereParams.addAll(List.of(bound));
            return this;
        }

        /**
         * 向量近邻：按给定算子计算距离、把距离作为一列选出、并按「最相似优先」排序。
         *
         * <p>排序方向由算子自己决定（见 {@link DistanceOperator#mostSimilarFirst()}）， 三个算子都是 {@code
         * ASC}——包括内积，因为 pgvector 的 {@code <#>} 返回的是负内积。
         */
        public Builder nearest(String column, float[] vector, DistanceOperator operator) {
            this.vectorColumn = SqlIdentifier.requireIdentifier(column, "向量列名");
            Objects.requireNonNull(vector, "查询向量不能为 null");
            Objects.requireNonNull(operator, "距离算子不能为 null");
            this.queryVector = vector.clone();
            this.operator = operator;
            return this;
        }

        /** 距离列的别名，默认 {@code distance}。 */
        public Builder distanceAlias(String alias) {
            this.distanceAlias = SqlIdentifier.requireIdentifier(alias, "距离列别名");
            return this;
        }

        /** 声明查询向量应当是多少维。建表时写死的那个 N 在这里再写一次， 让维度错误发生在 Java 侧而不是 SQL 执行阶段。 */
        public Builder expectDimensions(int dimensions) {
            if (dimensions <= 0) {
                throw new VectorFormatException("期望维度必须为正数，实际为 " + dimensions);
            }
            this.expectedDimensions = dimensions;
            return this;
        }

        /**
         * 声明这次查询应当命中的索引。
         *
         * <p>给了索引，{@link #build()} 就会校验算子与 opclass 是否配套、查询向量维度是否与索引一致； 不配套直接抛 {@link
         * OperatorIndexMismatchException}，绝不静默退化成全表扫描。
         */
        public Builder withIndex(VectorIndex index) {
            this.index = index;
            if (index != null && this.expectedDimensions < 0) {
                this.expectedDimensions = index.dimensions();
            }
            return this;
        }

        /** 追加一个标量排序列。向量近邻的距离列永远排在它前面。 */
        public Builder orderBy(String column, SortDirection direction) {
            String col = SqlIdentifier.requireIdentifier(column, "排序列名");
            Objects.requireNonNull(direction, "排序方向不能为 null");
            orderByItems.add(col + " " + direction.keyword());
            return this;
        }

        /** {@code LIMIT}，不设置就不加 LIMIT 子句。 */
        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new VectorFormatException("LIMIT 必须为正数，实际为 " + limit);
            }
            this.limit = limit;
            return this;
        }

        /** {@code OFFSET}，必须 ≥ 0。 */
        public Builder offset(int offset) {
            if (offset < 0) {
                throw new VectorFormatException("OFFSET 不能为负数，实际为 " + offset);
            }
            this.offset = offset;
            return this;
        }

        /** 校验并生成 {@link HybridQuery}。 */
        public HybridQuery build() {
            if (from == null) {
                log.warn("拒绝生成混合查询：FROM 是必填的（没有表就没有查询）");
                throw new IllegalStateException("FROM 是必填的：没有表就没有查询");
            }
            if (selectItems.isEmpty()) {
                selectItems.add("*");
            }

            String vectorLiteral = null;
            if (operator != null) {
                // toLiteral 顺带完成「非空 / 有限 / 维度上限」三项校验
                vectorLiteral = VectorCodec.toLiteral(queryVector);
                if (expectedDimensions > 0) {
                    VectorCodec.requireDimensions(queryVector, expectedDimensions, vectorColumn);
                }
                if (index != null) {
                    index.requireDimensions(queryVector);
                    index.requireOperator(operator);
                }
            } else if (expectedDimensions > 0) {
                log.warn(
                        "拒绝生成混合查询：声明了 expectDimensions({}) 却没有调用 nearest(...)", expectedDimensions);
                throw new IllegalStateException(
                        "声明了 expectDimensions("
                                + expectedDimensions
                                + ") 却没有调用 nearest(...)，无法校验任何东西");
            }

            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder();

            sql.append("SELECT ").append(String.join(", ", selectItems));
            if (operator != null) {
                sql.append(", (")
                        .append(vectorColumn)
                        .append(' ')
                        .append(operator.symbol())
                        .append(" ?::vector) AS ")
                        .append(distanceAlias);
                params.add(vectorLiteral);
            }

            sql.append("\nFROM ").append(from);

            for (Join join : joins) {
                sql.append('\n').append(join.toSql());
            }

            if (!whereClauses.isEmpty()) {
                sql.append("\nWHERE ").append(String.join(" AND ", whereClauses));
                params.addAll(whereParams);
            }

            List<String> ordering = new ArrayList<>();
            if (operator != null) {
                // 刻意把完整表达式（而不是 distance 别名）写进 ORDER BY：
                // PostgreSQL 只有在 ORDER BY 里出现索引建的那一列 + 对应算子时才会走 ANN 索引。
                ordering.add(
                        vectorColumn
                                + ' '
                                + operator.symbol()
                                + " ?::vector "
                                + operator.mostSimilarFirst().keyword());
                params.add(vectorLiteral);
            }
            ordering.addAll(orderByItems);
            if (!ordering.isEmpty()) {
                sql.append("\nORDER BY ").append(String.join(", ", ordering));
            }

            if (limit > 0) {
                sql.append("\nLIMIT ").append(limit);
            }
            if (offset >= 0) {
                sql.append("\nOFFSET ").append(offset);
            }

            HybridQuery built =
                    new HybridQuery(sql.toString(), params, operator, queryVector, distanceAlias);
            log.debug(
                    "hybrid query built usesVector={} boundParams={} limit={} offset={}",
                    built.usesVector(),
                    built.params().size(),
                    limit,
                    offset);
            return built;
        }
    }
}
