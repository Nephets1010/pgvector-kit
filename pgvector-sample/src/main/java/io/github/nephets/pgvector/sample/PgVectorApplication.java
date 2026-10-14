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

import io.github.nephets.pgvector.core.DistanceOperator;
import io.github.nephets.pgvector.core.HybridQuery;
import io.github.nephets.pgvector.core.IndexRequest;
import io.github.nephets.pgvector.core.VectorCodec;
import io.github.nephets.pgvector.spring.PgVectorTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * 可运行 demo。
 *
 * <pre>{@code
 * docker compose up -d                       # 起 postgres + pgvector
 * ./mvnw -pl pgvector-sample spring-boot:run # 跑这个 demo
 * }</pre>
 *
 * <p>它会打印三件事：生成的那条 SQL、混合查询的结果、以及索引建议器给出的 DDL。
 */
@SpringBootApplication
public class PgVectorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PgVectorApplication.class);

    private final PgVectorTemplate vectorTemplate;
    private final PlatformTransactionManager transactionManager;

    public PgVectorApplication(
            PgVectorTemplate vectorTemplate, PlatformTransactionManager transactionManager) {
        this.vectorTemplate = vectorTemplate;
        this.transactionManager = transactionManager;
    }

    public static void main(String[] args) {
        SpringApplication.run(PgVectorApplication.class, args);
    }

    @Override
    public void run(String... args) {
        log.info("pgvector-kit demo starting");
        InventoryScenario scenario = new InventoryScenario(vectorTemplate, transactionManager);

        scenario.reset();
        scenario.seedInOneTransaction(InventoryScenario.demoSkus());
        log.info(
                "demo seeded {} skus / {} embeddings (single transaction)",
                scenario.countSkus(),
                scenario.countEmbeddings());
        System.out.println(
                "已写入 "
                        + scenario.countSkus()
                        + " 个商品、"
                        + scenario.countEmbeddings()
                        + " 条向量（同一个事务）");

        String keyword = "无线鼠标 黑色";
        InventoryScenario.SearchRequest request =
                InventoryScenario.SearchRequest.of("SH01", keyword);
        HybridQuery query = scenario.buildQuery(request);

        System.out.println();
        System.out.println("查询关键词：" + keyword + "   仓库：SH01   仅看在售且有库存");
        System.out.println("生成的 SQL（就这一条，分页与排序都在数据库里）：");
        System.out.println(query.sql());
        System.out.println("绑定参数：" + query.params());
        System.out.println();

        List<SearchHit> hits = scenario.search(request);
        log.info("demo similarity search returned {} hits", hits.size());
        for (SearchHit hit : hits) {
            System.out.println("  " + hit);
        }

        System.out.println();
        System.out.println("索引建议（按 100 万行 / 1536 维 / 余弦 / 要求 0.95 召回）：");
        var advice =
                io.github.nephets.pgvector.core.IndexAdvisor.advise(
                        IndexRequest.of(
                                1_000_000,
                                1536,
                                DistanceOperator.COSINE,
                                "sku_embedding",
                                "embedding"));
        System.out.println("  " + advice.summary());
        System.out.println("  " + advice.ddl());
        System.out.println("  " + advice.searchSettings());

        System.out.println();
        System.out.println("把查询向量换成 4 维会发生什么（vector(8) 的列）：");
        try {
            VectorCodec.requireDimensions(
                    new float[4], InventoryScenario.DIMENSIONS, "sku_embedding.embedding");
        } catch (RuntimeException e) {
            System.out.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
