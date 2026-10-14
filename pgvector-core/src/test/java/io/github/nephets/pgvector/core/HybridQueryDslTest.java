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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("混合查询 DSL：一条 SQL")
class HybridQueryDslTest {

    private static final float[] QUERY = {0.1f, 0.2f, 0.3f};
    private static final String QUERY_LITERAL = "[0.1,0.2,0.3]";

    private static HybridQuery.Builder base() {
        return HybridQuery.builder().from("sku_embedding c");
    }

    @Nested
    @DisplayName("三种距离算子生成的 SQL")
    class Operators {

        @Test
        @DisplayName("余弦 <=> 与 vector_cosine_ops")
        void cosine() {
            HybridQuery q =
                    base().nearest("c.embedding", QUERY, DistanceOperator.COSINE).limit(5).build();
            assertThat(q.sql())
                    .isEqualTo(
                            "SELECT *, (c.embedding <=> ?::vector) AS distance\n"
                                    + "FROM sku_embedding c\n"
                                    + "ORDER BY c.embedding <=> ?::vector ASC\n"
                                    + "LIMIT 5");
            assertThat(q.params()).containsExactly(QUERY_LITERAL, QUERY_LITERAL);
            assertThat(q.operator()).isEqualTo(DistanceOperator.COSINE);
            assertThat(q.operator().operatorClass()).isEqualTo("vector_cosine_ops");
        }

        @Test
        @DisplayName("L2 <-> 与 vector_l2_ops")
        void l2() {
            HybridQuery q = base().nearest("c.embedding", QUERY, DistanceOperator.L2).build();
            assertThat(q.sql()).contains("c.embedding <-> ?::vector");
            assertThat(q.operator().operatorClass()).isEqualTo("vector_l2_ops");
        }

        @Test
        @DisplayName("内积 <#> 与 vector_ip_ops；pgvector 返回负内积，所以排序仍然是 ASC")
        void innerProduct() {
            HybridQuery q =
                    base().nearest("c.embedding", QUERY, DistanceOperator.INNER_PRODUCT).build();
            assertThat(q.sql())
                    .contains("(c.embedding <#> ?::vector) AS distance")
                    .contains("ORDER BY c.embedding <#> ?::vector ASC");
            assertThat(q.operator().operatorClass()).isEqualTo("vector_ip_ops");
        }

        @Test
        @DisplayName("三个算子的「最相似优先」方向都是 ASC —— 内积因为返回的是负值，直觉会写错")
        void allOperatorsSortAscending() {
            for (DistanceOperator operator : DistanceOperator.values()) {
                assertThat(operator.mostSimilarFirst()).isEqualTo(SortDirection.ASC);
            }
        }
    }

    @Nested
    @DisplayName("完整形态：标量过滤 + JOIN + 近邻 + 分页")
    class FullShape {

        @Test
        @DisplayName("生成的就是一条 SQL，且分页与排序都在数据库里完成")
        void generatesOneSql() {
            HybridQuery q =
                    HybridQuery.builder()
                            .select("c.id", "s.sku_code AS code", "s.stock")
                            .from("sku_embedding c")
                            .join(JoinType.INNER, "sku s", "s.id = c.sku_id")
                            .where("s.warehouse = ?", "SH01")
                            .where("s.stock > 0")
                            .nearest("c.embedding", QUERY, DistanceOperator.COSINE)
                            .limit(20)
                            .offset(40)
                            .build();

            assertThat(q.sql())
                    .isEqualTo(
                            "SELECT c.id, s.sku_code AS code, s.stock, (c.embedding <=> ?::vector) AS distance\n"
                                    + "FROM sku_embedding c\n"
                                    + "INNER JOIN sku s ON s.id = c.sku_id\n"
                                    + "WHERE s.warehouse = ? AND s.stock > 0\n"
                                    + "ORDER BY c.embedding <=> ?::vector ASC\n"
                                    + "LIMIT 20\n"
                                    + "OFFSET 40");
            assertThat(q.params()).containsExactly(QUERY_LITERAL, "SH01", QUERY_LITERAL);
        }

        @Test
        @DisplayName("多个 JOIN 按顺序追加")
        void supportsMultipleJoins() {
            HybridQuery q =
                    HybridQuery.builder()
                            .from("sku_embedding c")
                            .join("sku s", "s.id = c.sku_id")
                            .join(JoinType.LEFT, "warehouse w", "w.code = s.warehouse")
                            .where("w.city = ?", "上海")
                            .build();
            assertThat(q.sql())
                    .contains(
                            "INNER JOIN sku s ON s.id = c.sku_id\nLEFT JOIN warehouse w ON w.code = s.warehouse");
            assertThat(q.params()).containsExactly("上海");
        }

        @Test
        @DisplayName("只有标量条件、没有向量时不产生距离列，也不产生 ORDER BY")
        void scalarOnly() {
            HybridQuery q =
                    HybridQuery.builder().from("sku s").where("s.stock > ?", 10).limit(3).build();
            assertThat(q.sql()).isEqualTo("SELECT *\nFROM sku s\nWHERE s.stock > ?\nLIMIT 3");
            assertThat(q.usesVector()).isFalse();
            assertThat(q.operator()).isNull();
        }

