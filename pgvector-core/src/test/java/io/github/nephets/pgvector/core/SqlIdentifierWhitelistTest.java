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
 * 白名单的<strong>每一个拒绝点</strong>都要有用例：这里漏一个形状，线上就多一个注入面。
 *
 * <p>与 {@code SqlIdentifierTest} 的分工：那个文件讲「什么放行、什么拒绝」的轮廓， 这个文件把每个拒绝分支走到，并断言异常消息里带上了
 * role、被拒的值与原因——运维要靠这三样定位「谁在传什么进来」。
 */
@DisplayName("SQL 标识符白名单：逐个拒绝点")
class SqlIdentifierWhitelistTest {

    private static final String ROLE = "列名";

    @Nested
    @DisplayName("requireIdentifier：列名 / 索引名这类裸标识符")
    class Identifier {

        @Test
        @DisplayName("字母、下划线、$ 开头都合法；schema 限定与多点限定也合法")
        void acceptsValidShapes() {
            assertThat(SqlIdentifier.requireIdentifier("embedding", ROLE)).isEqualTo("embedding");
            assertThat(SqlIdentifier.requireIdentifier("_private", ROLE)).isEqualTo("_private");
            assertThat(SqlIdentifier.requireIdentifier("col$1", ROLE)).isEqualTo("col$1");
            assertThat(SqlIdentifier.requireIdentifier("public.sku_embedding", ROLE))
                    .isEqualTo("public.sku_embedding");
            assertThat(SqlIdentifier.requireIdentifier("a.b.c", ROLE)).isEqualTo("a.b.c");
        }

        @Test
        @DisplayName("数字开头拒绝")
        void rejectsDigitStart() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("1col", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("1col")
                    .hasMessageContaining("字母/数字/下划线");
        }

        @Test
        @DisplayName("中间带空格拒绝（\"a b\" 可以拼成两个标识符）")
        void rejectsInnerSpace() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a b", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("a b");
        }

        @Test
        @DisplayName("分号拒绝（语句终止符是第一号注入载荷）")
        void rejectsSemicolon() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a;b", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("括号拒绝（子查询入口）")
        void rejectsParentheses() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a(b)", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("连字符拒绝（SQL 注释的一半）")
        void rejectsDash() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a-b", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("中文标识符拒绝：宁可让用户改列名，也不放宽白名单")
        void rejectsNonAscii() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("列名", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("星号拒绝：select 列表的 * 有专门形状，裸标识符不接受")
        void rejectsStar() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("*", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("悬空点与双点拒绝（a. 与 a..b 都不是完整限定名）")
        void rejectsDanglingDots() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a.", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a..b", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("null 与空白拒绝，且消息说明「不能为空」")
        void rejectsNullAndBlank() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier(null, ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("   ", ROLE))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
        }

        @Test
        @DisplayName("异常消息三要素齐全：role + 被拒值 + 规则")
        void messageCarriesRoleValueAndRule() {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a b", "WHERE 条件"))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("WHERE 条件")
                    .hasMessageContaining("a b")
                    .hasMessageContaining("只允许");
        }
    }

    @Nested
    @DisplayName("requireTableRef：FROM / JOIN 的表引用")
    class TableRef {

        @Test
        @DisplayName("四种合法形状：裸表、别名、AS 别名、schema 限定 + AS 别名")
        void acceptsTableRefShapes() {
            assertThat(SqlIdentifier.requireTableRef("sku", "表")).isEqualTo("sku");
            assertThat(SqlIdentifier.requireTableRef("sku s", "表")).isEqualTo("sku s");
            assertThat(SqlIdentifier.requireTableRef("public.sku", "表")).isEqualTo("public.sku");
            assertThat(SqlIdentifier.requireTableRef("public.sku AS s", "表"))
                    .isEqualTo("public.sku AS s");
        }

        @Test
        @DisplayName("AS 大小写不敏感（SQL 关键字本来就不分大小写）")
        void asIsCaseInsensitive() {
            assertThat(SqlIdentifier.requireTableRef("public.sku as s", "表"))
                    .isEqualTo("public.sku as s");
        }

