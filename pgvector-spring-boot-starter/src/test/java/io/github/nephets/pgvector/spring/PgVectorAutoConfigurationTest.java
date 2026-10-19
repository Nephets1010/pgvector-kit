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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("pgvector starter 自动装配")
class PgVectorAutoConfigurationTest {

    private static DataSource h2() {
        return DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url("jdbc:h2:mem:pgvector-autoconfig;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .build();
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PgVectorAutoConfiguration.class))
                    .withBean(DataSource.class, PgVectorAutoConfigurationTest::h2);

    @Test
    @DisplayName("有 DataSource 时装配出 PgVectorTemplate")
    void providesTemplate() {
        runner.run(context -> assertThat(context).hasSingleBean(PgVectorTemplate.class));
    }

    @Test
    @DisplayName("没有 DataSource 时安静跳过，不抛错")
    void backsOffWithoutDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PgVectorAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(PgVectorTemplate.class));
    }

    @Test
    @DisplayName("pgvector.enabled=false 时关闭装配")
    void backsOffWhenDisabled() {
        runner.withPropertyValues("pgvector.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PgVectorTemplate.class));
    }

    @Test
    @DisplayName("默认算子是余弦，默认不校验维度")
    void bindsDefaults() {
        runner.run(
                context -> {
                    PgVectorProperties properties = context.getBean(PgVectorProperties.class);
                    assertThat(properties.getDefaultOperator()).isEqualTo(DistanceOperator.COSINE);
                    assertThat(properties.getDefaultDimensions()).isZero();
                    assertThat(properties.isFailOnOperatorIndexMismatch()).isTrue();
                    assertThat(properties.isEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("配置项能覆盖默认值")
    void bindsOverrides() {
        runner.withPropertyValues(
                        "pgvector.default-operator=inner_product",
                        "pgvector.default-dimensions=1536",
                        "pgvector.fail-on-operator-index-mismatch=false")
                .run(
                        context -> {
                            PgVectorProperties properties =
                                    context.getBean(PgVectorProperties.class);
                            assertThat(properties.getDefaultOperator())
                                    .isEqualTo(DistanceOperator.INNER_PRODUCT);
                            assertThat(properties.getDefaultDimensions()).isEqualTo(1536);
                            assertThat(properties.isFailOnOperatorIndexMismatch()).isFalse();
                        });
    }

    @Test
    @DisplayName("装配出来的 template 用的是同一个 DataSource")
    void templateUsesTheDataSource() {
        runner.run(
                context -> {
                    PgVectorTemplate template = context.getBean(PgVectorTemplate.class);
                    assertThat(template.jdbcTemplate().getDataSource())
                            .isSameAs(context.getBean(DataSource.class));
                });
    }
}
