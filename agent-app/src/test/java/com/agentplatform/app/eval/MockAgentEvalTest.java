package com.agentplatform.app.eval;

import com.agentplatform.app.agent.AgentConfig;
import com.agentplatform.app.agent.CalculatorTool;
import com.agentplatform.app.agent.TimeTool;
import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.harness.eval.CaseResult;
import com.agentplatform.harness.eval.HarnessRunner;
import com.agentplatform.harness.eval.MarkdownReport;
import com.agentplatform.harness.eval.MockChatModel;
import com.agentplatform.harness.knowledge.KnowledgeSearchTool;
import com.agentplatform.harness.loop.PlanExecuteLoop;
import com.agentplatform.harness.loop.ReActLoop;
import com.agentplatform.harness.skill.SkillRegistry;
import com.agentplatform.harness.tool.ToolRegistry;
import com.agentplatform.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mock 版全量评测：生产装配（AgentConfig 原样调用） + 假模型剧本，
 * 只有模型是假的——Agent 定义、技能注入、Loop 策略、工具执行链全走生产代码。
 *
 * 为什么不复用 Spring 上下文装配：单测要能在没有 MySQL/Redis/Milvus 的环境跑，
 * AgentConfig 的 @Bean 方法都是普通 Java 方法，直接 new 出来调用即可拿到同一份装配逻辑，
 * 避免了"为了测试起整个容器"的重型依赖。
 */
class MockAgentEvalTest {

    /** 检索桩：与真工具同名同契约，返回预置片段——RAG 链路评测不依赖 Milvus 在场 */
    private static KnowledgeSearchTool stubSearchTool() {
        return new KnowledgeSearchTool(null) {
            @Override
            public String execute(SearchArgs args) {
                return "知识库检索结果：\n[1] 来源《星舟公司内部手册》(片段2，相关度 0.912)\n" +
                        "报销流程：员工填写报销单 -> 部门主管审批 -> 财务复核打款。\n" +
                        "回答时请基于以上片段，并注明引用来源；若片段不足以回答，请明说知识库信息不足。";
            }
        };
    }

    /** 复用 AgentConfig 的生产装配，只把模型换成 mock——提示词漂移在此无处遁形 */
    private Map<String, AgentDefinition> assemble(MockChatModel model) {
        AgentConfig config = new AgentConfig();
        SkillRegistry skills = config.skillRegistry(
                new CalculatorTool(), new TimeTool(), stubSearchTool(), new ToolRegistry());
        return config.agents(skills, new ReActLoop(model), new PlanExecuteLoop(model));
    }

    @Test
    void assistantSuiteAllPass() throws IOException {
        runSuite("assistant 通用任务 Agent", AgentEvalCases.assistantScenarios());
    }

    @Test
    void knowledgeSuiteAllPass() throws IOException {
        runSuite("knowledge RAG 知识库 Agent", AgentEvalCases.knowledgeScenarios());
    }

    @Test
    void plannerSuiteAllPass() throws IOException {
        runSuite("planner 规划型 Agent", AgentEvalCases.plannerScenarios());
    }

    private void runSuite(String title, List<EvalScenario> scenarios) throws IOException {
        List<CaseResult> allResults = new ArrayList<>();
        for (EvalScenario scenario : scenarios) {
            // 每条用例独立模型与独立装配：用例间零共享，可复现是评测的第一生命线
            MockChatModel model = new MockChatModel();
            scenario.script().accept(model);
            AgentDefinition agent = assemble(model).get(scenario.agentName());
            allResults.addAll(new HarnessRunner(new AgentRuntime()).run(agent, List.of(scenario.testCase())));
        }

        // 报告落盘：通过率变化可追溯，真模型验证时可与 mock 报告对比
        Path reportPath = Path.of("target", "eval", title.split(" ")[0] + "-mock.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, MarkdownReport.render(title + "（mock）", allResults));

        long passed = allResults.stream().filter(CaseResult::passed).count();
        assertTrue(passed == allResults.size(),
                title + "：通过率 " + passed + "/" + allResults.size() + "，详情见 " + reportPath);
    }
}
