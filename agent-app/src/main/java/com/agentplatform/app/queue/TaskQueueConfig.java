package com.agentplatform.app.queue;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.runtime.AgentRuntime;
import com.agentplatform.runtime.queue.AgentTask;
import com.agentplatform.runtime.queue.AgentTaskQueue;
import com.agentplatform.runtime.queue.RocketMqTaskQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 任务队列装配（M3-B）：一条与 SSE 同步通道并存的异步执行通道。
 *
 * 同步（SSE）与异步（队列）的分工：
 * - SSE 适合"用户在等"的即时对话：低延迟、过程可见（事件流）；
 * - 队列适合"提交后不用盯着"的长任务：削峰、解耦、宕机不丢。
 * 两条通道最终都汇入同一个 AgentRuntime——执行语义只有一份，通道只是入口。
 */
@Configuration
public class TaskQueueConfig {

    @Bean(destroyMethod = "close")
    public AgentTaskQueue agentTaskQueue(Map<String, AgentDefinition> agents, AgentRuntime runtime,
                                         @Value("${agent.taskqueue.endpoints:localhost:10911}") String endpoints,
                                         @Value("${agent.taskqueue.topic:agent-tasks}") String topic,
                                         @Value("${agent.taskqueue.consumer-group:agent-platform-workers}") String group) {
        RocketMqTaskQueue queue = new RocketMqTaskQueue(endpoints, topic, group);
        queue.start(task -> {
            AgentDefinition definition = agents.get(task.agentName());
            if (definition == null) {
                // 未知 Agent 的任务没有重试价值：抛异常让消息走重投->死信路径，
                // 死信队列里保留现场供人工排查（比静默丢弃可追溯）
                throw new IllegalStateException("任务指向不存在的 Agent: " + task.agentName());
            }
            // 任务 id 即运行 id：生产者已用同一 id 落了 CREATED 记录，
            // 这里续上状态机，外部查询看到的是同一条记录的状态推进
            AgentRun run = new AgentRun(task.runId(), task.agentName(), task.input());
            run.setConversationId(task.conversationId());
            runtime.run(definition, run, null);
        });
        return queue;
    }
}
