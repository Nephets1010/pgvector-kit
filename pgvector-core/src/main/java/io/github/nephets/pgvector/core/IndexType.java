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

/** pgvector 支持的两种 ANN 索引。 */
public enum IndexType {

    /**
     * HNSW：多层近邻图。
     *
     * <p>构建慢、常驻内存大，但查询的延迟-召回曲线更稳，对参数不敏感， 并且可以在空表上先建索引、之后增量插入无需重建。
     */
    HNSW("hnsw"),

    /**
     * IVFFlat：倒排文件 + 倒排列表。
     *
     * <p>构建快、内存占用约为 HNSW 的三分之一到五分之一，但召回率强依赖 {@code probes}， 且必须在有代表性的数据上建（先灌数据再 CREATE
     * INDEX），数据分布漂移后需要重建。
     */
    IVFFLAT("ivfflat");

    private final String keyword;

    IndexType(String keyword) {
        this.keyword = keyword;
    }

    /** {@code CREATE INDEX ... USING} 后面的关键字。 */
    public String keyword() {
        return keyword;
    }
}
