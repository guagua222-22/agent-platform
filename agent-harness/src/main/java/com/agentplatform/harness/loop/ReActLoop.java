package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.agent.LoopStrategy;
import com.agentplatform.core.agent.MaxStepsExceededException;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.util.Strings;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ReAct 循环：思考(Reason) -> 行动(Act) -> 观察(Observe) 的往复，直到模型给出最终回答。
 *
 * 为什么关闭 Spring AI 的内置工具执行（internalToolExecutionEnabled=false）：
 * 框架默认会在内部自动完成"调工具->回填"的循环，过程不可见；
 * 但我们要的恰恰是过程可见——每一步都广播事件（SSE 实时推送、评测断言工具调用、
 * Tracing 记录耗时），所以循环必须由自己驱动，这也是面试时能讲清 ReAct 原理的前提。
 */
public class ReActLoop implements LoopStrategy {

    private final ChatModel model;

    public ReActLoop(ChatModel model) {
        this.model = model;
    }

    @Override
    public String name() {
        return "react";
    }

    @Override
    public void execute(AgentDefinition definition, AgentRun run, AgentEventSink sink) {
        // 消息历史：模型是无状态的，上下文全靠这段历史承载；SystemMessage 定义角色，UserMessage 是任务
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(definition.getSystemPrompt()));
        history.add(new UserMessage(run.getInput()));

        Map<String, Tool> toolsByName = definition.getTools().stream()
                .collect(Collectors.toMap(Tool::name, t -> t));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toToolCallbacks(definition))
                .internalToolExecutionEnabled(false)
                .build();

        for (int step = 1; step <= definition.getMaxSteps(); step++) {
            sink.emit(AgentEvent.of(AgentEvent.EventType.LLM_CALLED, "step=" + step));
            ChatResponse response = model.call(new Prompt(history, options));
            AssistantMessage assistant = response.getResult().getOutput();
            history.add(assistant); // 本轮 assistant 消息必须入史，下一轮模型才能"看到自己说过什么"

            if (!assistant.hasToolCalls()) {
                run.complete(assistant.getText());
                return;
            }

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                sink.emit(AgentEvent.of(AgentEvent.EventType.TOOL_CALLED,
                        call.name() + "(" + Strings.abbreviate(call.arguments()) + ")"));
                long start = System.currentTimeMillis();
                String result;
                String error = null;
                try {
                    Tool tool = toolsByName.get(call.name());
                    if (tool == null) {
                        // 模型幻觉出未注册的工具名：把错误当观察回填，让模型自我纠正
                        throw new IllegalArgumentException("模型调用了不存在的工具: " + call.name());
                    }
                    result = tool.execute(call.arguments());
                } catch (Exception e) {
                    result = null;
                    error = e.getMessage();
                }
                sink.emit(AgentEvent.of(AgentEvent.EventType.TOOL_RESULT,
                        "tool=" + call.name() + " durationMs=" + (System.currentTimeMillis() - start)
                                + (error == null ? " result=" + Strings.abbreviate(result) : " error=" + error)));
                // 工具失败也作为观察回填——ReAct 的"O"环节本就不区分成败，
                // 模型看到失败信息会自行换参数或换工具，这比直接终止更接近真实推理
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), error == null ? result : "工具执行失败: " + error));
            }
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());
            sink.emit(AgentEvent.of(AgentEvent.EventType.STEP_COMPLETED,
                    "step=" + step + " toolCalls=" + toolResponses.size()));
        }
        throw new MaxStepsExceededException(definition.getName(), definition.getMaxSteps());
    }

    /**
     * 平台 Tool 契约 -> Spring AI ToolCallback 的适配。
     * 按工具各自的 inputType 生成 JSON Schema，模型据此输出结构化参数。
     * 泛型在适配层必然擦除为 Object/Function 原始类型——这是框架边界的固有成本，
     * 用 @SuppressWarnings 收敛在最小范围。
     */
    private ToolCallback[] toToolCallbacks(AgentDefinition definition) {
        return definition.getTools().stream()
                .map(this::adapt)
                .toArray(ToolCallback[]::new);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolCallback adapt(Tool tool) {
        return FunctionToolCallback.builder(tool.name(), (Function) tool::execute)
                .description(tool.description())
                .inputType((Class) tool.inputType())
                .build();
    }
}
