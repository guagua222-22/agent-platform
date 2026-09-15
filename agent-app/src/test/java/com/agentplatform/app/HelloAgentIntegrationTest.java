package com.agentplatform.app;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.RunState;
import com.agentplatform.runtime.AgentRuntime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hello Agent 集成测试：真调千问（DashScope OpenAI 兼容端点）验证
 * "Harness 组装的 Agent 能在 Runtime 上正确运行"。
 *
 * 为什么标记 @Tag("integration") 并被默认排除：
 * 它花钱、依赖网络与 AI_API_KEY，必须与零成本的 mock 单测分开执行；
 * 跑法：export AI_API_KEY=sk-xxx && ./mvnw test -DexcludedGroups=
 */
@Tag("integration")
@SpringBootTest
class HelloAgentIntegrationTest {

    @Autowired
    private Map<String, AgentDefinition> agents;

    @Autowired
    private AgentRuntime runtime;

    @Test
    void calculatorAgentAnswersArithmeticWithTool() {
        AgentDefinition calculator = agents.get("calculator");
        assertNotNull(calculator, "calculator Agent 应已装配");

        // 全闭环验证：Runtime 执行 -> 模型选择工具 -> 工具算出 1081 -> 模型组织中文回答
        var run = runtime.run(calculator, "请帮我计算 23*47 等于多少");

        assertEquals(RunState.COMPLETED, run.getState(), "运行应正常结束: " + run.getError());
        assertNotNull(run.getFinalAnswer());
        assertTrue(run.getFinalAnswer().contains("1081"),
                "最终回答应包含正确结果 1081，实际: " + run.getFinalAnswer());
        assertTrue(run.getEvents().stream().anyMatch(
                        e -> e.type() == com.agentplatform.core.model.AgentEvent.EventType.TOOL_CALLED
                                && e.detail().startsWith("calculator(")),
                "事件流中应包含 calculator 工具的调用记录");
    }
}
