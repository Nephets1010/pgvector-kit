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

@DisplayName("索引建议：HNSW 还是 IVFFlat")
class IndexAdvisorTest {

    @Nested
    @DisplayName("选择依据")
    class Choice {

        @Test
        @DisplayName("千级数据选 HNSW：IVFFlat 的 k-means 样本不足")
        void smallCorpusChoosesHnsw() {
            IndexAdvice advice = IndexAdvisor.advise(request(5_000, 768, 0.95, false));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
            assertThat(advice.summary()).contains("HNSW");
            assertThat(String.join(" ", advice.reasons())).contains("k-means");
        }

        @Test
        @DisplayName("9999 行（临界下方）仍然选 HNSW")
        void justBelowThreshold() {
            assertThat(IndexAdvisor.advise(request(9_999, 768, 0.95, false)).indexType())
                    .isEqualTo(IndexType.HNSW);
        }

        @Test
        @DisplayName("10000 行且要高召回：选 HNSW（IVFFlat 的 probes 要开到接近 lists）")
        void largeCorpusHighRecallChoosesHnsw() {
            IndexAdvice advice = IndexAdvisor.advise(request(500_000, 768, 0.98, false));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
            assertThat(String.join(" ", advice.reasons())).contains("probes");
        }

        @Test
        @DisplayName("百万级 + 低维 + 内存紧张 + 召回要求不高：选 IVFFlat")
        void largeLowDimLowMemoryChoosesIvfflat() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.90, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.IVFFLAT);
            assertThat(String.join(" ", advice.reasons())).contains("内存");
        }

        @Test
        @DisplayName("同样百万级但内存不紧张：回落到 HNSW（默认偏 HNSW）")
        void largeButNotMemorySensitiveFallsBackToHnsw() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.90, false));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
        }

        @Test
        @DisplayName("百万级但维度 1536 > 768：IVFFlat 的倒排列表会把省下的内存吃回去，选 HNSW")
        void largeButHighDimensionChoosesHnsw() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 1536, 0.90, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
        }

        @Test
        @DisplayName("维度 3072：直接判定为建不出索引，并给出降维建议")
        void tooHighDimensionIsNotIndexable() {
            IndexAdvice advice = IndexAdvisor.advise(request(100_000, 3072, 0.95, false));
            assertThat(advice.indexable()).isFalse();
            assertThat(advice.indexType()).isNull();
            assertThat(advice.ddl()).isEmpty();
            assertThat(String.join(" ", advice.notes())).contains("降维");
            assertThat(advice.summary()).contains("建不出");
        }

        @Test
        @DisplayName("2000 维（可索引上限）仍然可以建")
        void maxIndexableDimensionIsIndexable() {
            assertThat(
                            IndexAdvisor.advise(
                                            request(
                                                    100_000,
                                                    PgVectorLimits.MAX_INDEXABLE_DIMENSIONS,
                                                    0.95,
                                                    false))
                                    .indexable())
                    .isTrue();
        }

        @Test
        @DisplayName("2001 维就建不出来了：边界必须清楚")
        void oneOverLimitIsNotIndexable() {
            assertThat(IndexAdvisor.advise(request(100_000, 2001, 0.95, false)).indexable())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("生成的 DDL")
    class Ddl {

        @Test
        @DisplayName("HNSW 的 DDL 含 USING hnsw 与正确的 opclass")
        void hnswDdl() {
            IndexAdvice advice = IndexAdvisor.advise(request(50_000, 768, 0.95, false));
            assertThat(advice.ddl())
                    .isEqualTo(
                            "CREATE INDEX idx_sku_embedding_embedding_vector_cosine_ops ON sku_embedding"
                                    + " USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);");
            assertThat(advice.parameters())
                    .containsEntry("m", 16)
                    .containsEntry("ef_construction", 64);
        }

        @Test
        @DisplayName("算子换成 L2，opclass 必须跟着变 —— 建错 opclass 等于白建")
        void opClassFollowsOperator() {
            IndexAdvice advice =
                    IndexAdvisor.advise(
                            new IndexRequest(
                                    50_000,
                                    768,
                                    0.95,
                                    DistanceOperator.L2,
                                    "sku_embedding",
                                    "embedding",
                                    null,
                                    false));
            assertThat(advice.ddl()).contains("vector_l2_ops");
            assertThat(advice.operator()).isEqualTo(DistanceOperator.L2);
        }

        @Test
        @DisplayName("IVFFlat 的 DDL 含 USING ivfflat 与 lists")
        void ivfflatDdl() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.90, true));
            assertThat(advice.ddl())
                    .startsWith("CREATE INDEX ")
                    .contains("USING ivfflat (embedding vector_cosine_ops) WITH (lists = ")
                    .endsWith(");");
            assertThat(advice.parameters()).containsKey("lists");
        }

        @Test
        @DisplayName("索引名可自定义，且会过标识符白名单")
        void customIndexName() {
            IndexAdvice advice =
                    IndexAdvisor.advise(
                            new IndexRequest(
                                    50_000,
                                    768,
                                    0.95,
                                    DistanceOperator.COSINE,
                                    "sku_embedding",
                                    "embedding",
                                    "idx_my_vec",
                                    false));
            assertThat(advice.ddl()).startsWith("CREATE INDEX idx_my_vec ON sku_embedding");
        }

        @Test
        @DisplayName("每条建议都要有理由，且提醒维度写死这件事")
        void alwaysExplains() {
            for (int dims : new int[] {8, 768, 1536}) {
                IndexAdvice advice = IndexAdvisor.advise(request(100_000, dims, 0.95, false));
                assertThat(advice.reasons()).isNotEmpty();
                assertThat(String.join(" ", advice.notes())).contains("vector(" + dims + ")");
            }
        }
    }

    @Nested
    @DisplayName("参数计算")
    class Parameters {

        @Test
        @DisplayName("lists：100 万行以内取 rows/1000")
        void listsBelowOneMillion() {
            assertThat(IndexAdvisor.listsFor(100_000)).isEqualTo(100);
            assertThat(IndexAdvisor.listsFor(1_000_000)).isEqualTo(1000);
        }

        @Test
        @DisplayName("lists：超过 100 万取 sqrt(rows)")
        void listsAboveOneMillion() {
            assertThat(IndexAdvisor.listsFor(4_000_000)).isEqualTo(2000);
        }

        @Test
        @DisplayName("lists：极小数据量不会算出 0（lists 至少为 1）")
        void listsNeverZero() {
            assertThat(IndexAdvisor.listsFor(10_000)).isEqualTo(10);
        }

        @Test
        @DisplayName("lists 有上限 32768")
        void listsCapped() {
            assertThat(IndexAdvisor.listsFor(Long.MAX_VALUE / 4))
                    .isEqualTo(PgVectorLimits.IVFFLAT_MAX_LISTS);
        }

        @Test
        @DisplayName("probes 从 sqrt(lists) 起步")
        void probesIsSqrtOfLists() {
            assertThat(IndexAdvisor.probesFor(100)).isEqualTo(10);
            assertThat(IndexAdvisor.probesFor(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("ef_search 随召回要求上升")
        void efSearchScalesWithRecall() {
            assertThat(IndexAdvisor.efSearchFor(0.80)).isEqualTo(40);
            assertThat(IndexAdvisor.efSearchFor(0.90)).isEqualTo(64);
            assertThat(IndexAdvisor.efSearchFor(0.95)).isEqualTo(100);
            assertThat(IndexAdvisor.efSearchFor(0.99)).isEqualTo(200);
        }

        @Test
        @DisplayName("HNSW 给出 SET hnsw.ef_search，IVFFlat 给出 SET ivfflat.probes")
        void searchSettingsMatchIndexType() {
            assertThat(IndexAdvisor.advise(request(50_000, 768, 0.95, false)).searchSettings())
                    .startsWith("SET hnsw.ef_search = ");
            assertThat(IndexAdvisor.advise(request(2_000_000, 768, 0.90, true)).searchSettings())
                    .startsWith("SET ivfflat.probes = ");
        }

        @Test
        @DisplayName("IVFFlat 的建议里必须提醒「先灌数据再建索引」")
        void ivfflatWarnsAboutBuildOrder() {
            assertThat(
                            String.join(
                                    " ",
                                    IndexAdvisor.advise(request(2_000_000, 768, 0.90, true))
                                            .notes()))
                    .contains("先灌数据");
        }
    }

    @Nested
    @DisplayName("入参校验")
    class RequestValidation {

        @Test
        @DisplayName("行数必须为正")
        void rejectsNonPositiveRows() {
            assertThatThrownBy(() -> request(0, 768, 0.95, false))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("行数");
        }

        @Test
        @DisplayName("维度超过 16000 报错")
        void rejectsTooManyDimensions() {
            assertThatThrownBy(() -> request(100, PgVectorLimits.MAX_DIMENSIONS + 1, 0.95, false))
                    .isInstanceOf(VectorFormatException.class);
        }

        @Test
        @DisplayName("召回率必须落在 (0,1]")
        void rejectsBadRecall() {
            assertThatThrownBy(() -> request(100, 8, 0, false))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("召回率");
            assertThatThrownBy(() -> request(100, 8, 1.5, false))
                    .isInstanceOf(VectorFormatException.class)
                    .hasMessageContaining("召回率");
        }

        @Test
        @DisplayName("表名要过标识符白名单")
        void rejectsBadTableName() {
            assertThatThrownBy(
                            () ->
                                    new IndexRequest(
                                            100,
                                            8,
                                            0.95,
                                            DistanceOperator.COSINE,
                                            "sku embedding",
                                            "embedding",
                                            null,
                                            false))
                    .isInstanceOf(InvalidSqlIdentifierException.class);
        }

        @Test
        @DisplayName("简写入口 IndexRequest.of 用默认 0.95 召回")
        void shortcutUsesDefaultRecall() {
            IndexRequest r =
                    IndexRequest.of(
                            100_000, 768, DistanceOperator.COSINE, "sku_embedding", "embedding");
            assertThat(r.targetRecall()).isEqualTo(0.95);
            assertThat(r.indexName()).isEqualTo("idx_sku_embedding_embedding_vector_cosine_ops");
        }
    }

    private static IndexRequest request(
            long rows, int dimensions, double recall, boolean lowMemory) {
        return new IndexRequest(
                rows,
                dimensions,
                recall,
                DistanceOperator.COSINE,
                "sku_embedding",
                "embedding",
                null,
                lowMemory);
    }
}
