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

/**
 * pgvector 自身的硬上限。
 *
 * <p>这些数字不是本项目拍的，是 pgvector 的实现限制。把它们做成常量而不是散落在代码里的魔数， 是为了让「维度写死」这个坑在编译期就能被 grep
 * 到，也为了让报错信息能给出具体的数字。
 */
public final class PgVectorLimits {

    /** pgvector 的 {@code vector} 类型最多容纳的分量数。 */
    public static final int MAX_DIMENSIONS = 16_000;

    /**
     * pgvector 的 HNSW / IVFFlat 索引能索引的最大维度。
     *
     * <p>超过这个维度的列依然可以建、可以查（全表扫描），但建不出 ANN 索引。 这是很多人上线后才发现的事：SQL 能跑、结果也对，只是慢。所以这里必须 fail-fast。
     */
    public static final int MAX_INDEXABLE_DIMENSIONS = 2_000;

    /** IVFFlat 的 {@code lists} 上限（pgvector 源码里的限制）。 */
    public static final int IVFFLAT_MAX_LISTS = 32_768;

    private PgVectorLimits() {}
}
