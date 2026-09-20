package com.agentplatform.runtime;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.agent.LoopStrategy;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.model.RunState;
import com.agentplatform.runtime.persistence.RunRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化集成路径：用内存版 RunRepository 验证 Runtime 的落库调用
 * （RUNNING 先落库、事件逐条追加、终态回写），不依赖真实 MySQL。
 */
class AgentRuntimePersistenceTest {

    /** 内存实现：与 JdbcRunRepository 同契约，测试零成本 */
    private static class InMemoryRunRepository implements RunRepository {
        private final Map<String, AgentRun> runs = new LinkedHashMap<>();
        private final Map<String, List<AgentEvent>> events = new LinkedHashMap<>();

        @Override
        public void saveRun(AgentRun run) {
            runs.put(run.getId(), run);
        }

        @Override
        public void saveEvent(String runId, AgentEvent event, int seq) {
            events.computeIfAbsent(runId, k -> new ArrayList<>()).add(event);
        }

        @Override
        public Optional<AgentRun> findRun(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<AgentEvent> findEvents(String runId) {
            return events.getOrDefault(runId, List.of());
        }

        @Override
        public List<AgentRun> findRecent(int limit) {
            return runs.values().stream().toList();
        }
    }

    private static final LoopStrategy OK_LOOP = new LoopStrategy() {
        @Override
        public String name() {
            return "ok";
        }

        @Override
        public void execute(AgentDefinition definition, AgentRun run, AgentEventSink sink) {
            sink.emit(AgentEvent.of(AgentEvent.EventType.LLM_CALLED, "step=1"));
            run.complete("回答");
        }
    };

    @Test
    void runIsPersistedThroughLifecycle() {
        InMemoryRunRepository repo = new InMemoryRunRepository();
        AgentRuntime runtime = new AgentRuntime(repo);
        AgentDefinition def = AgentDefinition.builder().name("t").loopStrategy(OK_LOOP).build();

        AgentRun run = runtime.run(def, "hi");

        // 运行记录落库且状态为终态 COMPLETED
        AgentRun saved = repo.findRun(run.getId()).orElseThrow();
        assertEquals(RunState.COMPLETED, saved.getState());
        assertEquals("回答", saved.getFinalAnswer());

        // 事件逐条落库：RUN_STARTED + LLM_CALLED + RUN_COMPLETED = 3 条
        assertEquals(3, repo.findEvents(run.getId()).size());
    }

    @Test
    void repositoryFailureDoesNotBreakRun() {
        // 模拟数据库故障：saveEvent 抛异常，运行本身必须照常完成
        RunRepository broken = new RunRepository() {
            @Override
            public void saveRun(AgentRun run) {
                throw new IllegalStateException("db down");
            }

            @Override
            public void saveEvent(String runId, AgentEvent event, int seq) {
                throw new IllegalStateException("db down");
            }

            @Override
            public Optional<AgentRun> findRun(String runId) {
                return Optional.empty();
            }

            @Override
            public List<AgentEvent> findEvents(String runId) {
                return List.of();
            }

            @Override
            public List<AgentRun> findRecent(int limit) {
                return List.of();
            }
        };
        AgentRuntime runtime = new AgentRuntime(broken);
        AgentDefinition def = AgentDefinition.builder().name("t").loopStrategy(OK_LOOP).build();

        AgentRun run = runtime.run(def, "hi");

        assertEquals(RunState.COMPLETED, run.getState(), "落库失败不得影响对话本身");
        assertTrue(run.getEvents().size() >= 3, "内存事件存档不受落库失败影响");
    }
}
