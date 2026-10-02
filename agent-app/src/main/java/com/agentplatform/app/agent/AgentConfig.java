package com.agentplatform.app.agent;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.harness.loop.ReActLoop;
import com.agentplatform.harness.knowledge.KnowledgeSearchTool;
import com.agentplatform.harness.tool.ToolRegistry;
import com.agentplatform.runtime.AgentRuntime;
import com.agentplatform.runtime.checkpoint.CheckpointStore;
import com.agentplatform.runtime.checkpoint.RedisCheckpointStore;
import com.agentplatform.runtime.persistence.JdbcRunRepository;
import com.agentplatform.runtime.persistence.RunRepository;
import com.agentplatform.runtime.ratelimit.RateLimiter;
import com.agentplatform.runtime.ratelimit.RedisRateLimiter;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

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
    public CheckpointStore checkpointStore(StringRedisTemplate redisTemplate) {
        return new RedisCheckpointStore(redisTemplate);
    }

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate redisTemplate) {
        // 演示参数：桶容量 10（允许短时突发），每秒回填 1 个令牌——持续高频才会被限
        return new RedisRateLimiter(redisTemplate, 10, 1);
    }

    @Bean
    public ReActLoop reActLoop(ChatModel model, CheckpointStore checkpointStore) {
        // model 是 OpenAI 兼容适配器自动装配的 bean（当前指向千问 DashScope 端点）；
        // ReActLoop 只依赖抽象接口，换供应商（DeepSeek/通义/Ollama/Mock）时
        // 此处注入不同的 bean 或改 yml 的 base-url 即可，Loop 零改动
        return new ReActLoop(model, checkpointStore);
    }

    @Bean
    public RunRepository runRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRunRepository(jdbcTemplate);
    }

    @Bean(destroyMethod = "close")
    public AgentRuntime agentRuntime(RunRepository repository) {
        // destroyMethod=close：应用停机时回收虚拟线程执行器，避免线程泄漏
        return new AgentRuntime(repository);
    }

    @Bean
    public Map<String, AgentDefinition> agents(ToolRegistry registry, CalculatorTool calculator,
                                               TimeTool timeTool, KnowledgeSearchTool knowledgeSearch,
                                               ReActLoop loop) {
        registry.register(calculator);
        registry.register(timeTool);
        registry.register(knowledgeSearch);

        // 工具型 Agent：强调"先算后答"，提示词把工具使用规则说死，降低模型自由发挥空间
        AgentDefinition calculatorAgent = AgentDefinition.builder()
                .name("calculator")
                .description("计算器助手：能做二元四则运算，回答数学题")
                .systemPrompt("你是计算助手。用户问算术题时，必须先调用 calculator 工具计算，再用中文复述算式和结果。" +
                        "工具参数必须是无空格、无等号的纯表达式，如 23*47。" +
                        "调用工具并拿到结果后，必须直接回答用户，禁止重复调用工具。")
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

        // 通用任务 Agent：多工具组合，模型按意图自主路由（面试讲"工具调用路由"的活例子）
        AgentDefinition assistantAgent = AgentDefinition.builder()
                .name("assistant")
                .description("通用任务助手：会算数、会查时间")
                .systemPrompt("你是通用任务助手，有两个工具可用：计算用 calculator，问时间用 get_current_time。" +
                        "按用户意图选择合适工具；与工具无关的闲聊直接回答。" +
                        "工具拿到结果后必须直接回答，禁止重复调用。")
                .tool(calculator)
                .tool(timeTool)
                .loopStrategy(loop)
                .maxSteps(5)
                .build();

        // RAG 知识库 Agent：先检索后回答，回答必须带来源引用（引用溯源是 RAG 可信度的命脉）
        AgentDefinition knowledgeAgent = AgentDefinition.builder()
                .name("knowledge")
                .description("知识库助手：基于已上传文档回答问题，并注明来源")
                .systemPrompt("你是知识库助手。回答用户问题前，必须先调用 knowledge_search 工具检索知识库。" +
                        "严格基于检索到的片段回答，并在答案中注明来源文档与片段编号，格式如【来源：《文档名》片段N】。" +
                        "若检索结果不足以回答，直接说明知识库信息不足，不得编造。")
                .tool(knowledgeSearch)
                .loopStrategy(loop)
                .maxSteps(5)
                .build();

        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        agents.put(calculatorAgent.getName(), calculatorAgent);
        agents.put(chatAgent.getName(), chatAgent);
        agents.put(assistantAgent.getName(), assistantAgent);
        agents.put(knowledgeAgent.getName(), knowledgeAgent);
        return agents;
    }
}
