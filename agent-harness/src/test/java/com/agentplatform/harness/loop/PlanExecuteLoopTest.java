package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.harness.eval.MockChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteLoopTest {

    private MockChatModel model;
    private PlanExecuteLoop loop;

    private record CalcArgs(String expression) {
    }

    /** 与 ReActLoopTest 同款的真计算器：测试只换模型剧本，工具执行链走真实路径 */
    private static final Tool<CalcArgs> CALCULATOR = new Tool<>() {
        @Override
        public String name() { return "calculator"; }

        @Override
        public String description() { return "计算二元四则运算"; }

        @Override
        public Class<CalcArgs> inputType() { return CalcArgs.class; }

        @Override
        public String execute(CalcArgs args) {
            String[] parts = args.expression().trim().split("\\*");
            return new BigDecimal(parts[0].trim()).multiply(new BigDecimal(parts[1].trim())).toPlainString();
        }
    };

    @BeforeEach
    void setUp() {
        model = new MockChatModel();
        loop = new PlanExecuteLoop(model);
    }

    private AgentDefinition definition(int maxSteps) {
        return AgentDefinition.builder()
                .name("planner")
                .systemPrompt("你是任务规划助手")
                .tool(CALCULATOR)
                .loopStrategy(loop)
                .maxSteps(maxSteps)
                .build();
    }

    /** 标准三阶段：规划 2 步 -> 步骤1调工具 -> 步骤2直接答 -> 汇总产出最终回答 */
    @Test
    void planExecuteSummarize() {
        model.scriptAnswer("1. 计算 23*47\n2. 说明结果含义")                    // 规划
                .scriptToolCall("calculator", "{\"expression\":\"23*47\"}")     // 步骤1 第1轮：要工具
                .scriptAnswer("步骤1完成：1081")                                // 步骤1 第2轮：结论
                .scriptAnswer("步骤2完成：这是乘积")                            // 步骤2 一轮直接答
                .scriptAnswer("最终答案：23*47=1081");                          // 汇总

        AgentRun run = new AgentRun("planner", "算 23*47 并解释");
        loop.execute(definition(5), run, run::addEvent);

        assertEquals("最终答案：23*47=1081", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_GENERATED && e.detail().contains("steps=2")));
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_STEP_STARTED && e.detail().contains("step=1/2")));
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_STEP_COMPLETED && e.detail().contains("1081")));
        assertTrue(run.getEvents().stream().anyMatch(e -> e.type() == AgentEvent.EventType.TOOL_CALLED));
        // 规划1 + 步骤1两轮 + 步骤2一轮 + 汇总1 = 5 次模型调用
        assertEquals(5, model.callCount());
    }

    /** 模型规划输出不合规（无编号行）：降级为单步执行，运行不中断 */
    @Test
    void invalidPlanFallsBackToSingleStep() {
        model.scriptAnswer("我觉得这个任务很简单")   // 规划：解析不出步骤
                .scriptAnswer("直接做完了")          // 单步执行
                .scriptAnswer("最终答案");           // 汇总

        AgentRun run = new AgentRun("planner", "随便聊聊");
        loop.execute(definition(5), run, run::addEvent);

        assertEquals("最终答案", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_GENERATED && e.detail().contains("steps=1")));
    }

    /** 计划超过 maxSteps：截断执行已规划部分，而不是整体报错 */
    @Test
    void planTruncatedToMaxSteps() {
        model.scriptAnswer("1. 步骤一\n2. 步骤二\n3. 步骤三\n4. 步骤四")
                .scriptAnswer("步骤一完成")
                .scriptAnswer("步骤二完成")
                .scriptAnswer("最终答案");

        AgentRun run = new AgentRun("planner", "多步任务");
        loop.execute(definition(2), run, run::addEvent);

        assertEquals("最终答案", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_GENERATED && e.detail().contains("steps=2")));
        assertTrue(run.getEvents().stream().noneMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_STEP_STARTED && e.detail().contains("step=3")));
    }

    /** 步骤内轮次耗尽：该步标记部分进展，后续步骤与汇总照常——单步失败不拖垮整体 */
    @Test
    void stepRoundBudgetExhaustedStillCompletes() {
        model.scriptAnswer("1. 调工具步骤")
                .scriptToolCall("calculator", "{\"expression\":\"1*1\"}")   // 步骤1 第1轮
                .scriptToolCall("calculator", "{\"expression\":\"1*1\"}")   // 第2轮还要工具
                .scriptToolCall("calculator", "{\"expression\":\"1*1\"}")   // 第3轮仍要工具 -> 预算耗尽
                .scriptAnswer("最终答案");

        AgentRun run = new AgentRun("planner", "测试");
        loop.execute(definition(5), run, run::addEvent);

        assertEquals("最终答案", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.PLAN_STEP_COMPLETED && e.detail().contains("部分进展")));
    }

    /** 计划解析：兼容 "1." / "2、" / "3)" / "-" 等列表格式，忽略前言与空行 */
    @Test
    void parsePlanToleratesFormats() {
        List<String> steps = PlanExecuteLoop.parsePlan(
                "这是计划：\n1. 第一步\n2、第二步\n3) 第三步\n- 第四步\n\n以上");
        assertEquals(List.of("第一步", "第二步", "第三步", "第四步"), steps);
    }
}
