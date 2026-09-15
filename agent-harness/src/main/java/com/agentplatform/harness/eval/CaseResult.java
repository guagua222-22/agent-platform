package com.agentplatform.harness.eval;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentRun;

import java.util.List;

/**
 * 单个用例的评测结果：原始运行记录 + 全部断言判定 + 耗时。
 */
public record CaseResult(TestCase testCase, AgentRun run, List<Assertion.AssertionResult> assertionResults,
                         long durationMs) {

    /** 所有断言全过才算通过：一条不过，整个用例不过（评测宁严勿松） */
    public boolean passed() {
        return assertionResults.stream().allMatch(Assertion.AssertionResult::passed);
    }
}
