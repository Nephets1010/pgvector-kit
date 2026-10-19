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
package io.github.nephets.pgvector.sample;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 给集成测试准备一个「真的有 pgvector」的 PostgreSQL。
 *
 * <p>两种来源，按优先级：
 *
 * <ol>
 *   <li>{@code -Dpgvector.jdbc.url=...}：连已有的数据库。CI 的 compose-smoke job 走这条路， 于是「compose
 *       起来那个库」和「测试跑的那个库」是同一个，不会出现两套环境各自漂移。
 *   <li>Testcontainers 起一个 {@code pgvector/pgvector:pg16}。
 * </ol>
 *
 * <p>本机没有 Docker 且没给 URL 时，测试会被<strong>跳过</strong>（{@code assumeTrue}），
 * 而不是伪装成通过——这一点写在这里是为了提醒：跳过不等于验证过。
 */
final class TestPostgres {

    /** pgvector 官方镜像：它就是在 postgres 镜像上装好 vector 扩展。 */
    static final String IMAGE = "pgvector/pgvector:pg16";

    private static final String URL_PROPERTY = "pgvector.jdbc.url";
    private static final String USER_PROPERTY = "pgvector.jdbc.user";
    private static final String PASSWORD_PROPERTY = "pgvector.jdbc.password";

    private static PostgreSQLContainer<?> container;

    private TestPostgres() {}

    static DataSource start() {
        String url = System.getProperty(URL_PROPERTY);
        if (url != null && !url.isBlank()) {
            return dataSource(
                    url,
                    System.getProperty(USER_PROPERTY, "postgres"),
                    System.getProperty(PASSWORD_PROPERTY, "postgres"));
        }
        assumeTrue(
                dockerAvailable(),
                "没有可用的 Docker 且未指定 -D" + URL_PROPERTY + "，跳过需要真实 pgvector 的集成测试");
        container =
                new PostgreSQLContainer<>(
                                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("vectordemo")
                        .withUsername("postgres")
                        .withPassword("postgres");
        container.start();
        return dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    static void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    private static DataSource dataSource(String url, String username, String password) {
        return DataSourceBuilder.create()
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
