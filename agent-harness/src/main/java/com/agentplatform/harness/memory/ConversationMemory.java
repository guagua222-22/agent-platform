package com.agentplatform.harness.memory;

import java.util.List;

/**
 * 会话记忆（Harness 侧抽象）：为一次 Agent 运行提供"之前聊过什么"。
 *
 * 为什么记忆是 Harness 组件而不是 Runtime 组件：
 * 记忆影响的是"发给模型的提示词内容"（构建行为），与工具、提示词模板同类；
 * Runtime 管的是运行本身（持久化/并发/重试），不该知道模型看到了什么。
 * 分层策略（短期窗口 + 长期摘要）是实现细节，接口只暴露 load/append 两个动作。
 */
public interface ConversationMemory {

    /** 注入模型前的历史上下文（实现方决定窗口/摘要如何组合） */
    List<MemoryMessage> load(String conversationId);

    /** 一轮对话结束后追加存档（全量留档，窗口与摘要由实现方内部维护） */
    void append(String conversationId, String agentName, String userInput, String assistantAnswer);

    record MemoryMessage(String role, String content) {
    }
}
