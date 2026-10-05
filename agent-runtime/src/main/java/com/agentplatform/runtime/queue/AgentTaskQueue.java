package com.agentplatform.runtime.queue;

import java.util.function.Consumer;

/**
 * 任务队列契约（Runtime 基础设施，M3-B）。
 *
 * 为什么 Agent 平台需要任务队列（SSE 同步通道之外再开一条异步通道）：
 * - 削峰：突发流量先在队列里排队，消费者按能力匀速执行，模型配额不被打穿；
 * - 解耦长任务：分钟级的多步任务不该占着 HTTP 长连接，提交后客户端轮询状态即可；
 * - 可靠性：消费者宕机消息不丢（MQ 持久化 + 重投），比内存队列高一个量级。
 *
 * 定义为接口：RocketMQ 是生产实现，测试用内存实现零成本——
 * 与 RunRepository 的"契约先行"同一思路。
 */
public interface AgentTaskQueue extends AutoCloseable {

    /** 投递任务（消息持久化后返回，Consumer 尚未开始处理） */
    void enqueue(AgentTask task);

    /**
     * 启动消费：每条消息回调 handler 一次。
     * handler 抛异常时消息会按 MQ 的重试策略重投——所以 handler 内部必须保证
     * 重复执行同一任务不产生脏数据（本平台靠 runId 幂等落库兜底）。
     */
    void start(Consumer<AgentTask> handler);

    @Override
    void close();
}
