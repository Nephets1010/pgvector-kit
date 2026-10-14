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

/**
 * 查询用的距离算子与索引的 opclass 不匹配。
 *
 * <p>这是本项目存在的核心理由之一。PostgreSQL 在这种情况下的行为是<strong>什么都不说</strong>： SQL
 * 照常执行、结果照常返回（顺序也是对的），只是那个索引一次都不会被用到， 于是 QPS 一上来就是全表扫描。开发环境几百行数据根本看不出来，上线后几百万行才发现。
 *
 * <p>所以这里选择抛异常，而不是打个 warning 继续。
 */
public class OperatorIndexMismatchException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient VectorIndex index;
    private final transient DistanceOperator queryOperator;

    public OperatorIndexMismatchException(
            VectorIndex index, DistanceOperator queryOperator, String extraDetail) {
        super(
                buildMessage(index, queryOperator)
                        + (extraDetail == null || extraDetail.isBlank() ? "" : " " + extraDetail));
        this.index = index;
        this.queryOperator = queryOperator;
    }

    private static String buildMessage(VectorIndex index, DistanceOperator queryOperator) {
        if (index == null) {
            return "距离算子与索引 opclass 不匹配：索引信息缺失";
        }
        if (queryOperator == null) {
            return "索引 " + index.name() + " 要求使用 " + index.operatorClass() + "，但查询没有指定距离算子";
        }
        return "距离算子与索引 opclass 不匹配：索引 "
                + index.name()
                + " ("
                + index.type().keyword()
                + ") 建在 "
                + index.operatorClass()
                + " 上，只能加速 "
                + index.operator().symbol()
                + " 查询；而你这次用的是 "
                + queryOperator.symbol()
                + " ("
                + queryOperator.operatorClass()
                + ")。PostgreSQL 不会报错，只会静默退化成全表扫描——这里直接拦下。"
                + " 要么把查询算子改成 "
                + index.operator().symbol()
                + "，要么按 "
                + queryOperator.operatorClass()
                + " 重建一张索引。";
    }

    /** 那张用不上的索引。 */
    public VectorIndex index() {
        return index;
    }

    /** 这次查询用的算子。 */
    public DistanceOperator queryOperator() {
        return queryOperator;
    }
}
