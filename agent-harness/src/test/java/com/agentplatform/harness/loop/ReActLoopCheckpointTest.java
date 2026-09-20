package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.harness.support.MockToolCallingChatModel;
import com.agentplatform.runtime.checkpoint.Checkpoint;
import com.agentplatform.runtime.checkpoint.CheckpointStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoint 断点续跑：验证每轮循环保存快照，以及从中途恢复后
 * "不重放已完成步骤、直接继续"的核心语义。
 */
class ReActLoopCheckpointTest {

    private record CalcArgs(String expression) {
    }

    private static final Tool<CalcArgs> CALCULATOR = new Tool<>() {
        @Override
        public String name() {
            return "calculator";
        }

        @Override
        public String description() {
            return "计算二元四则运算";
        }

        @Override
        public Class<CalcArgs> inputType() {
            return CalcArgs.class;
        }

        @Override
        public String execute(CalcArgs args) {
            String[] parts = args.expression().trim().split("\\*");
            return new BigDecimal(parts[0].trim()).multiply(new BigDecimal(parts[1].trim())).toPlainString();
        }
    };

    private static class MemoryCheckpointStore implements CheckpointStore {
        final Map<String, Checkpoint> data = new HashMap<>();

        @Override
        public void save(Checkpoint checkpoint) {
            data.put(checkpoint.runId(), checkpoint);
        }

        @Override
        public Optional<Checkpoint> load(String runId) {
            return Optional.ofNullable(data.get(runId));
        }

        @Override
        public void delete(String runId) {
            data.remove(runId);
        }
    }

    @Test
    void checkpointSavedAfterEachCompletedStep() {
        MockToolCallingChatModel model = new MockToolCallingChatModel();
        // 剧本只给第 1 轮：第 2 轮模型调用时脚本耗尽抛异常，模拟运行中断
        model.scriptToolCall("calculator", "{\"expression\":\"23*47\"}");

        MemoryCheckpointStore store = new MemoryCheckpointStore();
        ReActLoop loop = new ReActLoop(model, store);
        AgentDefinition def = AgentDefinition.builder()
                .name("calc").tool(CALCULATOR).loopStrategy(loop).maxSteps(5).build();

        AgentRun run = new AgentRun("calc", "23*47 等于多少");
        try {
            loop.execute(def, run, run::addEvent);
        } catch (IllegalStateException expected) {
            // 预期：脚本耗尽模拟中断
        }

        Checkpoint cp = store.load(run.getId()).orElseThrow();
        assertEquals(1, cp.step(), "第 1 轮完成后应保存 step=1 的快照");
        // 消息历史 = system + user + assistant(toolCall) + tool(result)，共 4 条
        assertEquals(4, cp.messages().size());
    }

    @Test
    void resumeContinuesFromCheckpointWithoutReplay() {
        // 第一步：跑一轮工具调用，制造中断现场与 checkpoint
        MockToolCallingChatModel firstModel = new MockToolCallingChatModel();
        firstModel.scriptToolCall("calculator", "{\"expression\":\"23*47\"}");

        MemoryCheckpointStore store = new MemoryCheckpointStore();
        ReActLoop firstLoop = new ReActLoop(firstModel, store);
        AgentDefinition def = AgentDefinition.builder()
                .name("calc").tool(CALCULATOR).loopStrategy(firstLoop).maxSteps(5).build();

        AgentRun interrupted = new AgentRun("calc", "23*47 等于多少");
        try {
            firstLoop.execute(def, interrupted, interrupted::addEvent);
        } catch (IllegalStateException expected) {
            // 第 2 轮模型调用脚本耗尽，模拟中断
        }
        Checkpoint cp = store.load(interrupted.getId()).orElseThrow();

        // 第二步：恢复——新模型实例只提供"最终回答"剧本
        MockToolCallingChatModel resumeModel = new MockToolCallingChatModel();
        resumeModel.scriptAnswer("23*47 的结果是 1081");

        ReActLoop resumeLoop = new ReActLoop(resumeModel, store);
        AgentDefinition resumeDef = AgentDefinition.builder()
                .name("calc").tool(CALCULATOR).loopStrategy(resumeLoop).maxSteps(5).build();

        AgentRun resumed = AgentRun.restore(interrupted.getId(), "calc", "23*47 等于多少",
                com.agentplatform.core.model.RunState.RUNNING, null, null,
                interrupted.getStartedAt(), null);
        resumeLoop.executeResume(resumeDef, resumed, resumed::addEvent, cp);

        assertEquals("23*47 的结果是 1081", resumed.getFinalAnswer());
        // 关键断言：恢复模型只被调用一次（直接产出答案），
        // 第 1 步的工具调用没有被重放——重放意味着重复花钱
        assertEquals(1, resumeModel.callCount(), "恢复路径不得重放已完成步骤");
        assertTrue(resumed.getEvents().stream().anyMatch(e ->
                e.type() == com.agentplatform.core.model.AgentEvent.EventType.LLM_CALLED
                        && e.detail().equals("step=2")), "恢复后应从 step=2 继续");
    }
}
