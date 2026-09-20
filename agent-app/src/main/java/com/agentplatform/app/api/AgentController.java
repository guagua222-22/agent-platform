package com.agentplatform.app.api;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.runtime.AgentRuntime;
import com.agentplatform.runtime.persistence.RunRepository;
import com.agentplatform.runtime.ratelimit.RateLimiter;
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
import java.time.Instant;
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
@RequestMapping("/api")
public class AgentController {

    private final Map<String, AgentDefinition> agents;
    private final AgentRuntime runtime;
    private final RunRepository repository;
    private final RateLimiter rateLimiter;

    public AgentController(Map<String, AgentDefinition> agents, AgentRuntime runtime,
                           RunRepository repository, RateLimiter rateLimiter) {
        this.agents = agents;
        this.runtime = runtime;
        this.repository = repository;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/agents")
    public List<AgentInfo> list() {
        return agents.values().stream()
                .map(a -> new AgentInfo(a.getName(), a.getDescription(),
                        a.getTools().stream().map(t -> t.name()).toList(), a.getMaxSteps()))
                .toList();
    }

    @PostMapping(value = "/agents/{name}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String name, @RequestParam String message) {
        AgentDefinition definition = agents.get(name);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: " + name);
        }

        // 限流在最外层：LLM 调用是付费资源，必须在进入执行前拦截。
        // 维度按 agent 名——每个 Agent 独立配额，互不挤占
        if (!rateLimiter.tryAcquire("agent:" + name)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }

        // 超时 120s：Agent 单次运行超过该时长视为异常，客户端可据此做超时兜底
        SseEmitter emitter = new SseEmitter(120_000L);

        // 并发策略由 Runtime 的虚拟线程执行器统一管理（runAsync），
        // Controller 只声明意图、消费事件，不再自己管理线程
        runtime.runAsync(definition, message, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type().name())
                                .data(event.detail(), MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        // 客户端断开连接：M1-C 引入取消令牌后这里会主动终止运行，
                        // 目前只抛出标记异常（被 Runtime 降级为日志，不打断 Agent 本身）
                        throw new ClientGoneException(e);
                    }
                })
                .whenComplete((run, ex) -> {
                    if (ex != null) {
                        emitter.completeWithError(ex);
                    } else {
                        emitter.complete();
                    }
                });
        return emitter;
    }

    /** 运行历史：持久化的直接价值——应用重启后会话记录仍在 */
    @GetMapping("/runs")
    public List<RunSummary> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return repository.findRecent(limit).stream()
                .map(r -> new RunSummary(r.getId(), r.getAgentName(), r.getInput(),
                        r.getState().name(), r.getStartedAt(), r.getFinishedAt()))
                .toList();
    }

    /** 单次运行详情：记录 + 完整事件回放（演示 Checkpoint 恢复的数据基础） */
    @GetMapping("/runs/{runId}")
    public RunDetail runDetail(@PathVariable String runId) {
        AgentRun run = repository.findRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在: " + runId));
        List<AgentEvent> events = repository.findEvents(runId);
        return new RunDetail(run.getId(), run.getAgentName(), run.getInput(),
                run.getState().name(), run.getFinalAnswer(), run.getError(),
                run.getStartedAt(), run.getFinishedAt(), events);
    }

    public record AgentInfo(String name, String description, List<String> tools, int maxSteps) {
    }

    public record RunSummary(String id, String agentName, String input, String state,
                             Instant startedAt, Instant finishedAt) {
    }

    public record RunDetail(String id, String agentName, String input, String state,
                            String finalAnswer, String error,
                            Instant startedAt, Instant finishedAt, List<AgentEvent> events) {
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(IOException cause) {
            super(cause);
        }
    }
}
