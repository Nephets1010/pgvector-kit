-- pgvector-kit sample 的建表脚本。
--
-- Spring Boot 启动时会自动执行（spring.sql.init.mode=always），
-- 集成测试也直接复用这个文件（ResourceDatabasePopulator），避免「两份 schema 各自漂移」。

CREATE EXTENSION IF NOT EXISTS vector;

-- 业务表：库存主数据
CREATE TABLE IF NOT EXISTS sku (
    id          BIGSERIAL PRIMARY KEY,
    sku_code    VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    warehouse   VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    stock       INT          NOT NULL
);

-- 向量表：与业务表分开，换 embedding 模型时不用动主表
CREATE TABLE IF NOT EXISTS sku_embedding (
    sku_id      BIGINT PRIMARY KEY REFERENCES sku (id) ON DELETE CASCADE,
    embedding   VECTOR(8) NOT NULL
);

-- 余弦索引。查询必须用 <=>，用 <-> 或 <#> 的话这个索引会被静默跳过。
CREATE INDEX IF NOT EXISTS idx_sku_embedding_cosine
    ON sku_embedding USING hnsw (embedding vector_cosine_ops);
