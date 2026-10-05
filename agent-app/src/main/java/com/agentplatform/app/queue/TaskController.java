package com.agentplatform.app.queue;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.runtime.persistence.RunRepository;
import com.agentplatform.runtime.queue.AgentTask;
import com.agentplatform.runtime.queue.AgentTaskQueue;
import com.agentplatform.runtime.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务 API（M3-B）：提交即返回，客户端轮询状态。
 *
 * 为什么提交时就落一条 CREATED 记录：
 * 任务从"入队"到"消费者开始跑"之间有时间差，没有这条记录的话，
 * 客户端拿 taskId 查状态会查无此物——CREATED 记录把任务的完整生命周期
 * （已排队 -> 运行中 -> 终态）统一到 agent_run 一张表上。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final Map<String, AgentDefinition> agents;
    private final AgentTaskQueue queue;
    private final RunRepository repository;
    private final RateLimiter rateLimiter;

    public TaskController(Map<String, AgentDefinition> agents, AgentTaskQueue queue,
                          RunRepository repository, RateLimiter rateLimiter) {
        this.agents = agents;
        this.queue = queue;
        this.repository = repository;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<TaskSubmitResponse> submit(@RequestParam String agent, @RequestParam String message,
                                                     @RequestParam(required = false) String conversationId) {
        if (!agents.containsKey(agent)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: " + agent);
        }
        // 异步通道同样过限流：队列削峰不等于放弃入口控制，
        // 恶意提交把队列灌爆照样是事故
        if (!rateLimiter.tryAcquire("agent:" + agent)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }

        String runId = UUID.randomUUID().toString();
        // 先落 CREATED 记录（状态可查）再入队：两个动作都不是事务内的，
        // 顺序保证"查得到的一定会被执行"；反过来先入队则可能出现"在执行但查不到"
        AgentRun placeholder = new AgentRun(runId, agent, message);
        repository.saveRun(placeholder);
        queue.enqueue(new AgentTask(runId, agent,
                conversationId == null || conversationId.isBlank() ? null : conversationId, message));

        return ResponseEntity.accepted().body(new TaskSubmitResponse(runId, "QUEUED", "/api/tasks/" + runId));
    }

    @GetMapping("/{taskId}")
    public TaskStatus status(@PathVariable String taskId) {
        AgentRun run = repository.findRun(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
        return new TaskStatus(run.getId(), run.getAgentName(), run.getState().name(),
                run.getFinalAnswer(), run.getError(), run.getFinishedAt());
    }

    public record TaskSubmitResponse(String taskId, String status, String statusUrl) {
    }

    public record TaskStatus(String taskId, String agentName, String state,
                             String finalAnswer, String error, Instant finishedAt) {
    }
}
