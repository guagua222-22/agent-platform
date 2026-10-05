package com.agentplatform.runtime;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.model.RunState;
import com.agentplatform.core.util.Strings;
import com.agentplatform.runtime.persistence.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 执行层入口：一次调用 = 一次完整、有明确终态的 Agent 运行。
 *
 * 为什么 Runtime 吞掉 Loop 抛出的异常而不是继续上抛：
 * Runtime 的核心承诺是"每次运行必达终态"——无论成功、超步数还是模型报错，
 * 都必须落在 COMPLETED/FAILED 之一并留档（事件流 + error），
 * 否则上层（SSE、评测）永远要处理"运行到一半没下文"的脏状态。
 *
 * M1 扩展的两条基础设施：
 * 1. 持久化（RunRepository，可空）：RUNNING 时先落库、事件逐条追加、终态回写。
 *    为什么落库失败只记日志不打断运行：持久化是"尽力而为的旁路"，
 *    数据库故障不应阻断对话本身——可用性优先于记录完整性。
 * 2. 并发（虚拟线程执行器）：runAsync 让每个请求占一个虚拟线程而非平台线程。
 *    为什么虚拟线程：Agent 运行是 IO 密集长耗时（模型调用以秒计），
 *    平台线程会被阻塞耗尽池子；虚拟线程由 JVM 调度、阻塞时自动让出，成本极低。
 */
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final RunRepository repository;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 无持久化模式（评测、单元测试） */
    public AgentRuntime() {
        this(null);
    }

    /** 生产模式：注入存储实现 */
    public AgentRuntime(RunRepository repository) {
        this.repository = repository;
    }

    /** 便捷入口：不关心过程事件的调用方（如评测的 mock 模式） */
    public AgentRun run(AgentDefinition definition, String input) {
        return run(definition, input, event -> { });
    }

    /**
     * 异步执行：HTTP 长连接场景（SSE）的标准入口。
     * Controller 不再自己 new 线程——并发策略是 Runtime 的职责，调用方只声明意图。
     */
    public CompletableFuture<AgentRun> runAsync(AgentDefinition definition, String input, AgentEventSink externalSink) {
        return runAsync(definition, input, null, externalSink);
    }

    /** 带会话上下文的异步执行：conversationId 非空时 Loop 可据此加载/回写会话记忆 */
    public CompletableFuture<AgentRun> runAsync(AgentDefinition definition, String input,
                                                String conversationId, AgentEventSink externalSink) {
        return CompletableFuture.supplyAsync(() -> run(definition, input, conversationId, externalSink), executor);
    }

    /**
     * @param definition  Harness 组装好的 Agent 定义
     * @param input       用户输入
     * @param externalSink 外部事件消费者（SSE 推送 / Tracing），可为空
     * @return 终态运行记录（COMPLETED 或 FAILED）
     */
    public AgentRun run(AgentDefinition definition, String input, AgentEventSink externalSink) {
        return run(definition, input, null, externalSink);
    }

    public AgentRun run(AgentDefinition definition, String input, String conversationId, AgentEventSink externalSink) {
        return runInternal(definition, input, conversationId, null, externalSink);
    }

    /**
     * 嵌套运行（SubAgent 委托专用，M3）：在当前运行内同步执行子 Agent。
     *
     * 为什么是同步而不是另起线程异步：
     * 1. 委托语义本就是"等子 Agent 出结果再继续"（agent-as-tool 的工具调用语义）；
     * 2. 同步执行与父运行同一虚拟线程，RunContext（ThreadLocal）天然传播，
     *    跨线程传播则需要显式上下文拷贝，复杂度换来的只是假象并发；
     * 3. 父运行本身已在虚拟线程里，阻塞等待子运行的成本约等于零。
     * 子事件加 "[sub:<agent>]" 前缀转发进父事件流——父运行的 SSE 能看到完整嵌套思考过程，
     * 子运行自己也有独立完整的事件存档（双层留痕：父看全局，子可独立审计）。
     */
    public AgentRun runChild(AgentDefinition definition, String input) {
        RunContext parent = RunContext.current();
        if (parent == null) {
            throw new IllegalStateException("runChild 必须在某次运行内调用（SubAgentTool 之外禁止使用）");
        }
        AgentEventSink forwarding = event -> parent.sink().emit(AgentEvent.of(event.type(),
                "[sub:" + definition.getName() + "] " + event.detail()));
        return runInternal(definition, input, null, parent.run().getId(), forwarding);
    }

    private AgentRun runInternal(AgentDefinition definition, String input, String conversationId,
                                 String parentRunId, AgentEventSink externalSink) {
        AgentRun run = new AgentRun(definition.getName(), input);
        run.setConversationId(conversationId);
        run.setParentRunId(parentRunId);
        return executePrepared(definition, run, externalSink);
    }

    /**
     * 用调用方预创建的 AgentRun 执行（任务队列场景，M3-B）：
     * 生产者预生成 runId 入队并落库，消费者用同一运行对象启动——
     * 任务 id 即运行 id，提交即刻可查询状态，无需任务/运行映射表。
     * conversationId / parentRunId 由调用方在 run 上预先设置。
     */
    public AgentRun run(AgentDefinition definition, AgentRun run, AgentEventSink externalSink) {
        return executePrepared(definition, run, externalSink);
    }

    private AgentRun executePrepared(AgentDefinition definition, AgentRun run, AgentEventSink externalSink) {
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(definition.getLoopStrategy(), "Agent 未指定 Loop 策略: " + definition.getName());
        AgentEventSink sink = externalSink == null ? event -> { } : externalSink;

        // 嵌套深度在创建新上下文前从当前上下文读出：顶层 = 0，每嵌套一层 +1
        RunContext parent = RunContext.current();
        int depth = parent == null ? 0 : parent.depth() + 1;

        // 复合 sink：事件先存档进 run（内存真相），再旁路落库，最后转发给外部。
        // 存档是 Runtime 的底线职责，外部 sink 抛异常不允许中断运行
        AgentEventSink composite = event -> {
            run.addEvent(event);
            persistEvent(run, event);
            try {
                sink.emit(event);
            } catch (Exception e) {
                log.warn("事件转发失败已忽略 run={} event={}", run.getId(), event.type(), e);
            }
        };

        composite.emit(AgentEvent.of(AgentEvent.EventType.RUN_STARTED,
                "agent=" + definition.getName() + " input=" + Strings.abbreviate(run.getInput())));
        run.transitionTo(RunState.RUNNING);
        persistRun(run); // RUNNING 即落库：崩溃后至少留有"运行过"的痕迹
        // 运行上下文绑定到当前线程：SubAgentTool 据此拿到父运行与父事件出口。
        // finally 清理是硬要求——ThreadLocal 残留是最隐蔽的上下文污染源
        RunContext.set(new RunContext(run, composite, depth));
        try {
            definition.getLoopStrategy().execute(definition, run, composite);
            run.transitionTo(RunState.COMPLETED);
            composite.emit(AgentEvent.of(AgentEvent.EventType.RUN_COMPLETED,
                    "answer=" + Strings.abbreviate(run.getFinalAnswer())));
        } catch (Exception e) {
            run.fail(e.getMessage());
            run.transitionTo(RunState.FAILED);
            composite.emit(AgentEvent.of(AgentEvent.EventType.RUN_FAILED, e.toString()));
            log.error("Agent 运行失败 run={} agent={}", run.getId(), definition.getName(), e);
        } finally {
            RunContext.clear();
            persistRun(run); // 终态回写：最终答案/错误信息/结束时间
        }
        return run;
    }

    private void persistRun(AgentRun run) {
        if (repository == null) {
            return;
        }
        try {
            repository.saveRun(run);
        } catch (Exception e) {
            log.warn("运行记录落库失败已忽略 run={}", run.getId(), e);
        }
    }

    private void persistEvent(AgentRun run, AgentEvent event) {
        if (repository == null) {
            return;
        }
        try {
            // addEvent 已先行，size() 即本事件的顺序号（从 1 起）
            repository.saveEvent(run.getId(), event, run.getEvents().size());
        } catch (Exception e) {
            log.warn("事件落库失败已忽略 run={} seq={}", run.getId(), run.getEvents().size(), e);
        }
    }

    /** 应用关闭时回收执行器（Spring Bean destroyMethod 调用） */
    public void close() {
        executor.close();
    }
}
