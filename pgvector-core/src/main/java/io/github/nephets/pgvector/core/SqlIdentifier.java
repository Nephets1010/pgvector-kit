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

import java.util.regex.Pattern;

/**
 * SQL 标识符白名单校验。
 *
 * <p>混合查询 DSL 是「拼 SQL」的，表名 / 列名必须拼进去——没有别的办法，因为占位符只能替代表达式的值，
 * 不能替代表达式本身。但只要是拼字符串，就有注入面。这里的对策不是「提醒使用者小心」， 而是把标识符限定在一个很小的白名单里：只允许 {@code 字母开头 + 字母数字下划线} 的形状， 允许
 * {@code schema.table} 与 {@code 表别名}。分号、注释、引号、括号一律拒绝。
 *
 * <p>后果是：带空格或中文的表名用不了。这是刻意的取舍——宁可让用户改列名，也不留一个注入面。
 *
 * <p>★ 被拒即 warn：白名单命中失败大概率是注入尝试或手滑，运维需要知道「谁在传什么进来」。
 */
public final class SqlIdentifier {

    private static final Logger log = LoggerFactory.getLogger(SqlIdentifier.class);

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_$]*";

    private static final Pattern IDENTIFIER = Pattern.compile(NAME + "(\\." + NAME + ")*");

    /**
     * 表引用：{@code doc_chunk} / {@code public.doc_chunk} / {@code doc_chunk c} / {@code doc_chunk AS
     * c}
     */
    private static final Pattern TABLE_REF =
            Pattern.compile(
                    NAME + "(\\." + NAME + ")*(\\s+(AS\\s+)?" + NAME + ")?",
                    Pattern.CASE_INSENSITIVE);

    /** 选择列：{@code *} / {@code c.*} / {@code c.id} / {@code c.id AS sku_id} / {@code count(*)} */
    private static final Pattern SELECT_ITEM =
            Pattern.compile(
                    "(\\*|"
                            + NAME
                            + "\\.\\*|"
                            + IDENTIFIER.pattern()
                            + "(\\s+AS\\s+"
                            + NAME
                            + ")?"
                            + "|"
                            + NAME
                            + "\\s*\\(\\s*\\*\\s*\\))",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 任何拼进 SQL 的自由文本（WHERE 谓词、JOIN 的 ON 条件）都不得出现的东西。
     *
     * <p>这里刻意把单引号一起禁掉：谓词里的字面量应该用 {@code ?} 绑定，而不是拼进字符串。 禁掉引号会逼使用者写成 {@code status =
     * ?}，顺带也解决了参数化查询的问题。
     */
    private static final Pattern FORBIDDEN_IN_CLAUSE = Pattern.compile(";|\\-\\-|/\\*|\\*/|'|\"|`");

    private SqlIdentifier() {}

    /** 校验列名之类的标识符。 */
    public static String requireIdentifier(String value, String role) {
        String v = requireNonBlank(value, role);
        if (!IDENTIFIER.matcher(v).matches()) {
            reject(role, v, "只允许 字母/数字/下划线，且以字母或下划线开头");
        }
        return v;
    }

    /** 校验 {@code FROM} 后面的表引用，允许带别名。 */
    public static String requireTableRef(String value, String role) {
        String v = requireNonBlank(value, role);
        if (!TABLE_REF.matcher(v).matches()) {
            reject(role, v, "只允许 \"表名[ 别名]\" 或 \"schema.表名[ AS 别名]\" 的形状");
        }
        return v;
    }

    /** 校验 {@code SELECT} 列表里的一项。 */
    public static String requireSelectItem(String value, String role) {
        String v = requireNonBlank(value, role);
        if (!SELECT_ITEM.matcher(v).matches()) {
            reject(role, v, "只允许 *, 别名.*, 列名, 列名 AS 别名, 或 count(*) 这种无参聚合");
        }
        return v;
    }

    /**
     * 校验拼进 SQL 的自由文本（WHERE 谓词、JOIN 的 ON 条件）。
     *
     * @param value 待校验文本
     * @param role 出错时用于定位的说明
     * @param allowPlaceholder 是否允许 {@code ?} 占位符；JOIN 的 ON 条件不允许（参数顺序会变得难以推断）
     */
    public static String requireClause(String value, String role, boolean allowPlaceholder) {
        String v = requireNonBlank(value, role);
        if (FORBIDDEN_IN_CLAUSE.matcher(v).find()) {
            reject(role, v, "不允许出现 ; -- /* */ 或引号；字面量请用 ? 绑定");
        }
        if (!allowPlaceholder && v.indexOf('?') >= 0) {
            reject(role, v, "这里不支持 ? 绑定参数（会把参数顺序变得无法推断）");
        }
        return v;
    }

    /** 数出子句里的 {@code ?} 个数，用于校验绑定参数数量。 */
    public static int countPlaceholders(String clause) {
        int count = 0;
        for (int i = 0; i < clause.length(); i++) {
            if (clause.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    private static String requireNonBlank(String value, String role) {
        if (value == null || value.isBlank()) {
            reject(role, String.valueOf(value), "不能为空");
        }
        return value.trim();
    }

    /**
     * 白名单拒绝的统一出口：warn 留痕后抛异常。
     *
     * <p>★ 为什么不在抛出处各自写日志：五个拒绝点的消息格式必须一致（role + value + 原因）， 收拢到一个方法里，改格式只改一处。
     */
    private static void reject(String role, String value, String reason) {
        log.warn("SQL 标识符白名单拒绝 role={} value=[{}] reason={}", role, value, reason);
        throw new InvalidSqlIdentifierException(role, value, reason);
    }
}
