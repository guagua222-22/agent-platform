package com.agentplatform.core.agent;

/**
 * Agent 在步数上限内仍未产出最终回答。
 * 为什么超步数是异常而不是普通返回：它通常是提示词或工具设计缺陷的信号
 * （模型反复调工具而不收敛），必须显式暴露给 Runtime 记 FAILED、给评测留断言依据，
 * 静默截断会掩盖真实质量问题。
 */
public class MaxStepsExceededException extends RuntimeException {

    public MaxStepsExceededException(String agentName, int maxSteps) {
        super("Agent [" + agentName + "] 超过最大步数 " + maxSteps + "，疑似工具或提示词缺陷");
    }
}
