package com.agentplatform.runtime;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.agent.LoopStrategy;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.model.RunState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    private final AgentRuntime runtime = new AgentRuntime();

    /** 正常路径：Loop 给出最终回答，Runtime 落 COMPLETED 并广播首尾事件 */
    @Test
    void completedRunEmitsLifecycleEvents() {
        AgentDefinition def = AgentDefinition.builder()
                .name("ok-agent")
                .loopStrategy(new StubLoop(true))
                .build();

        List<AgentEvent> received = new ArrayList<>();
        AgentRun run = runtime.run(def, "hi", received::add);

        assertEquals(RunState.COMPLETED, run.getState());
        assertEquals("stub-answer", run.getFinalAnswer());
        assertEquals(AgentEvent.EventType.RUN_STARTED, received.get(0).type());
        assertEquals(AgentEvent.EventType.RUN_COMPLETED, received.get(received.size() - 1).type());
        // 事件既转发给外部 sink，也存档进 run（同一批事件，两份引用）
        assertEquals(received.size(), run.getEvents().size());
    }

    /** 异常路径：Loop 抛异常，Runtime 必须兜底为 FAILED 而不是向上抛 */
    @Test
    void failedRunIsContainedAsState() {
        AgentDefinition def = AgentDefinition.builder()
                .name("bad-agent")
                .loopStrategy(new StubLoop(false))
                .build();

        AgentRun run = runtime.run(def, "hi");

        assertEquals(RunState.FAILED, run.getState());
        assertEquals("stub-boom", run.getError());
        assertTrue(run.getEvents().stream()
                .anyMatch(e -> e.type() == AgentEvent.EventType.RUN_FAILED));
    }

    /** 外部 sink 抛异常不允许中断运行：SSE 客户端断连是常态，不能因此弄脏运行记录 */
    @Test
    void externalSinkFailureDoesNotBreakRun() {
        AgentDefinition def = AgentDefinition.builder()
                .name("ok-agent")
                .loopStrategy(new StubLoop(true))
                .build();

        AgentRun run = runtime.run(def, "hi", event -> {
            throw new RuntimeException("模拟客户端断连");
        });

        assertEquals(RunState.COMPLETED, run.getState());
        assertEquals(2, run.getEvents().size(), "事件存档不受外部 sink 失败影响");
    }

    /** null 定义是 API 边界输入，Runtime 必须 fail-fast 而不是 NPE 到执行深处 */
    @Test
    void nullDefinitionRejected() {
        assertThrows(NullPointerException.class, () -> runtime.run(null, "hi"));
    }

    private static class StubLoop implements LoopStrategy {
        private final boolean succeed;

        StubLoop(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public void execute(AgentDefinition definition, AgentRun run, AgentEventSink sink) {
            if (succeed) {
                run.complete("stub-answer");
            } else {
                throw new IllegalStateException("stub-boom");
            }
        }
    }
}
