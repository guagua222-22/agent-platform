package com.agentplatform.harness.skill;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.skill.Skill;
import com.agentplatform.core.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 注册中心：平台内所有技能包的唯一登记处，并负责把技能"编译"进 AgentDefinition。
 *
 * 与 ToolRegistry 的关系：ToolRegistry 管工具零件（重名 fail-fast、全局目录），
 * SkillRegistry 管打包好的技能；Agent 装配时按技能名取用，不再直接面对零散工具——
 * "工具 -> 技能 -> Agent"三级装配是 Harness 构建层职责的核心体现。
 */
public class SkillRegistry {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry register(Skill skill) {
        if (skills.containsKey(skill.name())) {
            throw new IllegalStateException("技能重名: " + skill.name() + " 已注册过");
        }
        skills.put(skill.name(), skill);
        return this;
    }

    public Skill get(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("技能未注册: " + name);
        }
        return skill;
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    /**
     * 把技能"编译"进 Agent 定义：工具并入 tools，提示词片段追加到 systemPrompt 末尾。
     * 为什么片段要追加而非替换：技能之间不互相覆盖，Agent 的基础人设（调用方先设置的
     * systemPrompt）与技能规则（promptSnippet）分层共存，改技能不会动到人设。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public AgentDefinition.Builder applySkill(AgentDefinition.Builder builder, String skillName) {
        Skill skill = get(skillName);
        for (Tool tool : skill.tools()) {
            builder.tool(tool);
        }
        if (!skill.promptSnippet().isBlank()) {
            String base = builder.systemPrompt();
            builder.systemPrompt(base.isBlank() ? skill.promptSnippet()
                    : base + "\n" + skill.promptSnippet());
        }
        return builder;
    }

    /** 技能目录：name + 描述 + 工具名列表，供 Agent 目录页与 SubAgent 编排展示 */
    public String catalog() {
        return skills.values().stream()
                .map(s -> s.name() + " — " + s.description() + " [工具: "
                        + s.tools().stream().map(Tool::name).collect(Collectors.joining(", "))
                        + "]")
                .collect(Collectors.joining("\n"));
    }
}
