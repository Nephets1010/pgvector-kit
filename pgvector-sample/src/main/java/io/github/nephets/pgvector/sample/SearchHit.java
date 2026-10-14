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

/**
 * 一条混合查询结果：业务列 + 距离，来自同一行、同一条 SQL。
 *
 * @param skuCode 商品编码
 * @param name 商品名
 * @param warehouse 仓库
 * @param stock 可用库存
 * @param distance 距离（越小越相似；内积算子下是负内积，越小 = 内积越大）
 */
public record SearchHit(String skuCode, String name, String warehouse, int stock, double distance) {

    @Override
    public String toString() {
        return String.format(
                "%-10s %-16s %-6s 库存=%-5d distance=%.6f",
                skuCode, name, warehouse, stock, distance);
    }
}
