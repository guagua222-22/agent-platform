package com.agentplatform.app.api;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.runtime.AgentRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Agent 对外 API。
 *
 * SSE 为什么是本项目的事件推送选型：对话是"服务端持续产出、客户端只读"的单向流，
 * SSE 基于 HTTP、天然支持断线重连、无需升级协议；WebSocket 是全双工，
 * 适合需要客户端频繁主动发消息的场景，用在这里属于杀鸡用牛刀且增加部署复杂度。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final Map<String, AgentDefinition> agents;
    private final AgentRuntime runtime;

    public AgentController(Map<String, AgentDefinition> agents, AgentRuntime runtime) {
        this.agents = agents;
        this.runtime = runtime;
    }

    @GetMapping
    public List<AgentInfo> list() {
        return agents.values().stream()
                .map(a -> new AgentInfo(a.getName(), a.getDescription(),
                        a.getTools().stream().map(t -> t.name()).toList(), a.getMaxSteps()))
                .toList();
    }

    @PostMapping(value = "/{name}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String name, @RequestParam String message) {
        AgentDefinition definition = agents.get(name);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: " + name);
        }

        // 超时 120s：Agent 单次运行超过该时长视为异常，客户端可据此做超时兜底
        SseEmitter emitter = new SseEmitter(120_000L);

        // 为什么用虚拟线程：Agent 运行是"IO 密集 + 长耗时"（模型调用以秒计），
        // 每个请求占一个平台线程会很快耗尽 Servlet 线程池；
        // 虚拟线程由 JVM 调度、按 IO 阻塞自动挂起，几乎零成本支撑高并发长连接。
        // M1 会把它收敛成 Runtime 内部的专用执行器，而不是散落在 Controller。
        Thread.ofVirtual().start(() -> {
            try {
                runtime.run(definition, message, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type().name())
                                .data(event.detail(), MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        // 客户端断开连接：M1 引入取消令牌后这里会主动终止运行，
                        // M0 只抛出标记异常（被 Runtime 降级为日志，不打断 Agent 本身）
                        throw new ClientGoneException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    public record AgentInfo(String name, String description, List<String> tools, int maxSteps) {
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(IOException cause) {
            super(cause);
        }
    }
}
