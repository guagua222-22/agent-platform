package com.agentplatform.core.agent;

import com.agentplatform.core.model.AgentEvent;

/**
 * 事件出口。Loop 只负责发事件，不关心事件去哪（SSE 推给用户、存档、Tracing 上报）。
 * 为什么用函数式接口：解耦方向是"策略层不感知基础设施层"，M1 接入 Tracing 时
 * 只需在 Runtime 组装复合 sink，Loop 代码零改动。
 */
@FunctionalInterface
public interface AgentEventSink {

    void emit(AgentEvent event);
}
