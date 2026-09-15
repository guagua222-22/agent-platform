package com.agentplatform.core.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次 Agent 运行的完整记录：状态 + 事件存档 + 最终结果。
 *
 * 为什么状态迁移要显式校验：生产环境中"运行中又被打成已完成"这类并发误改会导致
 * 会话数据错乱；把合法迁移路径编码进状态机，非法迁移直接抛异常，问题在发生点暴露
 * 而不是静默产生脏数据。
 */
public class AgentRun {

    private final String id = UUID.randomUUID().toString();
    private final String agentName;
    private final String input;
    private final Instant startedAt = Instant.now();
    private final List<AgentEvent> events = new ArrayList<>();

    private volatile RunState state = RunState.CREATED;
    private volatile String finalAnswer;
    private volatile String error;
    private volatile Instant finishedAt;

    public AgentRun(String agentName, String input) {
        this.agentName = agentName;
        this.input = input;
    }

    /**
     * 显式状态机：只允许 CREATED -> RUNNING -> COMPLETED/FAILED/CANCELLED。
     * 其余路径（如 COMPLETED -> RUNNING）一律视为编程错误。
     */
    public synchronized void transitionTo(RunState target) {
        boolean legal = switch (state) {
            case CREATED -> target == RunState.RUNNING || target == RunState.CANCELLED;
            case RUNNING -> target == RunState.COMPLETED || target == RunState.FAILED || target == RunState.CANCELLED;
            case COMPLETED, FAILED, CANCELLED -> false; // 终态不可再迁移
        };
        if (!legal) {
            throw new IllegalStateException("非法状态迁移: " + state + " -> " + target + " (run=" + id + ")");
        }
        this.state = target;
        if (isTerminal(target)) {
            this.finishedAt = Instant.now();
        }
    }

    public synchronized void addEvent(AgentEvent event) {
        events.add(event);
    }

    public synchronized void complete(String answer) {
        this.finalAnswer = answer;
    }

    public synchronized void fail(String message) {
        this.error = message;
    }

    private static boolean isTerminal(RunState s) {
        return s == RunState.COMPLETED || s == RunState.FAILED || s == RunState.CANCELLED;
    }

    public String getId() { return id; }
    public String getAgentName() { return agentName; }
    public String getInput() { return input; }
    public RunState getState() { return state; }
    public String getFinalAnswer() { return finalAnswer; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public List<AgentEvent> getEvents() { return events; }
}
