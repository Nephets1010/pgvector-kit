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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HNSW 与 IVFFlat 的选择建议。
 *
 * <p>这两者的取舍没有「哪个更好」的答案，只有「在你的数据量、维度、召回要求下哪个更合适」。 这里把判断规则写成<strong>可读的常量 +
 * 逐条追加的理由</strong>，而不是一个黑箱打分函数： 阈值不合理时你能直接看到是哪个数字不合理，改起来也只是改一个常量。
 *
 * <h2>规则（按顺序判定，命中即返回）</h2>
 *
 * <ol>
 *   <li><strong>维度 &gt; 2000</strong> → 建不出索引。pgvector 的 HNSW / IVFFlat 都只能索引 ≤2000 维，
 *       这是实现限制，不是参数问题。
 *   <li><strong>行数 &lt; 1 万</strong> → HNSW。IVFFlat 靠 k-means 把数据聚成 {@code lists} 个簇，
 *       样本不足时簇心没有代表性，召回会随数据分布剧烈波动；HNSW 对数据量不敏感，还能在空表上先建。
 *   <li><strong>目标召回 ≥ 0.95</strong> → HNSW。IVFFlat 要把召回推到 0.95 以上， {@code probes} 必须开到接近 {@code
 *       lists}，那时它扫的簇已经接近全表，优势就没了。
 *   <li><strong>行数 ≥ 100 万 且 维度 ≤ 768 且 内存紧张</strong> → IVFFlat。 这是 IVFFlat 唯一明显划算的区间：常驻内存约为 HNSW
 *       的三分之一到五分之一，重建也快得多。
 *   <li><strong>其余</strong> → HNSW。默认偏向 HNSW，因为它的延迟-召回曲线对参数不敏感， 而 IVFFlat 的召回是 {@code probes}
 *       的函数，调参成本和踩坑概率都更高。
 * </ol>
 */
public final class IndexAdvisor {

    private static final Logger log = LoggerFactory.getLogger(IndexAdvisor.class);

    /** IVFFlat 需要的最小样本量：低于这个量级，k-means 的簇心没有代表性。 */
    static final long IVFFLAT_MIN_ROWS = 10_000L;

    /** 超过这个召回要求，IVFFlat 的 probes 就得开到接近 lists，优势消失。 */
    static final double HIGH_RECALL_THRESHOLD = 0.95;

    /** 只有到了这个量级，IVFFlat 省下的内存才值得它带来的调参成本。 */
    static final long LARGE_CORPUS_ROWS = 1_000_000L;

    /** IVFFlat 在这个维度以下才划算：高维时倒排列表本身就会把内存吃回去。 */
    static final int IVFFLAT_LOW_DIMENSION_LIMIT = 768;

    private IndexAdvisor() {}

    /**
     * 给出索引建议。
     *
     * @param request 输入（数据量、维度、召回要求、算子、表列名）
     * @return 建议，含可以直接执行的 DDL
     */
    public static IndexAdvice advise(IndexRequest request) {
        // ★ 入口就拒 null：request 的每个字段下一秒都会被读进日志与 DDL，
        //   放它过去只会在 request.indexName() 处抛一个没有消息的 NPE，看不出是谁传空了。
        Objects.requireNonNull(request, "IndexRequest 不能为 null");
        log.info(
                "advise indexName={} table={} column={} dimensions={} rows={} targetRecall={}"
                        + " operator={} preferLowMemory={}",
                request.indexName(),
                request.table(),
                request.column(),
                request.dimensions(),
                request.rows(),
                request.targetRecall(),
                request.operator(),
                request.preferLowMemory());

        List<String> reasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        DistanceOperator operator = request.operator();

        if (request.dimensions() > PgVectorLimits.MAX_INDEXABLE_DIMENSIONS) {
            log.info(
                    "advise: dimensions {} exceed pgvector indexable limit {}, cannot build ANN index",
                    request.dimensions(),
                    PgVectorLimits.MAX_INDEXABLE_DIMENSIONS);
            reasons.add(
                    "维度 "
                            + request.dimensions()
                            + " 超过 pgvector 可索引的上限 "
                            + PgVectorLimits.MAX_INDEXABLE_DIMENSIONS
                            + " 维（HNSW 与 IVFFlat 都是这个限制）");
            reasons.add("这个列仍然可以存、可以查，但只能全表扫描，任何 ANN 索引都建不出来");
            notes.add(
                    "请先把维度降到 "
                            + PgVectorLimits.MAX_INDEXABLE_DIMENSIONS
                            + " 以内（换小输出的 embedding 模型，或做一次降维）");
            notes.add("或者只在低维子集上建索引、把高维向量另表存放，用标量条件先过滤再查");
            return new IndexAdvice(
                    request.indexName(), false, null, operator, "", Map.of(), "", reasons, notes);
        }

        IndexType type = chooseType(request, reasons);

        Map<String, Integer> parameters = new LinkedHashMap<>();
        String searchSettings;
        if (type == IndexType.HNSW) {
            // pgvector 的默认值，也是社区最常用的一组：m 控制每层邻居数，ef_construction 控制构建质量。
            parameters.put("m", 16);
            parameters.put("ef_construction", 64);
            int efSearch = efSearchFor(request.targetRecall());
            parameters.put("ef_search", efSearch);
            searchSettings = "SET hnsw.ef_search = " + efSearch + ";";
            notes.add(
                    "hnsw.ef_search 是会话级参数，建议写在连接池的连接初始化 SQL 里，而不是每次查询前 SET"
                            + "（也可以 SET LOCAL 放在事务里）");
            notes.add("ef_search 越大召回越高、延迟越高；上面给的是经验起点，请用你自己的数据实测再定");
        } else {
            int lists = listsFor(request.rows());
            parameters.put("lists", lists);
            int probes = probesFor(lists);
            parameters.put("probes", probes);
            searchSettings = "SET ivfflat.probes = " + probes + ";";
            reasons.add("probes 建议从 " + probes + " 起（pgvector 官方建议先取 sqrt(lists)），再按实测召回调整");
            notes.add("IVFFlat 必须在【已有代表性的数据】之后再建：先灌数据，再 CREATE INDEX");
            notes.add("数据分布漂移（比如集中上了大批新品）后要 REINDEX，否则簇心失效、召回会掉");
            notes.add("ivfflat.probes 同样是会话级参数；probes 开到接近 lists 时召回接近 1，但已经等价于全表扫描");
        }

        notes.add(
                "vector("
                        + request.dimensions()
                        + ") 的维度写进了列类型：以后换 embedding 模型要先改列类型再重建索引，不能靠改代码蒙混过去");
        notes.add("生产环境建索引建议加 CONCURRENTLY（注意它不能在事务块里执行）");

        String ddl =
                "CREATE INDEX "
                        + request.indexName()
                        + " ON "
                        + request.table()
                        + " USING "
                        + type.keyword()
                        + " ("
                        + request.column()
                        + " "
                        + operator.operatorClass()
                        + ")"
                        + withClause(parameters, type)
                        + ";";

        log.info(
                "advise result indexable=true indexType={} searchSettings={}",
                type,
                searchSettings);
        return new IndexAdvice(
                request.indexName(),
                true,
                type,
                operator,
                ddl,
                parameters,
                searchSettings,
                reasons,
                notes);
    }

