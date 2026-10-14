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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * pgvector-kit 的自动装配。
 *
 * <p>刻意只做一件事：在有 {@link DataSource} 时提供 {@link PgVectorTemplate}。 它不创建连接、不扫
 * Mapper、不建表、不建索引——那些都是你的事， 一个在启动时偷偷执行 DDL 的 starter 迟早会在生产上闯祸。
 */
/*
 * ★ ConditionalOnClass 用的是【字符串】形式而不是 DataSource.class：
 *   写成类字面量会强迫 spring-jdbc 在解析注解时就存在于类路径上，
 *   而那正是我们要检测的东西（解析失败会直接 NoClassDefFoundError，而不是安静地跳过）。
 *   字符串形式下 Spring 用 ASM 判断，缺了就正常跳过自动装配。
 */
@AutoConfiguration
@ConditionalOnClass(name = {"javax.sql.DataSource", "org.springframework.jdbc.core.JdbcTemplate"})
@EnableConfigurationProperties(PgVectorProperties.class)
@ConditionalOnProperty(prefix = "pgvector", name = "enabled", matchIfMissing = true)
public class PgVectorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PgVectorAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    public PgVectorTemplate pgVectorTemplate(DataSource dataSource, PgVectorProperties properties) {
        log.info(
                "creating PgVectorTemplate bean (pgvector auto-configuration active,"
                        + " defaultOperator={})",
                properties.getDefaultOperator());
        return new PgVectorTemplate(dataSource, properties);
    }
}
