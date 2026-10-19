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

/**
 * {@link HybridQuery.Builder} 的分支覆盖：{@code HybridQueryDslTest} 断言生成的 SQL 形状， 这个文件把构造器的每条校验分支与边界值走到。
 *
 * <p>原则：断言<strong>行为与消息</strong>，不断言「没抛异常」——没抛异常可能是代码根本没走到。
 */
@DisplayName("混合查询构造器：分支与边界")
class HybridQueryBuilderTest {

    private static final float[] QUERY = {0.1f, 0.2f, 0.3f};
    private static final String LITERAL = "[0.1,0.2,0.3]";

    private static final VectorIndex COSINE_INDEX =
            new VectorIndex(
                    "idx_sku_embedding",
                    "sku_embedding",
                    "embedding",
                    IndexType.HNSW,
                    DistanceOperator.COSINE,
                    3);

    private static HybridQuery.Builder base() {
        return HybridQuery.builder().from("sku_embedding c");
    }

    @Nested
    @DisplayName("select")
    class Select {

        @Test
        @DisplayName("多次调用按顺序累积")
        void accumulatesAcrossCalls() {
            HybridQuery q = base().select("c.id").select("c.name", "c.stock").build();
            assertThat(q.sql()).startsWith("SELECT c.id, c.name, c.stock");
        }

        @Test
        @DisplayName("不写 select 默认 SELECT *")
        void defaultsToStar() {
            assertThat(base().build().sql()).startsWith("SELECT *\n");
        }

        @Test
        @DisplayName("空 varargs 等价于没写，仍然默认 *")
        void emptyVarargsFallsBackToStar() {
            assertThat(base().select().build().sql()).startsWith("SELECT *\n");
        }

