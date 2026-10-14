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

import java.util.Objects;

/**
 * 索引建议的输入。
 *
 * @param rows 表里（预计）有多少行向量
 * @param dimensions 向量维度
 * @param targetRecall 你能接受的召回率，0~1
 * @param operator 你打算用哪个距离算子查询——它决定 opclass，而 opclass 一旦建错就要重建索引
 * @param table 表名
 * @param column 列名
 * @param indexName 想要的索引名，留空则自动生成
 * @param preferLowMemory 内存是否比延迟更紧张（IVFFlat 的常驻内存显著小于 HNSW）
 */
public record IndexRequest(
        long rows,
        int dimensions,
        double targetRecall,
        DistanceOperator operator,
        String table,
        String column,
        String indexName,
        boolean preferLowMemory) {

    public IndexRequest {
        if (rows <= 0) {
            throw new VectorFormatException("行数必须为正数，实际为 " + rows);
        }
        if (dimensions <= 0) {
            throw new VectorFormatException("维度必须为正数，实际为 " + dimensions);
        }
        if (dimensions > PgVectorLimits.MAX_DIMENSIONS) {
            throw new VectorFormatException(
                    "维度 " + dimensions + " 超过 pgvector 上限 " + PgVectorLimits.MAX_DIMENSIONS + " 维");
        }
        if (targetRecall <= 0 || targetRecall > 1) {
            throw new VectorFormatException("召回率必须落在 (0, 1] 区间，实际为 " + targetRecall);
        }
        operator = Objects.requireNonNull(operator, "距离算子不能为 null");
        table = SqlIdentifier.requireIdentifier(table, "表名");
        column = SqlIdentifier.requireIdentifier(column, "列名");
        indexName =
                (indexName == null || indexName.isBlank())
                        ? ("idx_" + table + "_" + column + "_" + operator.operatorClass())
                        : SqlIdentifier.requireIdentifier(indexName, "索引名");
    }

    /** 只关心规模与算子时的简写入口。 */
    public static IndexRequest of(
            long rows, int dimensions, DistanceOperator operator, String table, String column) {
        return new IndexRequest(rows, dimensions, 0.95, operator, table, column, null, false);
    }
}
