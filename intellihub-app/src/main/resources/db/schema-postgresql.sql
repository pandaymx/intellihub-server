-- =============================================================================
-- IntelliHub PostgreSQL + pgvector 生产级持久化表结构与索引脚本
-- 适用版本：PostgreSQL 16+ / 18+ (含 pgvector 插件)
-- =============================================================================

-- 1. 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. RAG 混合检索知识切片表（Vector + Full-Text Search + 多租户 JSONB 过滤）
CREATE TABLE IF NOT EXISTS intellihub_kb_chunks (
    id VARCHAR(255) PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    fts_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 多租户与知识库元数据 GIN 索引（加速 metadata @> '{"tenant_id":"..."}'）
CREATE INDEX IF NOT EXISTS idx_intellihub_kb_chunks_meta_gin
    ON intellihub_kb_chunks USING GIN (metadata jsonb_path_ops);

-- 词法全文检索 GIN 倒排索引（加速 fts_vector @@ to_tsquery(...)）
CREATE INDEX IF NOT EXISTS idx_intellihub_kb_chunks_fts_gin
    ON intellihub_kb_chunks USING GIN (fts_vector);

-- 768 维稠密向量 HNSW 余弦距离索引（加速 ORDER BY embedding <=> query_vec）
CREATE INDEX IF NOT EXISTS idx_intellihub_kb_chunks_vec_hnsw
    ON intellihub_kb_chunks USING hnsw (embedding vector_cosine_ops);

-- 3. ReAct Agent 异步人工审批挂起快照表（AWAITING_APPROVAL 跨进程/重启恢复）
CREATE TABLE IF NOT EXISTS intellihub_agent_checkpoints (
    checkpoint_id VARCHAR(128) PRIMARY KEY,
    approval_ticket_id VARCHAR(255),
    suspended_iteration INT NOT NULL,
    payload_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. 多轮对话会话上下文记忆表（Session Memory Store）
CREATE TABLE IF NOT EXISTS intellihub_session_memory (
    session_id VARCHAR(128) PRIMARY KEY,
    messages_json TEXT NOT NULL,
    message_count INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
