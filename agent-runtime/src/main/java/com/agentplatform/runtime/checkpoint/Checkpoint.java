package com.agentplatform.runtime.checkpoint;

import java.util.List;

/**
 * 运行中途快照：断点续跑的最小恢复单元。
 *
 * 为什么 checkpoint 只存"消息历史 + 步数"而不存整个 AgentRun：
 * AgentRun 本体已在 MySQL（agent_run 表），checkpoint 是热数据、生命周期短（TTL），
 * 职责分工：MySQL 管"最终记录"，Redis 管"中途现场"——恢复时两者合并。
 */
public record Checkpoint(
        String runId,
        /** 已完成循环轮数，恢复时从 step+1 继续 */
        int step,
        /** 消息历史快照（含系统提示、用户输入、assistant 工具调用、工具结果） */
        List<CheckpointMessage> messages,
        /** 已产出的最终答案（通常为 null，恢复完成后写入） */
        String finalAnswer) {

    /** 简化消息：与 spring-ai 的 Message 一一对应，JSON 可序列化 */
    public record CheckpointMessage(
            String role, // system / user / assistant / tool
            String content,
            List<CheckpointToolCall> toolCalls,
            List<CheckpointToolResponse> toolResponses) {
    }

    public record CheckpointToolCall(String id, String name, String arguments) {
    }

    public record CheckpointToolResponse(String id, String name, String content) {
    }
}
