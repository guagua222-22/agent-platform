package com.agentplatform.harness.eval;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.runtime.AgentRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 评测执行器：驱动 Runtime 逐个跑用例并收集判定，是 Harness 的"质量入口"。
 *
 * 为什么每个用例必须独立运行：Agent 的每次运行都有独立的消息历史与事件存档，
 * 共享运行会让用例之间互相污染（前一个用例的上下文泄漏进后一个），
 * 评测结果就失去了可复现性——可复现是评测体系的第一生命线。
 */
public class HarnessRunner {

    private final AgentRuntime runtime;

    public HarnessRunner(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "评测必须跑在真实 Runtime 上，否则测的不是生产路径");
    }

    /**
     * @param agent 被测 Agent 定义（同一批用例可用于回归不同版本的 Agent）
     * @param cases 用例集
     * @return 每个用例一份结果（顺序与输入一致）
     */
    public List<CaseResult> run(AgentDefinition agent, List<TestCase> cases) {
        List<CaseResult> results = new ArrayList<>(cases.size());
        for (TestCase testCase : cases) {
            long start = System.currentTimeMillis();
            AgentRun run = runtime.run(agent, testCase.getInput());
            long durationMs = System.currentTimeMillis() - start;

            List<Assertion.AssertionResult> assertionResults = new ArrayList<>();
            for (Assertion assertion : testCase.getAssertions()) {
                Assertion.AssertionResult r = assertion.evaluate(run);
                assertionResults.add(new Assertion.AssertionResult(assertion.describe(), r.passed(), r.detail()));
            }
            results.add(new CaseResult(testCase, run, assertionResults, durationMs));
        }
        return results;
    }
}
