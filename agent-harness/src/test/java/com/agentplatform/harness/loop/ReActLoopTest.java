package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.MaxStepsExceededException;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.harness.support.MockToolCallingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActLoopTest {

    private MockToolCallingChatModel model;
    private ReActLoop loop;

    /** 参数对象：与 agent-app 的 CalcArgs 同构，保证测试覆盖"JSON -> 对象"反序列化路径 */
    private record CalcArgs(String expression) {
    }

    /** 真计算器工具（与 agent-app 中一致的最小实现），mock 只换模型不换工具 */
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
            // 最小乘法解析（测试剧本固定 23*47 场景），不必复刻完整四则运算
            String[] parts = args.expression().trim().split("\\*");
            BigDecimal a = new BigDecimal(parts[0].trim());
            BigDecimal b = new BigDecimal(parts[1].trim());
            return a.multiply(b).toPlainString();
        }
    };

    @BeforeEach
    void setUp() {
        model = new MockToolCallingChatModel();
        loop = new ReActLoop(model);
    }

    private AgentDefinition definition(int maxSteps) {
        return AgentDefinition.builder()
                .name("calc")
                .systemPrompt("你是计算助手")
                .tool(CALCULATOR)
                .loopStrategy(loop)
                .maxSteps(maxSteps)
                .build();
    }

    /** 完整两轮：第一轮模型要调工具，第二轮给出最终回答 */
    @Test
    void executesToolThenAnswers() {
        model.scriptToolCall("calculator", "{\"expression\":\"23*47\"}")
                .scriptAnswer("23*47 的结果是 1081");

        AgentRun run = new AgentRun("calc", "23*47 等于多少");
        // 直接测 Loop 时用 run::addEvent 模拟 Runtime 复合 sink 的"事件存档"职责
        loop.execute(definition(5), run, run::addEvent);

        assertEquals("23*47 的结果是 1081", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e -> e.type() == AgentEvent.EventType.TOOL_CALLED));
        assertTrue(run.getEvents().stream().anyMatch(e -> e.type() == AgentEvent.EventType.TOOL_RESULT));
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.TOOL_RESULT && e.detail().contains("result=")));
        assertEquals(2, model.callCount(), "应恰好两轮模型调用");
    }

    /** 模型幻觉出不存在的工具：错误作为观察回填，模型下一轮仍有机会给出答案 */
    @Test
    void unknownToolBecomesObservationAndContinues() {
        model.scriptToolCall("not_exist", "{}")
                .scriptAnswer("我无法使用该工具");

        AgentRun run = new AgentRun("calc", "hi");
        loop.execute(definition(5), run, run::addEvent);

        assertEquals("我无法使用该工具", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.TOOL_RESULT && e.detail().contains("error=")));
    }

    /** 工具执行抛异常：同样作为观察回填，循环不中断 */
    @Test
    void toolFailureBecomesObservation() {
        Tool<CalcArgs> failingTool = new Tool<>() {
            @Override
            public String name() {
                return "bomb";
            }

            @Override
            public String description() {
                return "总是失败";
            }

            @Override
            public Class<CalcArgs> inputType() {
                return CalcArgs.class;
            }

            @Override
            public String execute(CalcArgs args) {
                throw new IllegalStateException("boom");
            }
        };
        AgentDefinition def = AgentDefinition.builder()
                .name("calc").tool(failingTool).loopStrategy(loop).maxSteps(5).build();

        model.scriptToolCall("bomb", "{\"expression\":\"x\"}").scriptAnswer("工具坏了，抱歉");

        AgentRun run = new AgentRun("calc", "hi");
        loop.execute(def, run, run::addEvent);

        assertEquals("工具坏了，抱歉", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.TOOL_RESULT && e.detail().contains("boom")));
    }

    /** 模型无限调工具：达到 maxSteps 必须抛异常终止，防死循环是 ReAct 的硬约束 */
    @Test
    void maxStepsGuardThrows() {
        model.scriptToolCall("calculator", "{\"expression\":\"1+1\"}")
                .scriptToolCall("calculator", "{\"expression\":\"1+1\"}")
                .scriptToolCall("calculator", "{\"expression\":\"1+1\"}");

        AgentRun run = new AgentRun("calc", "hi");
        List<AgentEvent> events = new ArrayList<>();
        assertThrows(MaxStepsExceededException.class, () -> loop.execute(definition(3), run, events::add));
    }

    /** 无工具 Agent：模型一轮直接回答，零工具调用 */
    @Test
    void noToolsSingleRound() {
        AgentDefinition def = AgentDefinition.builder()
                .name("chat").systemPrompt("你是助手").loopStrategy(loop).maxSteps(3).build();

        model.scriptAnswer("你好！");

        AgentRun run = new AgentRun("chat", "你好");
        loop.execute(def, run, run::addEvent);

        assertEquals("你好！", run.getFinalAnswer());
        assertEquals(1, model.callCount());
    }
}
