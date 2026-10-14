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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引建议的结果。
 *
 * <p>每一条建议都带 {@code reasons}——把「为什么」和「怎么做」一起给出来， 是因为只有结论的建议在评审时会被质疑、上线后也没人知道当初的前提是什么。
 *
 * @param indexName 建议的索引名（DDL 里用的就是它，便于调用方按需改名）
 * @param indexable 这个维度能不能建 ANN 索引；false 时 {@code ddl} 为空
 * @param indexType 建议的索引类型；不可索引时为 null
 * @param operator 建议配套的算子（决定 opclass）
 * @param ddl 可以直接执行的建索引 SQL；不可索引时为空字符串
 * @param parameters 建索引参数（HNSW 的 m / ef_construction，IVFFlat 的 lists）
 * @param searchSettings 建议的会话级查询参数（ef_search / probes），为空字符串表示用默认值
 * @param reasons 选择这个索引的理由，至少一条
 * @param notes 上线前后要注意的事
 */
public record IndexAdvice(
        String indexName,
        boolean indexable,
        IndexType indexType,
        DistanceOperator operator,
        String ddl,
        Map<String, Integer> parameters,
        String searchSettings,
        List<String> reasons,
        List<String> notes) {

    public IndexAdvice {
        indexName = indexName == null ? "" : indexName;
        ddl = ddl == null ? "" : ddl;
        searchSettings = searchSettings == null ? "" : searchSettings;
        parameters =
                parameters == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 一行人话总结，适合直接打进日志或贴到评审文档里。 */
    public String summary() {
        if (!indexable) {
            return "这个维度建不出 ANN 索引：" + String.join("；", reasons);
        }
        return "建议 "
                + indexType.keyword().toUpperCase()
                + " + "
                + operator.operatorClass()
                + "："
                + String.join("；", reasons);
    }
}
