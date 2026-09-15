package com.agentplatform.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunTest {

    @Test
    void happyPathTransitions() {
        AgentRun run = new AgentRun("test-agent", "hello");
        assertEquals(RunState.CREATED, run.getState());

        run.transitionTo(RunState.RUNNING);
        run.complete("回答");
        run.transitionTo(RunState.COMPLETED);

        assertEquals(RunState.COMPLETED, run.getState());
        assertEquals("回答", run.getFinalAnswer());
        assertNotNull(run.getFinishedAt());
    }

    @Test
    void failedPathTransitions() {
        AgentRun run = new AgentRun("test-agent", "hello");
        run.transitionTo(RunState.RUNNING);
        run.fail("模型超时");
        run.transitionTo(RunState.FAILED);

        assertEquals(RunState.FAILED, run.getState());
        assertEquals("模型超时", run.getError());
    }

    @Test
    void illegalTransitionThrows() {
        AgentRun run = new AgentRun("test-agent", "hello");
        // 跳过 RUNNING 直接到 COMPLETED：状态机必须拒绝这种"未执行就完成"的脏路径
        assertThrows(IllegalStateException.class, () -> run.transitionTo(RunState.COMPLETED));
    }

    @Test
    void terminalStateIsImmutable() {
        AgentRun run = new AgentRun("test-agent", "hello");
        run.transitionTo(RunState.RUNNING);
        run.transitionTo(RunState.COMPLETED);
        // 终态不可再迁移：防止并发环境下已完成的结果被二次改写
        assertThrows(IllegalStateException.class, () -> run.transitionTo(RunState.RUNNING));
    }

    @Test
    void eventsAreAppendedInOrder() {
        AgentRun run = new AgentRun("test-agent", "hello");
        run.addEvent(AgentEvent.of(AgentEvent.EventType.RUN_STARTED, "start"));
        run.addEvent(AgentEvent.of(AgentEvent.EventType.RUN_COMPLETED, "end"));

        assertEquals(2, run.getEvents().size());
        assertEquals(AgentEvent.EventType.RUN_STARTED, run.getEvents().get(0).type());
    }
}
