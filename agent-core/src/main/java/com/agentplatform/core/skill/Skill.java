package com.agentplatform.core.skill;

import com.agentplatform.core.tool.Tool;

import java.util.List;

/**
 * Skill（技能包）：工具 + 提示词片段 + 描述的最小组合单元。
 *
 * 为什么需要 Skill 这一层抽象（而不直接把工具塞进 AgentDefinition）：
 * 真实业务里"工具+使用说明"是绑定的——光有 calculator 工具、没有"先算后答"的提示词约束，
 * 模型照样自由发挥。Skill 把二者打包成可复用资产：
 *   - 装配多个 Agent 时复用同一技能（如 math 技能同时服务 calculator 和 assistant）；
 *   - 技能可独立版本化、独立进评测（M2-D 技能级评测用例）；
 *   - M3 的 SubAgent 将直接以 Skill 为粒度授权（某子 Agent 只允许持有哪些技能）。
 *
 * 面试话术：Skill = Agent 的"插件"，对应 LangChain 的 ToolKit / OpenAI 的 GPTs Action 组合。
 */
public record Skill(
        /** 技能名：Agent 装配与评测报告中的引用锚点 */
        String name,
        /** 一句话说明能力边界，供 Agent 目录页与 SubAgent 编排展示 */
        String description,
        /** 提示词片段：拼入 Agent systemPrompt 末尾，声明本技能的使用规则 */
        String promptSnippet,
        /** 技能携带的工具集合 */
        List<Tool<?>> tools) {

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill 必须有名：它是装配与评测的锚点");
        }
        if (tools == null || tools.isEmpty()) {
            // 没有工具的"技能"只是提示词，那应该直接写进 systemPrompt，不该进 Skill 库
            throw new IllegalArgumentException("Skill 至少携带一个工具: " + name);
        }
        tools = List.copyOf(tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description = "";
        private String promptSnippet = "";
        private List<Tool<?>> tools = List.of();

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder promptSnippet(String promptSnippet) { this.promptSnippet = promptSnippet; return this; }
        public Builder tools(List<Tool<?>> tools) { this.tools = tools; return this; }

        public Skill build() {
            return new Skill(name, description, promptSnippet, tools);
        }
    }
}
