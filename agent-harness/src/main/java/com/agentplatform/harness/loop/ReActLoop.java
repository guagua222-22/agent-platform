package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.agent.LoopStrategy;
import com.agentplatform.core.agent.MaxStepsExceededException;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.util.Strings;
import com.agentplatform.harness.memory.ConversationMemory;
import com.agentplatform.runtime.checkpoint.Checkpoint;
import com.agentplatform.runtime.checkpoint.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ReAct 循环：思考(Reason) -> 行动(Act) -> 观察(Observe) 的往复，直到模型给出最终回答。
 *
 * 为什么关闭 Spring AI 的内置工具执行（internalToolExecutionEnabled=false）：
 * 框架默认会在内部自动完成"调工具->回填"的循环，过程不可见；
 * 但我们要的恰恰是过程可见——每一步都广播事件（SSE 实时推送、评测断言工具调用、
 * Tracing 记录耗时），所以循环必须由自己驱动，这也是面试时能讲清 ReAct 原理的前提。
 *
 * Checkpoint（M1-C）：每轮循环结束后把消息历史快照存入 CheckpointStore（Redis），
 * 运行中断后可用 {@link #executeResume} 从快照继续，不重放已完成的模型调用——
 * 断点续跑 = 省钱 + 省时 + 体验不中断。
 */
public class ReActLoop implements LoopStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final ChatModel model;
    private final CheckpointStore checkpointStore;
    private final ConversationMemory memory;

    public ReActLoop(ChatModel model) {
        this(model, null, null);
    }

    public ReActLoop(ChatModel model, CheckpointStore checkpointStore) {
        this(model, checkpointStore, null);
    }

    public ReActLoop(ChatModel model, CheckpointStore checkpointStore, ConversationMemory memory) {
        this.model = model;
        this.checkpointStore = checkpointStore;
        this.memory = memory;
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
        // 会话记忆（M2-C）：带 conversationId 的运行注入"摘要 + 最近窗口"，
        // 放在 system 之后、本次输入之前——人设 > 历史 > 当前问题的优先级次序
        if (memory != null && run.getConversationId() != null) {
            for (ConversationMemory.MemoryMessage m : memory.load(run.getConversationId())) {
                history.add(toHistoryMessage(m));
            }
            sink.emit(AgentEvent.of(AgentEvent.EventType.MEMORY_LOADED,
                    "conversation=" + run.getConversationId()));
        }
        history.add(new UserMessage(run.getInput()));
        runLoop(definition, run, sink, history, 0);
        // 对话闭环后回写记忆：只存"用户问 + 最终答"，中间工具过程是噪声不该进记忆
        if (memory != null && run.getConversationId() != null && run.getFinalAnswer() != null) {
            memory.append(run.getConversationId(), definition.getName(), run.getInput(), run.getFinalAnswer());
        }
    }

    private Message toHistoryMessage(ConversationMemory.MemoryMessage m) {
        return switch (m.role()) {
            case "system" -> new SystemMessage(m.content());
            case "assistant" -> new AssistantMessage(m.content());
            default -> new UserMessage(m.content());
        };
    }

    /**
     * 断点续跑：从 checkpoint 恢复消息历史，从 step+1 继续循环。
     * 恢复后的运行不再重放已完成步骤——模型调用是花钱的，重放即浪费。
     */
    public void executeResume(AgentDefinition definition, AgentRun run, AgentEventSink sink, Checkpoint checkpoint) {
        List<Message> history = checkpoint.messages().stream()
                .map(this::toMessage)
                .collect(Collectors.toCollection(ArrayList::new));
        runLoop(definition, run, sink, history, checkpoint.step());
    }

    /** 循环核心：execute（从零）与 executeResume（从中途）共用同一份逻辑 */
    private void runLoop(AgentDefinition definition, AgentRun run, AgentEventSink sink,
                         List<Message> history, int completedSteps) {
        // 工具适配只做一次：ToolCallback 既是模型侧的 Schema 声明，也是执行侧的反序列化入口。
        // 执行工具必须走 callback.call()（JSON -> 参数对象 -> 调用），直接调 Tool 会撞上类型墙
        ToolCallback[] callbacks = ToolCallbacks.of(definition);
        Map<String, ToolCallback> callbacksByName = ToolCallbacks.byName(callbacks);

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        for (int step = completedSteps + 1; step <= definition.getMaxSteps(); step++) {
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
                    ToolCallback callback = callbacksByName.get(call.name());
                    if (callback == null) {
                        // 模型幻觉出未注册的工具名：把错误当观察回填，让模型自我纠正
                        throw new IllegalArgumentException("模型调用了不存在的工具: " + call.name());
                    }
                    result = callback.call(call.arguments());
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
            saveCheckpoint(run, step, history); // 本轮闭环完成，保存现场供断点恢复
            sink.emit(AgentEvent.of(AgentEvent.EventType.STEP_COMPLETED,
                    "step=" + step + " toolCalls=" + toolResponses.size()));
        }
        throw new MaxStepsExceededException(definition.getName(), definition.getMaxSteps());
    }

    private void saveCheckpoint(AgentRun run, int step, List<Message> history) {
        if (checkpointStore == null) {
            return;
        }
        try {
            List<Checkpoint.CheckpointMessage> messages = history.stream()
                    .map(this::toCheckpointMessage)
                    .toList();
            checkpointStore.save(new Checkpoint(run.getId(), step, messages, run.getFinalAnswer()));
        } catch (Exception e) {
            // checkpoint 是尽力而为旁路：保存失败不影响运行本身
            log.warn("checkpoint 保存失败已忽略 run={}", run.getId(), e);
        }
    }

    /** spring-ai 消息 -> 可序列化快照 */
    private Checkpoint.CheckpointMessage toCheckpointMessage(Message m) {
        if (m instanceof SystemMessage sm) {
            return new Checkpoint.CheckpointMessage("system", sm.getText(), List.of(), List.of());
        }
        if (m instanceof UserMessage um) {
            return new Checkpoint.CheckpointMessage("user", um.getText(), List.of(), List.of());
        }
        if (m instanceof AssistantMessage am) {
            List<Checkpoint.CheckpointToolCall> calls = am.getToolCalls() == null ? List.of()
                    : am.getToolCalls().stream()
                            .map(tc -> new Checkpoint.CheckpointToolCall(tc.id(), tc.name(), tc.arguments()))
                            .toList();
            return new Checkpoint.CheckpointMessage("assistant", am.getText(), calls, List.of());
        }
        if (m instanceof ToolResponseMessage trm) {
            List<Checkpoint.CheckpointToolResponse> responses = trm.getResponses().stream()
                    .map(r -> new Checkpoint.CheckpointToolResponse(r.id(), r.name(), r.responseData()))
                    .toList();
            return new Checkpoint.CheckpointMessage("tool", "", List.of(), responses);
        }
        throw new IllegalStateException("未知消息类型无法快照: " + m.getClass());
    }

    /** 快照 -> spring-ai 消息（恢复路径） */
    private Message toMessage(Checkpoint.CheckpointMessage cm) {
        return switch (cm.role()) {
            case "system" -> new SystemMessage(cm.content());
            case "user" -> new UserMessage(cm.content());
            case "assistant" -> cm.toolCalls().isEmpty()
                    ? new AssistantMessage(cm.content())
                    : AssistantMessage.builder()
                            .content(cm.content())
                            .toolCalls(cm.toolCalls().stream()
                                    .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.arguments()))
                                    .toList())
                            .build();
            case "tool" -> ToolResponseMessage.builder()
                    .responses(cm.toolResponses().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.content()))
                            .toList())
                    .build();
            default -> throw new IllegalStateException("未知角色无法恢复: " + cm.role());
        };
    }
}
