-- V3：会话记忆分层（M2-C）
-- 为什么记忆要落 MySQL 而不是只放 Redis：Redis 存的是 Checkpoint（单次运行内的现场，
-- TTL 1h 即弃），会话记忆是跨运行、跨重启的长期资产——用户明天再来，Agent 还得记得聊过什么。
-- 分层设计：messages 全量留档（审计/追溯用），会话行上的 summary 是长期记忆的压缩形态，
-- 短期记忆 = 最近 N 条窗口，注入模型的是"摘要 + 窗口"而非全量（全量会爆 token）。

CREATE TABLE IF NOT EXISTS agent_conversation (
    id          VARCHAR(64)  NOT NULL COMMENT '会话ID（客户端生成或首条消息时创建）',
    agent_name  VARCHAR(64)  NOT NULL COMMENT '绑定的 Agent',
    summary     TEXT         NULL COMMENT '长期记忆：被窗口挤出历史的旧消息压缩摘要',
    msg_count   INT          NOT NULL DEFAULT 0 COMMENT '消息总数（判断是否需要再摘要）',
    summarized_upto BIGINT   NOT NULL DEFAULT 0 COMMENT '摘要水位线：id <= 此值的消息已并入摘要',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 会话（记忆分层载体）';

CREATE TABLE IF NOT EXISTS agent_conversation_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content         TEXT         NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话消息全量存档';
