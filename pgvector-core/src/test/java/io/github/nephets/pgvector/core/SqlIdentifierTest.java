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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SQL 标识符白名单")
class SqlIdentifierTest {

    @Test
    @DisplayName("普通列名、schema 限定名、带 $ 的名字都放行")
    void acceptsValidIdentifiers() {
        assertThat(SqlIdentifier.requireIdentifier("embedding", "列名")).isEqualTo("embedding");
        assertThat(SqlIdentifier.requireIdentifier("public.sku", "列名")).isEqualTo("public.sku");
        assertThat(SqlIdentifier.requireIdentifier("_tmp$1", "列名")).isEqualTo("_tmp$1");
    }

    @Test
    @DisplayName("首尾空白会被去掉")
    void trims() {
        assertThat(SqlIdentifier.requireIdentifier("  embedding  ", "列名")).isEqualTo("embedding");
    }

    @Test
    @DisplayName("以数字开头、含空格、含分号、含括号都拒绝")
    void rejectsInvalidIdentifiers() {
        for (String bad : new String[] {"1col", "a b", "a;b", "a(b)", "a-b", "列"}) {
            assertThatThrownBy(() -> SqlIdentifier.requireIdentifier(bad, "列名"))
                    .as("期望 %s 被拒绝", bad)
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Test
    @DisplayName("空值拒绝")
    void rejectsBlank() {
        assertThatThrownBy(() -> SqlIdentifier.requireIdentifier(null, "列名"))
                .isInstanceOf(InvalidSqlIdentifierException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("   ", "列名"))
                .isInstanceOf(InvalidSqlIdentifierException.class);
    }

    @Test
    @DisplayName("表引用允许别名，三种写法都放行")
    void acceptsTableRefsWithAlias() {
        assertThat(SqlIdentifier.requireTableRef("sku", "表")).isEqualTo("sku");
        assertThat(SqlIdentifier.requireTableRef("sku s", "表")).isEqualTo("sku s");
        assertThat(SqlIdentifier.requireTableRef("public.sku AS s", "表"))
                .isEqualTo("public.sku AS s");
    }

    @Test
    @DisplayName("表引用里带注入载荷拒绝")
    void rejectsInjectionInTableRef() {
        assertThatThrownBy(() -> SqlIdentifier.requireTableRef("sku s; DROP TABLE x", "表"))
                .isInstanceOf(InvalidSqlIdentifierException.class);
    }

    @Test
    @DisplayName("SELECT 列表允许 *、别名.*、列名 AS 别名、count(*)")
    void acceptsSelectItems() {
        for (String good : new String[] {"*", "s.*", "s.id", "s.id AS code", "count(*)"}) {
            assertThat(SqlIdentifier.requireSelectItem(good, "SELECT 列表项")).isEqualTo(good);
        }
    }

    @Test
    @DisplayName("SELECT 列表里的子查询与函数调用（带列名）拒绝：白名单只放行简单形状")
    void rejectsSelectItemWithArguments() {
        assertThatThrownBy(() -> SqlIdentifier.requireSelectItem("(SELECT 1)", "SELECT 列表项"))
                .isInstanceOf(InvalidSqlIdentifierException.class);
        assertThatThrownBy(() -> SqlIdentifier.requireSelectItem("lower(s.code)", "SELECT 列表项"))
                .isInstanceOf(InvalidSqlIdentifierException.class);
    }

    @Test
    @DisplayName("谓词允许 ? 与比较、AND、OR、IN 等常规写法")
    void acceptsNormalPredicates() {
        assertThat(SqlIdentifier.requireClause("s.stock > ? AND s.status IN (?, ?)", "WHERE", true))
                .isEqualTo("s.stock > ? AND s.status IN (?, ?)");
    }

    @Test
    @DisplayName("谓词里的分号、注释、引号一律拒绝")
    void rejectsDangerousPredicates() {
        for (String bad :
                new String[] {"a = ?; DROP TABLE t", "a = ? --", "a = /* x */ ?", "a = 'x'"}) {
            assertThatThrownBy(() -> SqlIdentifier.requireClause(bad, "WHERE", true))
                    .as("期望 %s 被拒绝", bad)
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }
    }

    @Test
    @DisplayName("ON 条件不允许 ?（allowPlaceholder=false）")
    void rejectsPlaceholderWhenNotAllowed() {
        assertThatThrownBy(() -> SqlIdentifier.requireClause("s.id = ?", "ON", false))
                .isInstanceOf(InvalidSqlIdentifierException.class)
                .hasMessageContaining("不支持 ? 绑定参数");
    }

    @Test
    @DisplayName("? 计数要准")
    void countsPlaceholders() {
        assertThat(SqlIdentifier.countPlaceholders("a = ? AND b IN (?, ?)")).isEqualTo(3);
        assertThat(SqlIdentifier.countPlaceholders("a > 0")).isZero();
    }

    @Test
    @DisplayName("异常消息里带上字段名与被拒的值，便于定位")
    void messageIsActionable() {
        assertThatThrownBy(() -> SqlIdentifier.requireIdentifier("a b", "WHERE 条件"))
                .hasMessageContaining("WHERE 条件")
                .hasMessageContaining("a b");
    }
}
