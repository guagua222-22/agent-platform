package com.agentplatform.core.agent;

import com.agentplatform.core.model.AgentRun;

/**
 * Agent 循环策略：决定"模型与工具之间如何往复"，是 Agent 的思维骨架。
 *
 * 为什么放在 core 而不是 harness 实现类里：Runtime 只认这个接口，不关心具体是
 * ReAct、Plan-and-Execute 还是未来的新策略——策略可插拔是平台区别于一次性脚本的关键。
 */
public interface LoopStrategy {

    /** 策略名，写入事件流便于 Tracing 与评测区分 */
    String name();

    /**
     * 执行完整循环。
     * 实现方负责：组装消息 -> 调模型 -> 执行工具 -> 回填结果 -> 循环，直到产出
     * 最终回答或达到步数上限；所有关键动作通过 {@code sink} 广播事件。
     * 异常允许向上抛，由 Runtime 统一兜底为 FAILED 终态。
     *
     * @param definition Agent 定义：系统提示词、可用工具、步数上限都在这里
     * @param run        本次运行的记录载体（输入、事件存档、最终回答写入处）
     */
    void execute(AgentDefinition definition, AgentRun run, AgentEventSink sink);
}