        @Test
        @DisplayName("标量排序列排在距离列之后")
        void scalarOrderByComesAfterDistance() {
            HybridQuery q =
                    HybridQuery.builder()
                            .from("sku_embedding c")
                            .nearest("c.embedding", QUERY, DistanceOperator.L2)
                            .orderBy("c.updated_at", SortDirection.DESC)
                            .build();
            assertThat(q.sql())
                    .contains("ORDER BY c.embedding <-> ?::vector ASC, c.updated_at DESC");
        }

        @Test
        @DisplayName("距离列别名可改")
        void distanceAliasIsConfigurable() {
            HybridQuery q =
                    HybridQuery.builder()
                            .from("sku_embedding c")
                            .nearest("c.embedding", QUERY, DistanceOperator.COSINE)
                            .distanceAlias("score")
                            .build();
            assertThat(q.sql()).contains("AS score");
            assertThat(q.distanceAlias()).isEqualTo("score");
        }

        @Test
        @DisplayName("只有 OFFSET 没有 LIMIT 也合法（PostgreSQL 允许）")
        void offsetWithoutLimit() {
            HybridQuery q = HybridQuery.builder().from("sku s").offset(10).build();
            assertThat(q.sql()).isEqualTo("SELECT *\nFROM sku s\nOFFSET 10");
        }

        @Test
        @DisplayName("查询向量以副本形式持有，外部改数组不会污染已生成的查询")
        void defendsAgainstExternalMutation() {
            float[] mutable = {1f, 2f, 3f};
            HybridQuery q =
                    HybridQuery.builder()
                            .from("c")
                            .nearest("c.embedding", mutable, DistanceOperator.L2)
                            .build();
            mutable[0] = 999f;
            assertThat(q.queryVector()).containsExactly(1f, 2f, 3f);
            assertThat(q.params()).containsExactly("[1.0,2.0,3.0]", "[1.0,2.0,3.0]");
        }
    }

    @Nested
    @DisplayName("必填项与参数个数校验")
    class Validation {

        @Test
        @DisplayName("缺 FROM 直接报错")
        void requiresFrom() {
            assertThatThrownBy(() -> HybridQuery.builder().where("a = ?", 1).build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FROM");
        }

        @Test
        @DisplayName("WHERE 里 ? 的个数与实际参数个数不符时报错，而不是留给 JDBC 报下标越界")
        void requiresMatchingPlaceholderCount() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("sku s")
                                            .where("s.a = ? AND s.b = ?", 1))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("2 个 ? 占位符")
                    .hasMessageContaining("1 个绑定参数");
        }

        @Test
        @DisplayName("LIMIT 必须为正")
        void rejectsNonPositiveLimit() {
            assertThatThrownBy(() -> HybridQuery.builder().from("sku s").limit(0))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("LIMIT");
        }

        @Test
        @DisplayName("OFFSET 不能为负")
        void rejectsNegativeOffset() {
            assertThatThrownBy(() -> HybridQuery.builder().from("sku s").offset(-1))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("OFFSET");
        }

        @Test
        @DisplayName("nearest 的算子不能为 null")
        void requiresOperator() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("c")
                                            .nearest("c.embedding", QUERY, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("只声明 expectDimensions 却不 nearest，等于什么都没校验，直接报错")
        void rejectsDimensionCheckWithoutVector() {
            assertThatThrownBy(
                            () -> HybridQuery.builder().from("sku s").expectDimensions(3).build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nearest");
        }
    }

    @Nested
    @DisplayName("SQL 注入面：拼进 SQL 的标识符必须过白名单")
    class InjectionGuard {

        @Test
        @DisplayName("表名里带分号会被拒绝")
        void rejectsSemicolonInTable() {
            assertThatThrownBy(() -> HybridQuery.builder().from("sku; DROP TABLE sku"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("SELECT 列表里带注释符会被拒绝")
        void rejectsCommentInSelect() {
            assertThatThrownBy(() -> HybridQuery.builder().from("sku s").select("s.id -- x"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("WHERE 里出现单引号会被拒绝：字面量必须走 ? 绑定")
        void rejectsQuoteInWhere() {
            assertThatThrownBy(() -> HybridQuery.builder().from("sku s").where("s.code = 'ABC'"))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("引号");
        }

        @Test
        @DisplayName("WHERE 里的 UNION 注入（带引号）被拒绝")
        void rejectsUnionInjection() {
            assertThatThrownBy(
                            () ->
                                    HybridQuery.builder()
                                            .from("sku s")
                                            .where("s.code = '1' UNION SELECT * FROM users"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("JOIN 的 ON 条件不允许 ? —— 否则参数顺序无法推断")
        void rejectsPlaceholderInJoinOn() {
            assertThatThrownBy(() -> HybridQuery.builder().from("c").join("sku s", "s.id = ?"))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不支持 ? 绑定参数");
        }

        @Test
        @DisplayName("合法的 schema 限定名与别名仍然放行")
        void allowsQualifiedNames() {
            HybridQuery q =
                    HybridQuery.builder()
                            .from("public.sku_embedding AS c")
                            .select("c.*")
                            .join("public.sku AS s", "s.id = c.sku_id")
                            .build();
            assertThat(q.sql())
                    .contains("FROM public.sku_embedding AS c")
                    .contains("INNER JOIN public.sku AS s");
        }
    }
}
