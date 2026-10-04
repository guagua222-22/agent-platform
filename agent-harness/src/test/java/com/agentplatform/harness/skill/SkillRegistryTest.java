package com.agentplatform.harness.skill;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.LoopStrategy;
import com.agentplatform.core.skill.Skill;
import com.agentplatform.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    private record Args(String expression) {
    }

    private static final Tool<Args> CALC = new Tool<>() {
        @Override
        public String name() { return "calculator"; }

        @Override
        public String description() { return "计算"; }

        @Override
        public Class<Args> inputType() { return Args.class; }

        @Override
        public String execute(Args args) { return "1"; }
    };

    private static final Tool<Args> CLOCK = new Tool<>() {
        @Override
        public String name() { return "get_current_time"; }

        @Override
        public String description() { return "查时间"; }

        @Override
        public Class<Args> inputType() { return Args.class; }

        @Override
        public String execute(Args args) { return "now"; }
    };

    private Skill mathSkill() {
        return Skill.builder()
                .name("math")
                .description("四则运算")
                .promptSnippet("数学技能：算数必须先调用 calculator，拿到结果后直接回答。")
                .tools(List.of(CALC))
                .build();
    }

    private Skill timeSkill() {
        return Skill.builder()
                .name("time")
                .description("时间查询")
                .promptSnippet("时间技能：问时间用 get_current_time。")
                .tools(List.of(CLOCK))
                .build();
    }

    /** 技能编译进 Agent：工具并入 + 片段追加到基础人设之后，且人设在前片段在后 */
    @Test
    void applySkillMergesToolsAndAppendsSnippet() {
        SkillRegistry registry = new SkillRegistry().register(mathSkill()).register(timeSkill());

        AgentDefinition.Builder builder = AgentDefinition.builder()
                .name("assistant")
                .systemPrompt("你是通用助手。")
                .loopStrategy(dummyLoop());
        registry.applySkill(builder, "math");
        registry.applySkill(builder, "time");
        AgentDefinition agent = builder.build();

        assertEquals(2, agent.getTools().size());
        String prompt = agent.getSystemPrompt();
        assertTrue(prompt.startsWith("你是通用助手。"), "基础人设必须在前");
        assertTrue(prompt.contains("数学技能"), "数学技能片段应注入");
        assertTrue(prompt.contains("时间技能"), "时间技能片段应注入");
        assertTrue(prompt.indexOf("数学技能") < prompt.indexOf("时间技能"), "片段按装配顺序追加");
    }

    /** 重名技能 fail-fast：与工具重名同理，模型将无法区分 */
    @Test
    void duplicateSkillNameRejected() {
        SkillRegistry registry = new SkillRegistry().register(mathSkill());
        assertThrows(IllegalStateException.class, () -> registry.register(mathSkill()));
    }

    /** 未注册的技能名必须报错而不是静默跳过——装配错误要在启动期暴露 */
    @Test
    void unknownSkillRejected() {
        SkillRegistry registry = new SkillRegistry();
        AgentDefinition.Builder builder = AgentDefinition.builder().name("x").loopStrategy(dummyLoop());
        assertThrows(IllegalArgumentException.class, () -> registry.applySkill(builder, "not_exist"));
    }

    /** 空工具技能没有意义：提示词片段应直接写进 systemPrompt */
    @Test
    void skillWithoutToolsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Skill.builder().name("empty").tools(List.of()).build());
    }

    private LoopStrategy dummyLoop() {
        return new LoopStrategy() {
            @Override
            public String name() { return "dummy"; }

            @Override
            public void execute(AgentDefinition definition, com.agentplatform.core.model.AgentRun run,
                                com.agentplatform.core.agent.AgentEventSink sink) {
                run.complete("ok");
            }
        };
    }
}
