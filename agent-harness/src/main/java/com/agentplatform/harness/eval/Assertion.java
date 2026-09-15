package com.agentplatform.harness.eval;

import com.agentplatform.core.model.AgentRun;

/**
 * 评测断言：对一次 Agent 运行结果给出通过/不通过的判定。
 *
 * 为什么断言要独立成接口：M0 只有规则断言（答案包含、工具被调用），
 * M3 要加 LLM-as-judge（让 DeepSeek 当评委判语义质量）、M4 要加成本断言
 * （Token 消耗、延迟上限）——断言类型必然增长，接口保证 HarnessRunner 不随断言种类改代码。
 */
public interface Assertion {

    /** 人类可读的断言描述（写进报告） */
    String describe();

    /** 对一次运行做判定 */
    AssertionResult evaluate(AgentRun run);

    record AssertionResult(String assertion, boolean passed, String detail) {
    }
}
