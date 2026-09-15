package com.agentplatform.harness.tool;

import com.agentplatform.core.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心：平台内所有工具的唯一登记处。
 *
 * 为什么工具必须集中注册而非随用随 new：
 * 1. 名称冲突要 fail-fast——两个工具同名会让模型的选择歧义，是隐蔽的线上事故源；
 * 2. 集中登记是 M3 工具级权限（ACL）与全局工具目录（/api/tools）的数据基础。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("工具重名: " + tool.name() + " 已注册过，模型将无法区分二者");
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具未注册: " + name);
        }
        return tool;
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }
}
