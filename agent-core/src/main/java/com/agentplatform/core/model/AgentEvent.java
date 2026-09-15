package com.agentplatform.core.model;

import java.time.Instant;

/**
 * Agent 运行过程中对外广播的事件（粒度：一次运行内的关键动作）。
 *
 * 为什么事件要独立于最终结果：SSE 流式输出、Tracing、评测断言（如"工具是否被调用"）
 * 都依赖过程信息而非仅依赖最终答案；事件流是 Runtime 可观测性的最小单元。
 *
 * @param type      事件类型
 * @param timestamp 发生时间（UTC）
 * @param detail    人类可读描述，用于日志与报告展示
 */
public record AgentEvent(EventType type, Instant timestamp, String detail) {

    public enum EventType {
        RUN_STARTED,     // 运行开始
        LLM_CALLED,      // 发起一次模型调用
        TOOL_CALLED,     // 模型要求调用某个工具
        TOOL_RESULT,     // 工具执行完毕
        STEP_COMPLETED,  // 一轮循环结束
        RUN_COMPLETED,   // 运行正常结束
        RUN_FAILED       // 运行异常结束
    }

    public static AgentEvent of(EventType type, String detail) {
        return new AgentEvent(type, Instant.now(), detail);
    }
}
