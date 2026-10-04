package com.agentplatform.app.agent;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.skill.Skill;
import com.agentplatform.harness.knowledge.KnowledgeSearchTool;
import com.agentplatform.harness.loop.PlanExecuteLoop;
import com.agentplatform.harness.loop.ReActLoop;
import com.agentplatform.harness.memory.ConversationMemory;
import com.agentplatform.harness.memory.LayeredConversationMemory;
import com.agentplatform.harness.skill.SkillRegistry;
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
import java.util.List;
import java.util.Map;

/**
 * Agent 装配：Harness 零件（工具 -> 技能 -> Agent 三级组装）变成可执行的 Agent 定义。
 *
 * M2-C 引入的装配分层：
 *   工具（ToolRegistry，防重名）
 *   -> 技能（SkillRegistry，工具 + 使用规则提示词的复用包）
 *   -> Agent（基础人设 + 若干技能 + Loop 策略）
 * 同一技能可服务多个 Agent（math 技能同时挂在 calculator / assistant / planner 上），
 * 改技能规则只改一处——这正是 Harness"构建层"存在的意义。
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
    public ConversationMemory conversationMemory(JdbcTemplate jdbcTemplate, ChatModel model) {
        // 窗口 10 条（5 轮对话）：覆盖正常多轮交互的近端上下文，更早的交给摘要层压缩
        return new LayeredConversationMemory(jdbcTemplate, model, 10);
    }

    @Bean
    public ReActLoop reActLoop(ChatModel model, CheckpointStore checkpointStore, ConversationMemory memory) {
        // model 是 OpenAI 兼容适配器自动装配的 bean（当前指向千问 DashScope 端点）；
        // 换供应商（DeepSeek/通义/Ollama/Mock）只需改 yml 的 base-url，Loop 零改动
        return new ReActLoop(model, checkpointStore, memory);
    }

    @Bean
    public PlanExecuteLoop planExecuteLoop(ChatModel model) {
        return new PlanExecuteLoop(model);
    }

    @Bean
    public SkillRegistry skillRegistry(CalculatorTool calculator, TimeTool timeTool,
                                       KnowledgeSearchTool knowledgeSearch, ToolRegistry toolRegistry) {
        toolRegistry.register(calculator);
        toolRegistry.register(timeTool);
        toolRegistry.register(knowledgeSearch);

        SkillRegistry skills = new SkillRegistry();
        // 技能提示词片段即"工具使用说明书"：规则说死，降低模型自由发挥空间
        skills.register(Skill.builder()
                .name("math")
                .description("四则运算：二元加减乘除")
                .promptSnippet("【数学技能】用户问算术题时，必须先调用 calculator 工具计算，再复述算式和结果；" +
                        "参数是无空格、无等号的纯表达式（如 23*47）；拿到结果后直接回答，禁止重复调用。")
                .tools(List.of(calculator))
                .build());
        skills.register(Skill.builder()
                .name("time")
                .description("时间查询：当前日期时间，支持时区")
                .promptSnippet("【时间技能】用户问现在时间/日期时，调用 get_current_time；可按时区查询。")
                .tools(List.of(timeTool))
                .build());
        skills.register(Skill.builder()
                .name("knowledge-search")
                .description("知识库检索：从已上传文档中查找相关片段")
                .promptSnippet("【知识检索技能】回答问题前先调用 knowledge_search 检索知识库；" +
                        "严格基于检索片段回答，注明来源（格式【来源：《文档名》片段N】）；检索不足时明说，不得编造。")
                .tools(List.of(knowledgeSearch))
                .build());
        return skills;
    }

    @Bean
    public Map<String, AgentDefinition> agents(SkillRegistry skills,
                                               ReActLoop reactLoop, PlanExecuteLoop planExecuteLoop) {
        // 基础人设先行，技能片段由 applySkill 追加在后——人设与技能规则分层共存
        AgentDefinition calculatorAgent = skills.applySkill(AgentDefinition.builder()
                        .name("calculator")
                        .description("计算器助手：能做二元四则运算，回答数学题")
                        .systemPrompt("你是计算助手，用中文简洁回答。")
                        .loopStrategy(reactLoop)
                        .maxSteps(5),
                "math").build();

        // 纯对话 Agent：无技能（无工具）时 ReActLoop 一轮即结束
        AgentDefinition chatAgent = AgentDefinition.builder()
                .name("chat")
                .description("通用中文对话助手（带会话记忆）")
                .systemPrompt("你是一个友好、简洁的中文助手，直接回答用户问题。")
                .loopStrategy(reactLoop)
                .maxSteps(3)
                .build();

        // 通用任务 Agent：数学 + 时间双技能，模型按意图自主路由（工具调用路由的活例子）
        AgentDefinition.Builder assistantBuilder = AgentDefinition.builder()
                .name("assistant")
                .description("通用任务助手：会算数、会查时间（带会话记忆）")
                .systemPrompt("你是通用任务助手。按用户意图选择合适技能；与技能无关的闲聊直接回答。")
                .loopStrategy(reactLoop)
                .maxSteps(5);
        skills.applySkill(assistantBuilder, "math");
        AgentDefinition assistantAgent = skills.applySkill(assistantBuilder, "time").build();

        // RAG 知识库 Agent：检索技能单独成包，回答必须带来源引用（引用溯源是 RAG 可信度的命脉）
        AgentDefinition knowledgeAgent = skills.applySkill(AgentDefinition.builder()
                        .name("knowledge")
                        .description("知识库助手：基于已上传文档回答问题，并注明来源")
                        .systemPrompt("你是知识库助手，用中文回答。")
                        .loopStrategy(reactLoop)
                        .maxSteps(5),
                "knowledge-search").build();

        // 规划型 Agent：Plan-and-Execute 策略，适合多步骤复杂任务（先出计划再按图施工）
        AgentDefinition.Builder plannerBuilder = AgentDefinition.builder()
                .name("planner")
                .description("规划助手：先拆解任务为计划，再逐步执行并汇总（适合多步骤任务）")
                .systemPrompt("你是任务规划助手，擅长把复杂任务拆解成可执行步骤。")
                .loopStrategy(planExecuteLoop)
                .maxSteps(6);
        skills.applySkill(plannerBuilder, "math");
        AgentDefinition plannerAgent = skills.applySkill(plannerBuilder, "time").build();

        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        agents.put(calculatorAgent.getName(), calculatorAgent);
        agents.put(chatAgent.getName(), chatAgent);
        agents.put(assistantAgent.getName(), assistantAgent);
        agents.put(knowledgeAgent.getName(), knowledgeAgent);
        agents.put(plannerAgent.getName(), plannerAgent);
        return agents;
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
}
