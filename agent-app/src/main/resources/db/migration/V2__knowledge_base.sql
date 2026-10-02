-- M2 知识库元数据表
-- 为什么文本存 MySQL、向量存 Milvus 而不全放一边：
-- MySQL 管"文档与分块的业务元数据"（可事务、可关联、可全文检索），
-- Milvus 管"向量与相似度检索"（HNSW 索引是它的专长）。
-- 关联设计：Milvus 主键 = knowledge_chunk.id（MySQL 自增主键），
-- 两侧天然对齐，检索命中向量后凭主键直接定位分块，无需冗余映射字段。

CREATE TABLE knowledge_doc (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(20) NOT NULL DEFAULT 'text',
    chunk_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE knowledge_chunk (
    -- 自增主键同时是 Milvus 侧向量主键：写入顺序 = 先 MySQL 拿 id，再带 id 写 Milvus
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id VARCHAR(36) NOT NULL,
    -- 分块在文档内的顺序：引用溯源时按序展示
    seq INT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_doc_seq (doc_id, seq),
    INDEX idx_doc (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
