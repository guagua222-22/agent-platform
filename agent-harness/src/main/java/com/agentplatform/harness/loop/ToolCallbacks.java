package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.tool.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loop 策略共用的工具适配：平台 Tool 契约 -> Spring AI ToolCallback。
 *
 * 为什么抽出来：ReActLoop 与 PlanExecuteLoop 都需要同一条适配链，
 * 各写一份会在"工具契约演进"（如 M0 的泛型化改造）时改两处，漏一处就是线上事故。
 *
 * 适配的两个职责：
 * 1. 按工具各自的 inputType 生成 JSON Schema，模型据此输出结构化参数；
 * 2. 执行时走 callback.call() 的"JSON -> 参数对象 -> 调用"链路——
 *    模型传来的 arguments 是原始 JSON 字符串，直接调 Tool.execute 会撞上类型墙。
 * 泛型在适配层必然擦除为 Object/Function 原始类型，用 @SuppressWarnings 收敛在最小范围。
 */
final class ToolCallbacks {

    private ToolCallbacks() {
    }

    static ToolCallback[] of(AgentDefinition definition) {
        return definition.getTools().stream()
                .map(ToolCallbacks::adapt)
                .toArray(ToolCallback[]::new);
    }

    static Map<String, ToolCallback> byName(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), cb -> cb));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ToolCallback adapt(Tool tool) {
        return FunctionToolCallback.builder(tool.name(), (Function) tool::execute)
                .description(tool.description())
                .inputType((Class) tool.inputType())
                .build();
    }
}
