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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pgvector.*} 配置。 */
@ConfigurationProperties(prefix = "pgvector")
public class PgVectorProperties {

    private static final Logger log = LoggerFactory.getLogger(PgVectorProperties.class);

    /** 默认距离算子：配置为空时回退到它（而不是把 null 泄给查询路径）。 */
    static final DistanceOperator DEFAULT_OPERATOR = DistanceOperator.COSINE;

    /** 是否启用自动装配。 */
    private boolean enabled = true;

    /**
     * 默认距离算子。
     *
     * <p>它必须和你建索引时用的 opclass 配套——不一致会导致索引被静默跳过。 这里只是给「没显式指定算子」的情况一个默认值，不会替你校验索引。
     */
    private DistanceOperator defaultOperator = DEFAULT_OPERATOR;

    /**
     * 全局期望维度，0 表示不校验。
     *
     * <p>建议设成你 {@code vector(N)} 里的那个 N。
     */
    private int defaultDimensions = 0;

    /**
     * 算子与索引 opclass 不匹配时是否直接抛异常。
     *
     * <p>默认 true。关掉它等于接受「静默退化成全表扫描」，只应该在你明确知道自己在做什么时关。
     */
    private boolean failOnOperatorIndexMismatch = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DistanceOperator getDefaultOperator() {
        return defaultOperator;
    }

    /**
     * 设置默认距离算子；null / 空值回退到 {@link #DEFAULT_OPERATOR} 并告警。
     *
     * <p>★ 为什么回退而不是抛异常：配置绑定阶段的 null 多半是「yml 里写错了名字」或「没写」， 抛异常会让应用起不来；回退到 COSINE 至少让「没定向量的查询」能跑，同时
     * warn 留痕。
     */
    public void setDefaultOperator(DistanceOperator defaultOperator) {
        if (defaultOperator == null) {
            log.warn("pgvector.default-operator 为空，回退到默认算子 {}（请检查配置项名称）", DEFAULT_OPERATOR);
            this.defaultOperator = DEFAULT_OPERATOR;
            return;
        }
        this.defaultOperator = defaultOperator;
    }

    public int getDefaultDimensions() {
        return defaultDimensions;
    }

    /**
     * 设置全局期望维度；负数回退到 0（= 不校验）并告警。
     *
     * <p>★ 0 是「不校验」的合法语义，负值则是配置错误——回退比抛异常更利于启动， 但必须 warn：静默接受一个非法维度等于关掉了用户显式要求的维度闸门。
     */
    public void setDefaultDimensions(int defaultDimensions) {
        if (defaultDimensions < 0) {
            log.warn(
                    "pgvector.default-dimensions 为负数 {}，回退到 0（不校验维度）；"
                            + "如需校验请设为你建表的 vector(N) 里的 N",
                    defaultDimensions);
            this.defaultDimensions = 0;
            return;
        }
        this.defaultDimensions = defaultDimensions;
    }

    public boolean isFailOnOperatorIndexMismatch() {
        return failOnOperatorIndexMismatch;
    }

    public void setFailOnOperatorIndexMismatch(boolean failOnOperatorIndexMismatch) {
        this.failOnOperatorIndexMismatch = failOnOperatorIndexMismatch;
    }
}
