package com.agentplatform.app.agent;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.harness.loop.ReActLoop;
import com.agentplatform.harness.tool.ToolRegistry;
import com.agentplatform.runtime.AgentRuntime;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 装配：Harness 零件（工具、Loop 策略）组装成可执行的 Agent 定义。
 *
 * 为什么 Agent 注册表用 Map 而非数据库：M0 只有两个内置 Agent，
 * M1 加持久化时 AgentDefinition 落 MySQL，注册表随之改为"启动时加载 + 缓存"，
 * 但"定义与装配分离"的结构不变——装配就是 Harness 的职责边界。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public ReActLoop reActLoop(ChatModel model) {
        // model 是 OpenAI 兼容适配器自动装配的 bean（当前指向千问 DashScope 端点）；
        // ReActLoop 只依赖抽象接口，换供应商（DeepSeek/通义/Ollama/Mock）时
        // 此处注入不同的 bean 或改 yml 的 base-url 即可，Loop 零改动
        return new ReActLoop(model);
    }

    @Bean
    public AgentRuntime agentRuntime() {
        return new AgentRuntime();
    }

    @Bean
    public Map<String, AgentDefinition> agents(ToolRegistry registry, CalculatorTool calculator, ReActLoop loop) {
        registry.register(calculator);

        // 工具型 Agent：强调"先算后答"，提示词把工具使用规则说死，降低模型自由发挥空间
        AgentDefinition calculatorAgent = AgentDefinition.builder()
                .name("calculator")
                .description("计算器助手：能做二元四则运算，回答数学题")
                .systemPrompt("你是计算助手。用户问算术题时，必须先调用 calculator 工具计算，再用中文复述算式和结果。" +
                        "工具参数必须是无空格、无等号的纯表达式，如 23*47。")
                .tool(calculator)
                .loopStrategy(loop)
                .maxSteps(5)
                .build();

        // 纯对话 Agent：无工具时 ReActLoop 一轮即结束（模型直接回答，不产生工具调用）
        AgentDefinition chatAgent = AgentDefinition.builder()
                .name("chat")
                .description("通用中文对话助手")
                .systemPrompt("你是一个友好、简洁的中文助手，直接回答用户问题。")
                .loopStrategy(loop)
                .maxSteps(3)
                .build();

        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        agents.put(calculatorAgent.getName(), calculatorAgent);
        agents.put(chatAgent.getName(), chatAgent);
        return agents;
    }
}