    private static IndexType chooseType(IndexRequest request, List<String> reasons) {
        if (request.rows() < IVFFLAT_MIN_ROWS) {
            reasons.add(
                    "数据量 "
                            + request.rows()
                            + " 行 < "
                            + IVFFLAT_MIN_ROWS
                            + " 行：IVFFlat 靠 k-means 聚类，样本不足时簇心没有代表性，召回会随数据分布剧烈波动");
            reasons.add("HNSW 可以在空表上先建索引，之后增量插入无需重建，小规模数据下几乎不用调参");
            return IndexType.HNSW;
        }

        if (request.targetRecall() >= HIGH_RECALL_THRESHOLD) {
            reasons.add(
                    "目标召回 "
                            + request.targetRecall()
                            + " ≥ "
                            + HIGH_RECALL_THRESHOLD
                            + "：IVFFlat 要把召回推到这个水平，probes 必须开到接近 lists，扫的簇已接近全表，省下的内存换不来延迟优势");
            reasons.add("HNSW 的召回-延迟曲线更平缓，高召回区间是它的主场");
            return IndexType.HNSW;
        }

        if (request.rows() >= LARGE_CORPUS_ROWS
                && request.dimensions() <= IVFFLAT_LOW_DIMENSION_LIMIT
                && request.preferLowMemory()) {
            reasons.add(
                    "数据量 "
                            + request.rows()
                            + " 行 ≥ "
                            + LARGE_CORPUS_ROWS
                            + " 行且维度 "
                            + request.dimensions()
                            + " ≤ "
                            + IVFFLAT_LOW_DIMENSION_LIMIT
                            + "，且你声明内存比延迟更紧张");
            reasons.add("IVFFlat 只存倒排列表，常驻内存约为 HNSW 的三分之一到五分之一，重建也快得多");
            return IndexType.IVFFLAT;
        }

        reasons.add(
                "数据量 "
                        + request.rows()
                        + " 行、维度 "
                        + request.dimensions()
                        + "、目标召回 "
                        + request.targetRecall()
                        + "：没有命中任何「偏向 IVFFlat」的条件");
        reasons.add("默认选 HNSW：它的延迟-召回曲线对参数不敏感，而 IVFFlat 的召回是 probes 的函数，调参成本更高");
        return IndexType.HNSW;
    }

    /** IVFFlat 的 {@code lists}：pgvector 官方给的经验值是「100 万行以内取 rows/1000，超过取 sqrt(rows)」。 */
    static int listsFor(long rows) {
        long raw = rows <= 1_000_000L ? rows / 1_000L : (long) Math.sqrt(rows);
        return (int) Math.max(1L, Math.min(PgVectorLimits.IVFFLAT_MAX_LISTS, raw));
    }

    /** IVFFlat 的 {@code probes}：官方建议从 {@code sqrt(lists)} 起步。 */
    static int probesFor(int lists) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(lists)));
    }

    /** HNSW 的 {@code ef_search}：pgvector 默认 40，按召回要求往上抬。经验值，需实测校准。 */
    static int efSearchFor(double targetRecall) {
        if (targetRecall >= 0.99) {
            return 200;
        }
        if (targetRecall >= 0.95) {
            return 100;
        }
        if (targetRecall >= 0.90) {
            return 64;
        }
        return 40;
    }

    private static String withClause(Map<String, Integer> parameters, IndexType type) {
        if (type == IndexType.HNSW) {
            return " WITH (m = "
                    + parameters.get("m")
                    + ", ef_construction = "
                    + parameters.get("ef_construction")
                    + ")";
        }
        return " WITH (lists = " + parameters.get("lists") + ")";
    }
}