        @Test
        @DisplayName("select(null) 抛带消息的 NPE")
        void rejectsNullVarargs() {
            assertThatThrownBy(() -> HybridQuery.builder().from("t").select((String[]) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("select 列表不能为 null");
        }

        @Test
        @DisplayName("列表项里混进 null：按标识符白名单拒绝")
        void rejectsNullItem() {
            assertThatThrownBy(() -> base().select("c.id", null))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
        }

        @Test
        @DisplayName("聚合项 count(*) 放行")
        void acceptsAggregate() {
            assertThat(base().select("count(*)").build().sql()).startsWith("SELECT count(*)");
        }
    }

    @Nested
    @DisplayName("from")
    class From {

        @Test
        @DisplayName("null 拒绝，且消息说明不能为空")
        void rejectsNull() {
            assertThatThrownBy(() -> HybridQuery.builder().from(null))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
        }

        @Test
        @DisplayName("空白字符串拒绝")
        void rejectsBlank() {
            assertThatThrownBy(() -> HybridQuery.builder().from("   "))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("schema 限定 + AS 别名放行")
        void acceptsQualifiedAlias() {
            assertThat(HybridQuery.builder().from("public.sku AS s").build().sql())
                    .contains("FROM public.sku AS s");
        }
    }

    @Nested
    @DisplayName("join 与 Join record")
    class Joins {

        @Test
        @DisplayName("两参便捷方法就是 INNER JOIN")
        void convenienceMethodIsInner() {
            HybridQuery q = base().join("sku s", "s.id = c.sku_id").build();
            assertThat(q.sql()).contains("INNER JOIN sku s ON s.id = c.sku_id");
        }

        @Test
        @DisplayName("三种 JoinType 渲染出三种关键字")
        void rendersEachJoinType() {
            assertThat(new HybridQuery.Join(JoinType.INNER, "a x", "x.id = y.id").toSql())
                    .isEqualTo("INNER JOIN a x ON x.id = y.id");
            assertThat(new HybridQuery.Join(JoinType.LEFT, "a x", "x.id = y.id").toSql())
                    .isEqualTo("LEFT JOIN a x ON x.id = y.id");
            assertThat(new HybridQuery.Join(JoinType.RIGHT, "a x", "x.id = y.id").toSql())
                    .isEqualTo("RIGHT JOIN a x ON x.id = y.id");
        }

        @Test
        @DisplayName("多个 JOIN 按调用顺序追加")
        void appendsInOrder() {
            HybridQuery q =
                    base().join("sku s", "s.id = c.sku_id")
                            .join(JoinType.LEFT, "warehouse w", "w.code = s.warehouse")
                            .build();
            assertThat(q.sql())
                    .contains("INNER JOIN sku s ON s.id = c.sku_id\nLEFT JOIN warehouse w");
        }

        @Test
        @DisplayName("Join 的 type 为 null：NPE")
        void rejectsNullType() {
            assertThatThrownBy(() -> new HybridQuery.Join(null, "sku s", "s.id = c.sku_id"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("JOIN 类型不能为 null");
        }

        @Test
        @DisplayName("Join 的 ON 条件带 ?：拒绝并说明参数顺序无法推断")
        void rejectsPlaceholderInOn() {
            assertThatThrownBy(() -> new HybridQuery.Join(JoinType.INNER, "sku s", "s.id = ?"))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不支持 ? 绑定参数");
        }

        @Test
        @DisplayName("Join 的表引用不合法：拒绝")
        void rejectsBadTableRef() {
            assertThatThrownBy(() -> new HybridQuery.Join(JoinType.INNER, "sku; DROP", "1 = 1"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Nested
    @DisplayName("where：占位符与参数必须一一对应")
    class Where {

        @Test
        @DisplayName("参数多了也报错（JDBC 会静默忽略多出来的参数）")
        void rejectsExtraParams() {
            assertThatThrownBy(() -> base().where("s.stock > ?", 1, 2))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("1 个 ? 占位符")
                    .hasMessageContaining("2 个绑定参数");
        }

        @Test
        @DisplayName("参数少了报错，消息两个数字都给全")
        void rejectsMissingParams() {
            assertThatThrownBy(() -> base().where("s.a = ? AND s.b = ?", 1))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("2 个 ? 占位符")
                    .hasMessageContaining("1 个绑定参数");
        }

        @Test
        @DisplayName("没有占位符就没有参数：合法")
        void acceptsZeroZero() {
            HybridQuery q = base().where("s.stock > 0").build();
            assertThat(q.sql()).contains("WHERE s.stock > 0");
            assertThat(q.params()).isEmpty();
        }

        @Test
        @DisplayName("参数数组显式传 null 等价于不传（varargs 的既有语义）")
        void acceptsNullVarargsAsEmpty() {
            HybridQuery q = base().where("s.stock > 0", (Object[]) null).build();
            assertThat(q.params()).isEmpty();
        }

        @Test
        @DisplayName("多个 where 用 AND 连接，参数按 SQL 出现顺序排列")
        void joinsWithAndInOrder() {
            HybridQuery q =
                    base().where("s.warehouse = ?", "SH01")
                            .where("s.stock > ?", 10)
                            .where("s.status = ?", "ON_SALE")
                            .build();
            assertThat(q.sql()).contains("WHERE s.warehouse = ? AND s.stock > ? AND s.status = ?");
            assertThat(q.params()).containsExactly("SH01", 10, "ON_SALE");
        }

        @Test
        @DisplayName("null 谓词拒绝")
        void rejectsNullPredicate() {
            assertThatThrownBy(() -> base().where(null, 1))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
        }
    }

    @Nested
    @DisplayName("nearest 与 expectDimensions 的交互")
    class NearestAndDimensions {

        @Test
        @DisplayName("expectDimensions(0) 与负数拒绝")
        void rejectsNonPositiveExpectation() {
            assertThatThrownBy(() -> base().expectDimensions(0))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("期望维度必须为正数，实际为 0");
            assertThatThrownBy(() -> base().expectDimensions(-3))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("期望维度必须为正数，实际为 -3");
        }

        @Test
        @DisplayName("显式 expectDimensions 不会被 withIndex 覆盖：两个期望不一致时以显式声明为准")
        void explicitExpectationWinsOverIndex() {
            // 索引是 3 维，但显式声明 8 维：构建时先按 8 维校验 -> 报期望 8 维
            assertThatThrownBy(
                            () ->
                                    base().expectDimensions(8)
                                            .withIndex(COSINE_INDEX)
                                            .nearest("c.embedding", QUERY, DistanceOperator.COSINE)
                                            .build())
                    .isInstanceOf(VectorDimensionMismatchException.class)
                    .hasMessageContaining("期望 8 维")
                    .hasMessageContaining("实际 3 维");
        }

        @Test
        @DisplayName("只 withIndex 不 nearest：等于什么都没校验，报错")
        void rejectsIndexWithoutNearest() {
            assertThatThrownBy(() -> base().withIndex(COSINE_INDEX).build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expectDimensions(3)")
                    .hasMessageContaining("nearest");
        }

        @Test
        @DisplayName("withIndex(null) 是合法的「不校验」声明")
        void acceptsExplicitNullIndex() {
            HybridQuery q =
                    base().withIndex(null)
                            .nearest("c.embedding", QUERY, DistanceOperator.L2)
                            .build();
            assertThat(q.sql()).contains("<-> ?::vector");
        }

        @Test
        @DisplayName("查询向量带 NaN：构建期就拒，不会生成一条数据库要报错的 SQL")
        void rejectsNaNInQueryVector() {
            assertThatThrownBy(
                            () ->
                                    base().nearest(
                                                    "c.embedding",
                                                    new float[] {Float.NaN},
                                                    DistanceOperator.COSINE)
                                            .build())
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("nearest 的向量为 null：NPE")
        void rejectsNullVector() {
            assertThatThrownBy(() -> base().nearest("c.embedding", null, DistanceOperator.COSINE))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("查询向量不能为 null");
        }

        @Test
        @DisplayName("1 维查询向量合法")
        void acceptsSingleDimensionVector() {
            HybridQuery q =
                    base().nearest("c.embedding", new float[] {1f}, DistanceOperator.COSINE)
                            .build();
            assertThat(q.params()).containsExactly("[1.0]", "[1.0]");
        }
    }

    @Nested
    @DisplayName("orderBy / distanceAlias")
    class Ordering {

        @Test
        @DisplayName("多个标量排序列追加在距离列之后，顺序保持")
        void appendsScalarOrderings() {
            HybridQuery q =
                    base().nearest("c.embedding", QUERY, DistanceOperator.COSINE)
                            .orderBy("c.updated_at", SortDirection.DESC)
                            .orderBy("c.id", SortDirection.ASC)
                            .build();
            assertThat(q.sql())
                    .contains(
                            "ORDER BY c.embedding <=> ?::vector ASC, c.updated_at DESC, c.id ASC");
        }

        @Test
        @DisplayName("没有向量近邻时只有标量排序")
        void scalarOnlyOrdering() {
            HybridQuery q = base().orderBy("c.id", SortDirection.ASC).build();
            assertThat(q.sql()).contains("ORDER BY c.id ASC").doesNotContain("::vector");
        }

        @Test
        @DisplayName("排序方向 null：NPE")
        void rejectsNullDirection() {
            assertThatThrownBy(() -> base().orderBy("c.id", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("排序方向不能为 null");
        }

        @Test
        @DisplayName("排序列名不合法：白名单拒绝")
        void rejectsBadColumn() {
            assertThatThrownBy(() -> base().orderBy("c.id; DROP", SortDirection.ASC))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("距离列别名默认 distance，可改，改完 SELECT 与 ORDER BY 都用新别名所在的同一表达式")
        void distanceAliasConfigurable() {
            HybridQuery q =
                    base().nearest("c.embedding", QUERY, DistanceOperator.COSINE)
                            .distanceAlias("score")
                            .build();
            assertThat(q.distanceAlias()).isEqualTo("score");
            assertThat(q.sql()).contains("AS score");
        }

        @Test
        @DisplayName("非法别名拒绝")
        void rejectsBadAlias() {
            assertThatThrownBy(() -> base().distanceAlias("bad alias"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Nested
    @DisplayName("limit / offset 边界")
    class Pagination {

        @Test
        @DisplayName("limit=1 与 offset=0 都是合法边界值")
        void acceptsBoundaryValues() {
            HybridQuery q = base().limit(1).offset(0).build();
            assertThat(q.sql()).endsWith("LIMIT 1\nOFFSET 0");
        }

        @Test
        @DisplayName("limit=Integer.MAX_VALUE 不溢出")
        void acceptsMaxLimit() {
            assertThat(base().limit(Integer.MAX_VALUE).build().sql()).endsWith("LIMIT 2147483647");
        }

        @Test
        @DisplayName("offset=Integer.MAX_VALUE 不溢出")
        void acceptsMaxOffset() {
            assertThat(base().offset(Integer.MAX_VALUE).build().sql())
                    .endsWith("OFFSET 2147483647");
        }

        @Test
        @DisplayName("limit=-1 拒绝")
        void rejectsNegativeLimit() {
            assertThatThrownBy(() -> base().limit(-1))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("LIMIT 必须为正数，实际为 -1");
        }
    }

    @Nested
    @DisplayName("生成结果的不可变性与确定性")
    class ResultContract {

        @Test
        @DisplayName("params() 不可修改：调用方改不了绑定顺序")
        void paramsAreUnmodifiable() {
            HybridQuery q = base().where("s.a = ?", 1).build();
            assertThatThrownBy(() -> q.params().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("queryVector() 返回副本：外部改返回值不影响已生成的查询")
        void queryVectorIsDefensiveCopy() {
            HybridQuery q = base().nearest("c.embedding", QUERY, DistanceOperator.COSINE).build();
            float[] leaked = q.queryVector();
            leaked[0] = 999f;
            assertThat(q.queryVector()).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(q.params()).containsExactly(LITERAL, LITERAL);
        }

        @Test
        @DisplayName("纯标量查询的 queryVector() 为 null")
        void scalarQueryHasNullVector() {
            assertThat(base().build().queryVector()).isNull();
        }

        @Test
        @DisplayName("同一个 builder 构建两次得到同一条 SQL（构建不消耗状态）")
        void buildIsRepeatable() {
            HybridQuery.Builder builder =
                    base().select("c.id")
                            .where("s.a = ?", 1)
                            .nearest("c.embedding", QUERY, DistanceOperator.COSINE);
            HybridQuery first = builder.build();
            HybridQuery second = builder.build();
            assertThat(second.sql()).isEqualTo(first.sql());
            assertThat(second.params()).containsExactlyElementsOf(first.params());
        }

        @Test
        @DisplayName("toString 就是 SQL，方便直接打日志")
        void toStringIsSql() {
            HybridQuery q = base().build();
            assertThat(q.toString()).isEqualTo(q.sql());
        }
    }

    @Nested
    @DisplayName("完整形态")
    class FullShape {

        @Test
        @DisplayName("标量全要素（无向量）：SELECT + JOIN + WHERE + ORDER BY + 分页")
        void scalarFullShape() {
            HybridQuery q =
                    HybridQuery.builder()
                            .select("s.id", "s.name")
                            .from("sku_embedding e")
                            .join("sku s", "s.id = e.sku_id")
                            .where("s.warehouse = ?", "SH01")
                            .where("s.stock >= ?", 1)
                            .orderBy("s.id", SortDirection.ASC)
                            .limit(10)
                            .offset(20)
                            .build();
            assertThat(q.sql())
                    .isEqualTo(
                            "SELECT s.id, s.name\n"
                                    + "FROM sku_embedding e\n"
                                    + "INNER JOIN sku s ON s.id = e.sku_id\n"
                                    + "WHERE s.warehouse = ? AND s.stock >= ?\n"
                                    + "ORDER BY s.id ASC\n"
                                    + "LIMIT 10\n"
                                    + "OFFSET 20");
            assertThat(q.params()).containsExactly("SH01", 1);
            assertThat(q.usesVector()).isFalse();
        }

        @Test
        @DisplayName("向量全要素：距离列进 SELECT、向量字面量出现两次（SELECT 与 ORDER BY 各一次）")
        void vectorFullShape() {
            HybridQuery q =
                    HybridQuery.builder()
                            .select("s.sku_code")
                            .from("sku_embedding e")
                            .join("sku s", "s.id = e.sku_id")
                            .where("s.warehouse = ?", "SH01")
                            .nearest("e.embedding", QUERY, DistanceOperator.COSINE)
                            .distanceAlias("dist")
                            .withIndex(COSINE_INDEX)
                            .limit(5)
                            .offset(0)
                            .build();
            assertThat(q.sql())
                    .isEqualTo(
                            "SELECT s.sku_code, (e.embedding <=> ?::vector) AS dist\n"
                                    + "FROM sku_embedding e\n"
                                    + "INNER JOIN sku s ON s.id = e.sku_id\n"
                                    + "WHERE s.warehouse = ?\n"
                                    + "ORDER BY e.embedding <=> ?::vector ASC\n"
                                    + "LIMIT 5\n"
                                    + "OFFSET 0");
            assertThat(q.params()).containsExactly(LITERAL, "SH01", LITERAL);
            assertThat(q.usesVector()).isTrue();
            assertThat(q.operator()).isEqualTo(DistanceOperator.COSINE);
        }
    }
}
