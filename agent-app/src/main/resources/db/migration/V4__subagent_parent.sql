-- V4：SubAgent 嵌套运行（M3）
-- 为什么父子关联放在 agent_run 上加列而不是另建关系表：
-- 嵌套运行本身就是一次完整的 AgentRun（独立事件存档、独立终态、独立持久化），
-- parent_run_id 只是它众多属性之一；另建关系表会把"一次运行"拆成两张皮，
-- 查询子运行列表也只是一句 WHERE parent_run_id = ?，索引足矣。
ALTER TABLE agent_run
    ADD COLUMN parent_run_id VARCHAR(64) NULL COMMENT '父运行ID：非空表示 SubAgent 嵌套运行' AFTER agent_name,
    ADD INDEX idx_parent (parent_run_id);
