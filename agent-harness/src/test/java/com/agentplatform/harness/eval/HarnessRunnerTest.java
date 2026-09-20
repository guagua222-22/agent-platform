package com.agentplatform.harness.eval;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.harness.loop.ReActLoop;
import com.agentplatform.harness.support.MockToolCallingChatModel;
import com.agentplatform.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测体系端到端：mock 模型 + 真实 Runtime + 真实 ReActLoop，
 * 只有模型是假的——这保证测的是生产代码路径本身。
 */
class HarnessRunnerTest {

    private static final Tool<String> CALCULATOR = new Tool<>() {
        @Override
        public String name() {
            return "calculator";
        }

        @Override
        public String description() {
            return "计算二元四则运算";
        }

        @Override
        public Class<String> inputType() {
            return String.class;
        }

        @Override
        public String execute(String arguments) {
            return "1081";
        }
    };

    @Test
    void fullPassSuite() {
        MockToolCallingChatModel model = new MockToolCallingChatModel();
        AgentDefinition agent = AgentDefinition.builder()
                .name("calculator")
                .systemPrompt("你是计算助手")
                .tool(CALCULATOR)
                .loopStrategy(new ReActLoop(model))
                .maxSteps(5)
                .build();

        // 两个用例共用同一个"剧本"模型：每用例 2 轮模型调用
        model.scriptToolCall("calculator", "23*47").scriptAnswer("结果是 1081");
        model.scriptToolCall("calculator", "1+1").scriptAnswer("结果是 2");

        TestCase case1 = TestCase.builder()
                .id("calc-001").name("乘法运算").input("23*47 等于多少")
                .assertion(TestCase.runCompleted())
                .assertion(TestCase.answerContains("1081"))
                .assertion(TestCase.toolCalled("calculator"))
                .build();
        TestCase case2 = TestCase.builder()
                .id("calc-002").name("加法运算").input("1+1 等于多少")
                .assertion(TestCase.runCompleted())
                .assertion(TestCase.answerContains("2"))
                .build();

        HarnessRunner runner = new HarnessRunner(new AgentRuntime());
        List<CaseResult> results = runner.run(agent, List.of(case1, case2));

        assertEquals(2, results.size());
        assertTrue(results.get(0).passed());
        assertTrue(results.get(1).passed());

        String report = MarkdownReport.render("calculator 冒烟", results);
        assertTrue(report.contains("通过率: 100%"));
        assertTrue(report.contains("calc-001"));
    }

    @Test
    void failingAssertionMarksCaseFailed() {
        MockToolCallingChatModel model = new MockToolCallingChatModel();
        AgentDefinition agent = AgentDefinition.builder()
                .name("calculator")
                .loopStrategy(new ReActLoop(model))
                .maxSteps(3)
                .build();

        model.scriptAnswer("42"); // 模型答错

        TestCase badCase = TestCase.builder()
                .id("calc-003").name("期望错误答案的用例").input("1+1")
                .assertion(TestCase.answerContains("1081")) // 期望 1081，实际 42 → 必失败
                .build();

        List<CaseResult> results = new HarnessRunner(new AgentRuntime()).run(agent, List.of(badCase));

        assertFalse(results.get(0).passed());
        String report = MarkdownReport.render("calculator 冒烟", results);
        assertTrue(report.contains("FAIL"));
    }
}
