package com.agentplatform.runtime;

import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.model.AgentRun;

/**
 * 当前线程正在执行的运行上下文（嵌套运行传播的载体）。
 *
 * 为什么用 ThreadLocal 而不是把 run 塞进 Tool 契约参数：
 * Tool 契约保持纯粹（参数 = 模型输出的 JSON），不该感知"自己在哪次运行里被执行"；
 * 但 SubAgent 工具又必须知道父运行才能建立父子关联、把子事件转发进父事件流。
 * ThreadLocal 是框架级上下文传播的惯用解法（同 Spring SecurityContext、SLF4J MDC），
 * 虚拟线程下依然安全——每个虚拟线程有独立副本，且 Runtime 在 finally 中必定清理。
 *
 * @param run    当前运行
 * @param sink   当前运行的复合事件出口（子运行事件经它转发进父事件流）
 * @param depth  嵌套深度：顶层运行 = 0，每嵌套一层 +1（SubAgentTool 据此拒绝过深委托）
 */
public record RunContext(AgentRun run, AgentEventSink sink, int depth) {

    /** 嵌套深度上限：编排者 -> 专家 足够覆盖现实场景，放任无限嵌套等于把死循环权交给模型 */
    public static final int MAX_DEPTH = 2;

    private static final ThreadLocal<RunContext> HOLDER = new ThreadLocal<>();

    static void set(RunContext context) {
        HOLDER.set(context);
    }

    static void clear() {
        HOLDER.remove();
    }

    /** 当前线程是否在某次运行内（SubAgentTool 据此判断父运行） */
    public static RunContext current() {
        return HOLDER.get();
    }
}
