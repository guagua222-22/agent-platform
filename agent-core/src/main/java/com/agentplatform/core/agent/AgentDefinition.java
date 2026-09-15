package com.agentplatform.core.agent;

import com.agentplatform.core.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 的静态定义（Harness 组装产物，Runtime 执行输入）。
 *
 * 为什么定义与运行分离（AgentDefinition vs AgentRun）：
 * 定义是可复用、可版本化的"配方"（同一 Agent 可被并发执行多次），
 * 运行是单次执行的状态快照；两者分离后，M1 的持久化存定义、Checkpoint 存运行，
 * 职责清晰，也为 M3 的 SubAgent 复用同一套契约打底。
 */
public class AgentDefinition {

    private final String name;
    private final String description;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final LoopStrategy loopStrategy;
    private final int maxSteps;

    private AgentDefinition(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.systemPrompt = b.systemPrompt;
        this.tools = Collections.unmodifiableList(new ArrayList<>(b.tools));
        this.loopStrategy = b.loopStrategy;
        this.maxSteps = b.maxSteps;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSystemPrompt() { return systemPrompt; }
    public List<Tool> getTools() { return tools; }
    public LoopStrategy getLoopStrategy() { return loopStrategy; }
    public int getMaxSteps() { return maxSteps; }

    public static class Builder {
        private String name;
        private String description = "";
        private String systemPrompt = "";
        private final List<Tool> tools = new ArrayList<>();
        private LoopStrategy loopStrategy;
        private int maxSteps = 10;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder tool(Tool tool) { this.tools.add(tool); return this; }
        public Builder tools(List<Tool> tools) { this.tools.addAll(tools); return this; }
        public Builder loopStrategy(LoopStrategy loopStrategy) { this.loopStrategy = loopStrategy; return this; }

        /** 步数上限：防止模型陷入"永远在调工具"的死循环，超限由 Loop 抛异常终止 */
        public Builder maxSteps(int maxSteps) { this.maxSteps = maxSteps; return this; }

        public AgentDefinition build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Agent 必须有名: 它同时是 API 路由与持久化主键");
            }
            if (loopStrategy == null) {
                throw new IllegalArgumentException("Agent 必须指定 Loop 策略: " + name);
            }
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps 至少为 1: " + name);
            }
            return new AgentDefinition(this);
        }
    }
}