        @Test
        @DisplayName("三个 token 拒绝：FROM sku s x 是语法错误，不能放进去让数据库报")
        void rejectsExtraToken() {
            assertThatThrownBy(() -> SqlIdentifier.requireTableRef("sku s x", "表"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("经典注入载荷拒绝：sku s; DROP TABLE x")
        void rejectsDropTablePayload() {
            assertThatThrownBy(() -> SqlIdentifier.requireTableRef("sku s; DROP TABLE x", "表"))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("表名[ 别名]");
        }

        @Test
        @DisplayName("双引号包裹的标识符拒绝（引号是注入的逃生舱）")
        void rejectsQuotedIdentifier() {
            assertThatThrownBy(() -> SqlIdentifier.requireTableRef("\"sku\"", "表"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("子查询形状拒绝：FROM (SELECT ...)")
        void rejectsSubquery() {
            assertThatThrownBy(() -> SqlIdentifier.requireTableRef("(SELECT 1)", "表"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Nested
    @DisplayName("requireSelectItem：SELECT 列表项")
    class SelectItem {

        @Test
        @DisplayName("五种合法形状：*、别名.*、列名、列名 AS 别名、count(*)")
        void acceptsSelectItemShapes() {
            for (String good :
                    new String[] {"*", "s.*", "s.id", "s.id AS code", "count(*)", "COUNT(*)"}) {
                assertThat(SqlIdentifier.requireSelectItem(good, "SELECT 列表项"))
                        .as("期望 %s 放行", good)
                        .isEqualTo(good);
            }
        }

        @Test
        @DisplayName("count( * ) 括号内允许空白")
        void acceptsSpacesInsideAggregate() {
            assertThat(SqlIdentifier.requireSelectItem("count( * )", "SELECT 列表项"))
                    .isEqualTo("count( * )");
        }

        @Test
        @DisplayName("schema 限定的列名放行")
        void acceptsQualifiedColumn() {
            assertThat(SqlIdentifier.requireSelectItem("public.sku.id", "SELECT 列表项"))
                    .isEqualTo("public.sku.id");
        }

        @Test
        @DisplayName("子查询拒绝")
        void rejectsSubquery() {
            assertThatThrownBy(() -> SqlIdentifier.requireSelectItem("(SELECT 1)", "SELECT 列表项"))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("count(*)");
        }

        @Test
        @DisplayName("带列参数的函数调用拒绝：lower(s.code) 不在白名单形状里")
        void rejectsFunctionWithArguments() {
            assertThatThrownBy(() -> SqlIdentifier.requireSelectItem("lower(s.code)", "SELECT 列表项"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("AS 后面没有别名拒绝")
        void rejectsDanglingAs() {
            assertThatThrownBy(() -> SqlIdentifier.requireSelectItem("s.id AS", "SELECT 列表项"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("缺 AS 的别名（s.id code）拒绝：避免与两列表格的隐式连接混淆")
        void rejectsAliasWithoutAs() {
            assertThatThrownBy(() -> SqlIdentifier.requireSelectItem("s.id code", "SELECT 列表项"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("别名后面再跟 token 拒绝")
        void rejectsTrailingTokenAfterAlias() {
            assertThatThrownBy(
                            () -> SqlIdentifier.requireSelectItem("s.id AS code x", "SELECT 列表项"))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Nested
    @DisplayName("requireClause：WHERE 谓词与 JOIN 的 ON 条件")
    class Clause {

        @Test
        @DisplayName("常规谓词形状全部放行：比较、IN、BETWEEN、LIKE、IS NULL、无占位符的连接条件")
        void acceptsNormalPredicates() {
            for (String good :
                    new String[] {
                        "s.stock > ?",
                        "s.status IN (?, ?)",
                        "s.name LIKE ?",
                        "s.updated_at IS NULL",
                        "s.stock BETWEEN ? AND ?",
                        "s.id = c.sku_id"
                    }) {
                assertThat(SqlIdentifier.requireClause(good, "WHERE 条件", true))
                        .as("期望 %s 放行", good)
                        .isEqualTo(good);
            }
        }

        @Test
        @DisplayName("分号拒绝：语句截断是第一号注入载荷")
        void rejectsSemicolon() {
            assertThatThrownBy(
                            () -> SqlIdentifier.requireClause("a = ?; DROP TABLE t", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("; -- /* */");
        }

        @Test
        @DisplayName("行注释 -- 拒绝")
        void rejectsLineComment() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("a = ? --", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("块注释起 /* 拒绝")
        void rejectsBlockCommentStart() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("a = /* x */ ?", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("块注释止 */ 单独出现也拒绝（闭合一个没打开的注释同样是异常形状）")
        void rejectsBlockCommentEnd() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("a = ? */", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("单引号拒绝：字面量必须走 ? 绑定，禁引号是逼大家参数化的手段")
        void rejectsSingleQuote() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("a = 'x'", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("引号");
        }

        @Test
        @DisplayName("双引号拒绝")
        void rejectsDoubleQuote() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("a = \"x\"", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("反引号拒绝（MySQL 习惯带过来的注入面）")
        void rejectsBacktick() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("a = `x`", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("allowPlaceholder=false 时连 ? 都拒绝，并说明理由")
        void rejectsPlaceholderWhenNotAllowed() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause("s.id = ?", "JOIN 的 ON 条件", false))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不支持 ? 绑定参数")
                    .hasMessageContaining("JOIN 的 ON 条件");
        }

        @Test
        @DisplayName("null 与空白拒绝")
        void rejectsNullAndBlank() {
            assertThatThrownBy(() -> SqlIdentifier.requireClause(null, "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class)
                    .hasMessageContaining("不能为空");
            assertThatThrownBy(() -> SqlIdentifier.requireClause("  ", "WHERE", true))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Nested
    @DisplayName("countPlaceholders：占位符计数")
    class PlaceholderCount {

        @Test
        @DisplayName("0、1、3 个都数得准")
        void countsCorrectly() {
            assertThat(SqlIdentifier.countPlaceholders("a > 0")).isZero();
            assertThat(SqlIdentifier.countPlaceholders("a = ?")).isEqualTo(1);
            assertThat(SqlIdentifier.countPlaceholders("a = ? AND b IN (?, ?)")).isEqualTo(3);
        }

        @Test
        @DisplayName("相邻占位符 ?? 数成 2（PostgreSQL 里确实是两个参数位）")
        void countsAdjacentPlaceholders() {
            assertThat(SqlIdentifier.countPlaceholders("a = ??")).isEqualTo(2);
        }

        @Test
        @DisplayName("空串数成 0")
        void countsEmptyClause() {
            assertThat(SqlIdentifier.countPlaceholders("")).isZero();
        }
    }
}
