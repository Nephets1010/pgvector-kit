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
 * pgvector 的三种距离算子。
 *
 * <p>这三个算子长得差不多、长得都像「排序」，但语义完全不同，用错了一个 SQL 都不会报错—— 它只是安静地返回一个不太对的顺序。这是本项目要挡住的头号坑，所以它和索引 opclass
 * 绑死在一起 （见 {@link #operatorClass()} 与 {@link OperatorIndexMismatchException}）。
 */
public enum DistanceOperator {

    /**
     * 欧氏距离 {@code <->}。值越小越近。
     *
     * <p>对向量的模长敏感：两个方向一致、长度差 10 倍的向量，L2 距离会很大但余弦距离为 0。 没做归一化的原始特征向量用它。
     */
    L2("<->", "vector_l2_ops", "欧氏距离，对模长敏感"),

    /**
     * 余弦距离 {@code <=>}，等于 {@code 1 - cos(θ)}。值越小越近。
     *
     * <p>只看方向不看长度。绝大多数文本 / 语义 embedding 的推荐选择。
     */
    COSINE("<=>", "vector_cosine_ops", "余弦距离，只看方向不看模长"),

    /**
     * 内积 {@code <#>}。
     *
     * <p><strong>注意：pgvector 返回的是「负内积」</strong>，所以 {@code ORDER BY ... ASC}
     * 得到的是「内积最大」的行，也就是最相似的——这恰好和另外两个算子一致， 但如果你按直觉写 {@code DESC}，得到的是最不相似的。
     *
     * <p>内积没有归一化，模长大的向量天然占优（这在某些推荐场景是想要的特性，在另一些场景是 bug）。
     */
    INNER_PRODUCT("<#>", "vector_ip_ops", "负内积：ASC 等价于「内积最大」，对模长敏感");

    private final String symbol;
    private final String operatorClass;
    private final String semantics;

    DistanceOperator(String symbol, String operatorClass, String semantics) {
        this.symbol = symbol;
        this.operatorClass = operatorClass;
        this.semantics = semantics;
    }

    /** SQL 里的算子符号，如 {@code <=>}。 */
    public String symbol() {
        return symbol;
    }

    /**
     * 建索引时必须配套使用的 opclass，如 {@code vector_cosine_ops}。
     *
     * <p>如果索引用的 opclass 与查询用的算子不是一对，PostgreSQL <strong>不会报错</strong>， 它只是不使用这个索引，安静地退化成全表扫描。
     */
    public String operatorClass() {
        return operatorClass;
    }

    /** 一句话说明这个算子在比什么。 */
    public String semantics() {
        return semantics;
    }

    /** 这个算子是否配得上给定的 opclass。 */
    public boolean matchesOperatorClass(String operatorClass) {
        return this.operatorClass.equals(operatorClass);
    }

    /**
     * 「最相似优先」的排序方向。
     *
     * <p>三个算子都是 {@link SortDirection#ASC}：L2 与余弦距离本身越小越近， 内积因为 pgvector 返回的是负值，ASC 同样是越相似越靠前。
     */
    public SortDirection mostSimilarFirst() {
        return SortDirection.ASC;
    }
}
