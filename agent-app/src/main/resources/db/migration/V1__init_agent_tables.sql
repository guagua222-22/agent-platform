-- M1 初始化迁移：Agent 运行记录与事件存档
-- 为什么用 Flyway 而不是手写建表脚本：数据库结构随里程碑演进（M2 会话表、M3 审计表），
-- 版本化迁移保证"任何环境都能从零重建到当前结构"，是生产级数据库变更的基线实践。

CREATE TABLE agent_run (
    -- UUID 主键：运行记录由 Runtime 生成 ID，客户端/服务端都能幂等引用
    id VARCHAR(36) PRIMARY KEY,
    agent_name VARCHAR(100) NOT NULL,
    input TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    final_answer TEXT NULL,
    error TEXT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_agent_name (agent_name),
    INDEX idx_started_at (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    -- 事件在单次运行内的顺序号：Checkpoint 恢复与事件重放的锚点
    seq INT NOT NULL,
    type VARCHAR(30) NOT NULL,
    detail VARCHAR(1000) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    -- (run_id, seq) 唯一：同一运行同一顺序号只允许一条事件，防重复落库
    UNIQUE KEY uk_run_seq (run_id, seq),
    INDEX idx_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
