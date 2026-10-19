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
 * 库存业务行。
 *
 * <p>注意它<strong>不含向量字段</strong>：向量放在 {@code sku_embedding} 表里。 这不是洁癖，是因为 embedding
 * 模型会换、维度会变，把它们和业务主表绑在一张表上， 每次换模型都要改一次主表结构。
 *
 * @param skuCode 商品编码
 * @param name 商品名
 * @param warehouse 仓库
 * @param status 上架状态：ON_SALE / OFF_SHELF
 * @param stock 可用库存
 */
public record Sku(String skuCode, String name, String warehouse, String status, int stock) {}
