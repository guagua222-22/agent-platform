package com.agentplatform.app.eval;

import com.agentplatform.harness.eval.MockChatModel;
import com.agentplatform.harness.eval.TestCase;

import java.util.function.Consumer;

/**
 * 一条评测场景 = 用例 + mock 剧本。
 *
 * 为什么剧本与用例绑定成对：mock 评测里模型是"按剧本演戏"的，
 * 剧本模拟的是"理想模型在该输入下应有的行为"——断言验证的是
 * 我们的工程链路（Loop 驱动、工具路由、事件流、报告）在理想行为下是否全部正确。
 * 模型本身的质量由真模型集成测试（@Tag("integration")）用同一批用例验证。
 *
 * @param agentName 被测 Agent（AgentConfig 装配产物之一）
 * @param testCase  输入 + 断言
 * @param script    向 mock 模型预置响应序列
 */
public record EvalScenario(String agentName, TestCase testCase, Consumer<MockChatModel> script) {
}
