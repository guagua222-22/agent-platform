package com.agentplatform.runtime.queue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 一次异步 Agent 任务（任务队列的消息体）。
 *
 * 为什么任务 id 直接等于运行 id（runId）：
 * 生产者预生成 runId 入队 + 落库（CREATED 状态），消费者用同一 id 启动运行——
 * 提交即返回的 id 立刻可查状态，不需要"任务表 -> 运行表"的映射，也少一次状态同步。
 *
 * 消息编码为什么手写而不是用 JSON：四个字段结构固定，手写编解码十几行且格式显式——
 * 用 mqadmin/控制台查消息时肉眼可读，没有 schema 演进包袱；
 * input 可能含任意字符（包括分隔符本身），Base64 是无转义地狱的最简方案。
 */
public record AgentTask(String runId, String agentName, String conversationId, String input) {

    private static final String SEP = "\u0001";

    public byte[] encode() {
        return String.join(SEP,
                runId,
                agentName,
                conversationId == null ? "" : conversationId,
                Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8))
        ).getBytes(StandardCharsets.UTF_8);
    }

    public static AgentTask decode(byte[] body) {
        String[] parts = new String(body, StandardCharsets.UTF_8).split(SEP, -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("任务消息格式非法：期望 4 段，实际 " + parts.length);
        }
        return new AgentTask(parts[0], parts[1], parts[2].isEmpty() ? null : parts[2],
                new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8));
    }
}
