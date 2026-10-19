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

/**
 * 索引建议器的<strong>边界值</strong>与参数计算：{@code IndexAdvisorTest} 覆盖主干规则， 这个文件把每个阈值的等号点、ef_search
 * 的每一档、IVFFlat 的提醒事项逐条钉住。
 *
 * <p>阈值判断差一个等号，线上就是「10 万行的表建了 IVFFlat 召回剧烈波动」这类事故， 所以边界必须显式有用例，不能只测「大概在这个区间」。
 */
@DisplayName("索引建议器：边界与参数档位")
class IndexAdvisorExtendedTest {

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

    @Nested
    @DisplayName("选择规则的等号边界")
    class Boundaries {

        @Test
        @DisplayName("行数正好 10000（IVFFlat 最小样本量的等号点）：仍选 HNSW，规则是「小于」才拦")
        void exactlyAtIvfflatMinRows() {
            IndexAdvice advice = IndexAdvisor.advise(request(10_000, 768, 0.90, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
        }

        @Test
        @DisplayName("行数正好 100 万（大语料的等号点）+ 低维 + 内存紧张 + 召回不高：选 IVFFlat")
        void exactlyAtLargeCorpusRows() {
            IndexAdvice advice = IndexAdvisor.advise(request(1_000_000, 768, 0.90, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.IVFFLAT);
            assertThat(String.join(" ", advice.reasons())).contains("1000000 行 ≥ 1000000 行");
        }

        @Test
        @DisplayName("召回正好 0.95（高召回阈值的等号点）：选 HNSW，规则是「大于等于」就拦")
        void exactlyAtHighRecallThreshold() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.95, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
            assertThat(String.join(" ", advice.reasons())).contains("0.95");
        }

        @Test
        @DisplayName("维度正好 768（IVFFlat 划算维度的等号点）：有资格选 IVFFlat")
        void exactlyAtLowDimensionLimit() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.90, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.IVFFLAT);
        }

