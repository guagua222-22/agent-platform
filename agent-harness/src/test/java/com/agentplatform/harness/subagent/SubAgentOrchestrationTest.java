package com.agentplatform.harness.subagent;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.harness.eval.MockChatModel;
import com.agentplatform.harness.loop.ReActLoop;
import com.agentplatform.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubAgent 编排（agent-as-tool）：委托路由、嵌套运行关联、事件转发、深度熔断。
 * 全链路只有模型是假的：Runtime、ReActLoop、SubAgentTool、事件流全走生产代码。
 */
class SubAgentOrchestrationTest {

    private record CalcArgs(String expression) {
    }

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

    private AgentDefinition agent(String name, MockChatModel model, Tool<?>... tools) {
        AgentDefinition.Builder b = AgentDefinition.builder()
                .name(name).systemPrompt("你是" + name)
                .loopStrategy(new ReActLoop(model)).maxSteps(5);
        for (Tool<?> t : tools) {
            b.tool(t);
        }
        return b.build();
    }

    /** 标准委托链路：编排者路由 -> 子 Agent 完整运行 -> 结果回填 -> 编排者汇总 */
    @Test
    void delegatesAndReturnsSubResult() {
        AgentRuntime runtime = new AgentRuntime();
        MockChatModel orchModel = new MockChatModel();
        MockChatModel calcModel = new MockChatModel();

        AgentDefinition calc = agent("calc", calcModel, CALCULATOR);
        AgentDefinition orch = agent("orch", orchModel, new SubAgentTool(calc, runtime));

        orchModel.scriptToolCall("delegate_to_calc", "{\"task\":\"23*47 等于多少\"}")
                .scriptAnswer("算好了，结果是 1081");
        calcModel.scriptToolCall("calculator", "{\"expression\":\"23*47\"}")
                .scriptAnswer("23*47=1081");

        AgentRun run = runtime.run(orch, "帮我算 23*47");

        assertEquals("算好了，结果是 1081", run.getFinalAnswer());
        // 子 Agent 真的完整跑了一轮：工具被执行（1081 只能来自真计算器）
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.TOOL_RESULT && e.detail().contains("1081")));
        // 子事件加前缀转发进父事件流：父运行能看到完整嵌套思考过程
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.RUN_STARTED && e.detail().contains("[sub:calc]")));
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.RUN_COMPLETED && e.detail().contains("[sub:calc]")));
    }

    /** 嵌套运行关联：子运行的 parentRunId 必须指向父运行（编排树的数据基础） */
    @Test
    void subRunCarriesParentId() {
        AgentRuntime runtime = new AgentRuntime();
        MockChatModel parentModel = new MockChatModel();
        MockChatModel childModel = new MockChatModel();
        AtomicReference<AgentRun> capturedSub = new AtomicReference<>();

        AgentDefinition child = agent("child", childModel);
        // 自定义工具直接调 runChild，把子运行对象抓出来断言
        Tool<CalcArgs> delegatingTool = new Tool<>() {
            @Override
            public String name() { return "grab_sub"; }

            @Override
            public String description() { return "抓取子运行"; }

            @Override
            public Class<CalcArgs> inputType() { return CalcArgs.class; }

            @Override
            public String execute(CalcArgs args) {
                capturedSub.set(runtime.runChild(child, "子任务"));
                return "done";
            }
        };
        AgentDefinition parent = agent("parent", parentModel, delegatingTool);

        parentModel.scriptToolCall("grab_sub", "{\"expression\":\"1*1\"}").scriptAnswer("完成");
        childModel.scriptAnswer("子回答");

        AgentRun parentRun = runtime.run(parent, "顶层任务");

        assertEquals(parentRun.getId(), capturedSub.get().getParentRunId());
        assertEquals("child", capturedSub.get().getAgentName());
    }

    /** 深度熔断：嵌套到 MAX_DEPTH 后继续委托被硬性截断，错误作为观察回填 */
    @Test
    void depthLimitEnforced() {
        AgentRuntime runtime = new AgentRuntime();
        MockChatModel aModel = new MockChatModel();
        MockChatModel bModel = new MockChatModel();
        MockChatModel cModel = new MockChatModel();

        // C 也拿着委托工具：C 运行在 depth=2，再委托必触发熔断
        AgentDefinition c = agent("c", cModel,
                new SubAgentTool(agent("d", new MockChatModel()), runtime));
        AgentDefinition b = agent("b", bModel, new SubAgentTool(c, runtime));
        AgentDefinition a = agent("a", aModel, new SubAgentTool(b, runtime));

        aModel.scriptToolCall("delegate_to_b", "{\"task\":\"任务B\"}").scriptAnswer("A 汇总完毕");
        bModel.scriptToolCall("delegate_to_c", "{\"task\":\"任务C\"}").scriptAnswer("B 汇报");
        cModel.scriptToolCall("delegate_to_d", "{\"task\":\"任务D\"}").scriptAnswer("C 无法继续，坦白交代");

        AgentRun run = runtime.run(a, "套娃任务");

        assertEquals("A 汇总完毕", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.TOOL_RESULT && e.detail().contains("嵌套深度超过上限")),
                "深度熔断的错误应作为观察出现在事件流中");
    }

    /** 子运行失败不拖死父运行：失败作为观察回填，编排者有机会换路或坦白 */
    @Test
    void subFailureBecomesObservation() {
        AgentRuntime runtime = new AgentRuntime();
        MockChatModel orchModel = new MockChatModel();
        // 子 Agent 模型脚本耗尽即抛异常 -> 子运行 FAILED
        MockChatModel failingModel = new MockChatModel();

        AgentDefinition broken = agent("broken", failingModel);
        AgentDefinition orch = agent("orch", orchModel, new SubAgentTool(broken, runtime));

        orchModel.scriptToolCall("delegate_to_broken", "{\"task\":\"试试看\"}")
                .scriptAnswer("专家出了状况，我直接回答你");

        AgentRun run = runtime.run(orch, "测试容错");

        assertEquals("专家出了状况，我直接回答你", run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.TOOL_RESULT && e.detail().contains("执行失败")));
    }

    /** 裸调委托工具（不在任何运行内）：装配错误必须 fail-fast */
    @Test
    void delegationOutsideRunRejected() {
        AgentRuntime runtime = new AgentRuntime();
        SubAgentTool tool = new SubAgentTool(agent("x", new MockChatModel()), runtime);
        assertThrows(IllegalStateException.class,
                () -> tool.execute(new SubAgentTool.DelegateArgs("任务")));
    }

    /** 空任务描述：子 Agent 看不到对话上下文，空描述等于让子 Agent 瞎猜 */
    @Test
    void blankTaskRejected() {
        AgentRuntime runtime = new AgentRuntime();
        SubAgentTool tool = new SubAgentTool(agent("x", new MockChatModel()), runtime);
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(new SubAgentTool.DelegateArgs("  ")));
    }
}
