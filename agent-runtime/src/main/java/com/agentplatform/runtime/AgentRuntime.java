package com.agentplatform.runtime;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.model.RunState;
import com.agentplatform.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 执行层入口：一次调用 = 一次完整、有明确终态的 Agent 运行。
 *
 * 为什么 Runtime 吞掉 Loop 抛出的异常而不是继续上抛：
 * Runtime 的核心承诺是"每次运行必达终态"——无论成功、超步数还是模型报错，
 * 都必须落在 COMPLETED/FAILED 之一并留档（事件流 + error），
 * 否则上层（SSE、评测）永远要处理"运行到一半没下文"的脏状态。
 * M1 起这里将扩展：持久化落库、Checkpoint 恢复、限流与并发控制。
 */
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** 便捷入口：不关心过程事件的调用方（如评测的 mock 模式） */
    public AgentRun run(AgentDefinition definition, String input) {
        return run(definition, input, event -> { });
    }

    /**
     * @param definition  Harness 组装好的 Agent 定义
     * @param input       用户输入
     * @param externalSink 外部事件消费者（SSE 推送 / Tracing），可为空
     * @return 终态运行记录（COMPLETED 或 FAILED）
     */
    public AgentRun run(AgentDefinition definition, String input, AgentEventSink externalSink) {
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(definition.getLoopStrategy(), "Agent 未指定 Loop 策略: " + definition.getName());
        AgentEventSink sink = externalSink == null ? event -> { } : externalSink;

        AgentRun run = new AgentRun(definition.getName(), input);
        // 复合 sink：事件先存档进 run，再转发给外部——存档是 Runtime 的底线职责，
        // 外部 sink 抛异常不允许中断运行（降级为只记日志，M1 接入 Tracing 后按需收紧）
        AgentEventSink composite = event -> {
            run.addEvent(event);
            try {
                sink.emit(event);
            } catch (Exception e) {
                log.warn("事件转发失败已忽略 run={} event={}", run.getId(), event.type(), e);
            }
        };

        composite.emit(AgentEvent.of(AgentEvent.EventType.RUN_STARTED,
                "agent=" + definition.getName() + " input=" + Strings.abbreviate(input)));
        run.transitionTo(RunState.RUNNING);
        try {
            definition.getLoopStrategy().execute(definition, run, composite);
            run.transitionTo(RunState.COMPLETED);
            composite.emit(AgentEvent.of(AgentEvent.EventType.RUN_COMPLETED,
                    "answer=" + Strings.abbreviate(run.getFinalAnswer())));
        } catch (Exception e) {
            run.fail(e.getMessage());
            run.transitionTo(RunState.FAILED);
            composite.emit(AgentEvent.of(AgentEvent.EventType.RUN_FAILED, e.toString()));
            log.error("Agent 运行失败 run={} agent={}", run.getId(), definition.getName(), e);
        }
        return run;
    }
}
