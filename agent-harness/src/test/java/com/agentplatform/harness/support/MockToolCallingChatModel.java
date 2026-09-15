package com.agentplatform.harness.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 脚本化假模型：按预设顺序返回响应，零成本、零网络。
 *
 * 为什么评测必须有 mock 模型：Agent 质量的日常回归不能依赖"网络通不通、Key 有没有、模型浪不浪"，
 * mock 模型把变量收敛到"我们自己的代码逻辑"，一次 5 分钟跑完；
 * 真模型的集成测试只在发布前全量跑（@Tag("integration")）。
 */
public class MockToolCallingChatModel implements ChatModel {

    private final Deque<ChatResponse> scripted = new ArrayDeque<>();
    private final List<Prompt> calls = new ArrayList<>();

    /** 预置一次模型响应：按加入顺序依次返回 */
    public MockToolCallingChatModel script(ChatResponse response) {
        scripted.add(response);
        return this;
    }

    /** 预置一轮"模型要调用工具"的响应 */
    public MockToolCallingChatModel scriptToolCall(String toolName, String arguments) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call_1", "function", toolName, arguments);
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return script(new ChatResponse(List.of(new Generation(message))));
    }

    /** 预置一轮"模型给出最终回答"的响应 */
    public MockToolCallingChatModel scriptAnswer(String text) {
        return script(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    public int callCount() {
        return calls.size();
    }

    public Prompt lastPrompt() {
        return calls.get(calls.size() - 1);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.add(prompt);
        ChatResponse next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("Mock 模型脚本耗尽：模型比预期多调了一次");
        }
        return next;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }
}
