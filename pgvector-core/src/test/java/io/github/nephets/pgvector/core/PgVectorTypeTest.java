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

@DisplayName("距离算子 / 索引类型 / JOIN 类型的自身契约")
class PgVectorTypeTest {

    @Test
    @DisplayName("三个算子的符号、opclass 一一对应，不能串")
    void operatorSymbolsAndClasses() {
        assertThat(DistanceOperator.L2.symbol()).isEqualTo("<->");
        assertThat(DistanceOperator.L2.operatorClass()).isEqualTo("vector_l2_ops");
        assertThat(DistanceOperator.COSINE.symbol()).isEqualTo("<=>");
        assertThat(DistanceOperator.COSINE.operatorClass()).isEqualTo("vector_cosine_ops");
        assertThat(DistanceOperator.INNER_PRODUCT.symbol()).isEqualTo("<#>");
        assertThat(DistanceOperator.INNER_PRODUCT.operatorClass()).isEqualTo("vector_ip_ops");
    }

    @Test
    @DisplayName("每个算子只认自己的 opclass")
    void matchesOnlyItsOwnOperatorClass() {
        assertThat(DistanceOperator.COSINE.matchesOperatorClass("vector_cosine_ops")).isTrue();
        assertThat(DistanceOperator.COSINE.matchesOperatorClass("vector_l2_ops")).isFalse();
        assertThat(DistanceOperator.COSINE.matchesOperatorClass("vector_ip_ops")).isFalse();
    }

    @Test
    @DisplayName("内积算子必须说明自己返回的是负内积 —— 这是最容易写错方向的一个")
    void innerProductDocumentsNegativeSign() {
        assertThat(DistanceOperator.INNER_PRODUCT.semantics()).contains("负内积");
    }

    @Test
    @DisplayName("HNSW / IVFFlat 的关键字就是 CREATE INDEX ... USING 后面那个")
    void indexKeywords() {
        assertThat(IndexType.HNSW.keyword()).isEqualTo("hnsw");
        assertThat(IndexType.IVFFLAT.keyword()).isEqualTo("ivfflat");
    }

    @Test
    @DisplayName("JOIN 关键字渲染成 <KEYWORD> JOIN")
    void joinKeywords() {
        assertThat(JoinType.INNER.keyword()).isEqualTo("INNER");
        assertThat(JoinType.LEFT.keyword()).isEqualTo("LEFT");
        assertThat(JoinType.RIGHT.keyword()).isEqualTo("RIGHT");
    }

    @Test
    @DisplayName("Join 渲染成完整的 JOIN 子句")
    void joinRenders() {
        assertThat(new HybridQuery.Join(JoinType.LEFT, "sku s", "s.id = c.sku_id").toSql())
                .isEqualTo("LEFT JOIN sku s ON s.id = c.sku_id");
    }

    @Test
    @DisplayName("硬上限数字来自 pgvector 实现，不是拍的")
    void limits() {
        assertThat(PgVectorLimits.MAX_DIMENSIONS).isEqualTo(16_000);
        assertThat(PgVectorLimits.MAX_INDEXABLE_DIMENSIONS).isEqualTo(2_000);
        assertThat(PgVectorLimits.IVFFLAT_MAX_LISTS).isEqualTo(32_768);
    }

    @Test
    @DisplayName("HybridQuery#toString 就是 SQL，方便打日志")
    void toStringIsSql() {
        HybridQuery q =
                HybridQuery.builder()
                        .from("sku s")
                        .nearest("s.embedding", new float[] {1f}, DistanceOperator.COSINE)
                        .build();
        assertThat(q.toString()).isEqualTo(q.sql());
    }
}
