package com.agentplatform.harness.prompt;

import java.util.Map;

/**
 * 提示词模板：变量占位 + 版本号。
 *
 * 为什么提示词要模板化并带版本：提示词是 Agent 行为的"源代码"，
 * 版本号让 M1 起的"提示词回归测试"能定位"改到哪个版本导致效果退化"（prompt 防漂移），
 * 这是 Agent 工程区别于普通 CRUD 的核心工程实践。
 */
public class PromptTemplate {

    private final String name;
    private final int version;
    private final String template;

    public PromptTemplate(String name, int version, String template) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("模板必须有名，它是版本追踪的锚点");
        }
        this.name = name;
        this.version = version;
        this.template = template;
    }

    /**
     * 渲染模板：把 ${key} 替换为变量值。
     * 为什么残留未替换的占位符要抛异常：提示词里漏传变量（如 ${user_name} 原样进模型）
     * 会静默降低回答质量，且极难事后排查；fail-fast 让装配错误在开发期暴露。
     */
    public String render(Map<String, Object> variables) {
        String out = template;
        for (Map.Entry<String, Object> e : variables.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        if (out.contains("${")) {
            throw new IllegalStateException("模板 [" + name + " v" + version + "] 渲染后仍有未替换变量: " + out);
        }
        return out;
    }

    public String getName() { return name; }
    public int getVersion() { return version; }
    public String getTemplate() { return template; }
}