        @Test
        @DisplayName("维度 769 高出一格：倒排列表把内存吃回去，回落到 HNSW")
        void oneOverLowDimensionLimit() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 769, 0.90, true));
            assertThat(advice.indexType()).isEqualTo(IndexType.HNSW);
            assertThat(String.join(" ", advice.reasons())).contains("没有命中任何");
        }
    }

    @Nested
    @DisplayName("lists / probes 参数计算")
    class ListAndProbe {

        @Test
        @DisplayName("lists：100 万的等号点仍走 rows/1000")
        void listsAtOneMillion() {
            assertThat(IndexAdvisor.listsFor(1_000_000)).isEqualTo(1000);
        }

        @Test
        @DisplayName("lists：刚过 100 万换 sqrt 公式（1000001 的 sqrt 仍是 1000）")
        void listsJustAboveOneMillion() {
            assertThat(IndexAdvisor.listsFor(1_000_001)).isEqualTo(1000);
        }

        @Test
        @DisplayName("lists：远小于 1000 行的病态输入也不会算出 0")
        void listsNeverBelowOne() {
            assertThat(IndexAdvisor.listsFor(1)).isEqualTo(1);
            assertThat(IndexAdvisor.listsFor(999)).isEqualTo(1);
        }

        @Test
        @DisplayName("lists：天文数字被 32768 上限截住（pgvector 源码限制）")
        void listsCappedAtPgvectorLimit() {
            assertThat(IndexAdvisor.listsFor(Long.MAX_VALUE / 4))
                    .isEqualTo(PgVectorLimits.IVFFLAT_MAX_LISTS);
        }

        @Test
        @DisplayName("probes：0 与 1 都至少为 1（pgvector 不接受 probes=0）")
        void probesAtLeastOne() {
            assertThat(IndexAdvisor.probesFor(0)).isEqualTo(1);
            assertThat(IndexAdvisor.probesFor(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("probes：非完全平方数向上取整（sqrt(2) 要进成 2，不能舍成 1）")
        void probesRoundsUp() {
            assertThat(IndexAdvisor.probesFor(2)).isEqualTo(2);
            assertThat(IndexAdvisor.probesFor(99)).isEqualTo(10);
        }

        @Test
        @DisplayName("probes：完全平方数不开根不差一")
        void probesExactSqrt() {
            assertThat(IndexAdvisor.probesFor(10_000)).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("ef_search 的每一档")
    class EfSearch {

        @Test
        @DisplayName("四档边界值：0.90 / 0.95 / 0.99 是升档点，其余落下一档")
        void everyTier() {
            assertThat(IndexAdvisor.efSearchFor(0.50)).isEqualTo(40);
            assertThat(IndexAdvisor.efSearchFor(0.8999)).isEqualTo(40);
            assertThat(IndexAdvisor.efSearchFor(0.90)).isEqualTo(64);
            assertThat(IndexAdvisor.efSearchFor(0.9499)).isEqualTo(64);
            assertThat(IndexAdvisor.efSearchFor(0.95)).isEqualTo(100);
            assertThat(IndexAdvisor.efSearchFor(0.9899)).isEqualTo(100);
            assertThat(IndexAdvisor.efSearchFor(0.99)).isEqualTo(200);
            assertThat(IndexAdvisor.efSearchFor(1.00)).isEqualTo(200);
        }

        @Test
        @DisplayName("ef_search 单调不减：召回要求越高，搜索宽度只能越大")
        void efSearchIsMonotonic() {
            double[] recalls = {0.5, 0.8, 0.9, 0.95, 0.99, 1.0};
            int previous = 0;
            for (double recall : recalls) {
                int current = IndexAdvisor.efSearchFor(recall);
                assertThat(current).isGreaterThanOrEqualTo(previous);
                previous = current;
            }
        }
    }

    @Nested
    @DisplayName("建议里的提醒事项")
    class Notes {

        @Test
        @DisplayName("HNSW 建议：会话级 ef_search 的用法与 CONCURRENTLY 提醒都在")
        void hnswNotes() {
            IndexAdvice advice = IndexAdvisor.advise(request(50_000, 768, 0.95, false));
            assertThat(advice.searchSettings()).isEqualTo("SET hnsw.ef_search = 100;");
            assertThat(advice.parameters())
                    .containsEntry("m", 16)
                    .containsEntry("ef_construction", 64)
                    .containsEntry("ef_search", 100);
            String notes = String.join(" ", advice.notes());
            assertThat(notes).contains("ef_search 是会话级参数").contains("CONCURRENTLY");
        }

        @Test
        @DisplayName("IVFFlat 建议：先灌数据再建索引、漂移后 REINDEX、probes 上限提醒都在")
        void ivfflatNotes() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.90, true));
            // 200 万行走 sqrt 公式：lists = sqrt(2000000) = 1414，probes = ceil(sqrt(1414)) = 38
            assertThat(advice.searchSettings()).isEqualTo("SET ivfflat.probes = 38;");
            assertThat(advice.parameters())
                    .containsEntry("lists", 1414)
                    .containsEntry("probes", 38);
            String notes = String.join(" ", advice.notes());
            assertThat(notes).contains("先灌数据").contains("REINDEX").contains("CONCURRENTLY");
            String reasons = String.join(" ", advice.reasons());
            assertThat(reasons).contains("probes 建议从 38 起");
        }

        @Test
        @DisplayName("IVFFlat 的 DDL：lists 与 200 万行对得上（sqrt 公式），WITH 子句只有 lists")
        void ivfflatDdlForTwoMillionRows() {
            IndexAdvice advice = IndexAdvisor.advise(request(2_000_000, 768, 0.90, true));
            assertThat(advice.ddl())
                    .isEqualTo(
                            "CREATE INDEX idx_sku_embedding_embedding_vector_cosine_ops"
                                    + " ON sku_embedding USING ivfflat"
                                    + " (embedding vector_cosine_ops) WITH (lists = 1414);");
        }

        @Test
        @DisplayName("任何可索引的建议，summary 都是「建议 <TYPE> + <opclass>：<理由>」")
        void summaryNamesTypeAndOpClass() {
            assertThat(IndexAdvisor.advise(request(50_000, 768, 0.95, false)).summary())
                    .startsWith("建议 HNSW + vector_cosine_ops：");
            assertThat(IndexAdvisor.advise(request(2_000_000, 768, 0.90, true)).summary())
                    .startsWith("建议 IVFFLAT + vector_cosine_ops：");
        }
    }

    @Nested
    @DisplayName("入口与产物的健壮性")
    class Robustness {

        @Test
        @DisplayName("advise(null)：带消息的 NPE，而不是在 request.indexName() 处裸奔")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> IndexAdvisor.advise(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("IndexRequest 不能为 null");
        }

        @Test
        @DisplayName("合法输入组合扫一遍：要么给出可执行 DDL，要么明确说建不出，不允许半成品")
        void neverReturnsHalfBakedAdvice() {
            long[] rowsValues = {1, 9_999, 10_000, 500_000, 1_000_000, 2_000_000};
            int[] dimsValues = {8, 768, 1536, 2000};
            double[] recallValues = {0.5, 0.90, 0.95, 1.0};
            for (long rows : rowsValues) {
                for (int dims : dimsValues) {
                    for (double recall : recallValues) {
                        IndexAdvice advice = IndexAdvisor.advise(request(rows, dims, recall, true));
                        assertThat(advice.reasons())
                                .as("rows=%d dims=%d recall=%s", rows, dims, recall)
                                .isNotEmpty();
                        if (advice.indexable()) {
                            assertThat(advice.ddl())
                                    .as("rows=%d dims=%d recall=%s", rows, dims, recall)
                                    .startsWith("CREATE INDEX ")
                                    .endsWith(";");
                        } else {
                            assertThat(advice.ddl()).isEmpty();
                            assertThat(advice.indexType()).isNull();
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("IndexAdvice 全 null 构造：所有可空字段退化成安全默认，summary 不炸")
        void allNullAdviceIsSafe() {
            IndexAdvice advice =
                    new IndexAdvice(null, true, null, null, null, null, null, null, null);
            assertThat(advice.indexName()).isEmpty();
            assertThat(advice.ddl()).isEmpty();
            assertThat(advice.searchSettings()).isEmpty();
            assertThat(advice.parameters()).isEmpty();
            assertThat(advice.reasons()).isEmpty();
            assertThat(advice.notes()).isEmpty();
            assertThat(advice.summary()).isNotNull();
        }

        @Test
        @DisplayName("不可索引时 summary 明说「建不出 ANN 索引」")
        void notIndexableSummary() {
            IndexAdvice advice = IndexAdvisor.advise(request(100_000, 3072, 0.95, false));
            assertThat(advice.summary()).startsWith("这个维度建不出 ANN 索引：");
        }

        @Test
        @DisplayName("reasons 与 parameters 都不可修改：调用方改不了建议内容")
        void adviceIsImmutable() {
            IndexAdvice advice = IndexAdvisor.advise(request(50_000, 768, 0.95, false));
            assertThatThrownBy(() -> advice.reasons().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> advice.parameters().put("m", 32))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
