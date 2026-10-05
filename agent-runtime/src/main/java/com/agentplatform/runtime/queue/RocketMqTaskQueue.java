package com.agentplatform.runtime.queue;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * RocketMQ 实现（5.x gRPC 客户端，走 Proxy 端口）。
 *
 * 选型说明（复用秒杀项目经验，面试可展开）：
 * - 为什么 RocketMQ 而不是 Kafka：业务消息（重试/死信/延迟/顺序）开箱即用，
 *   Kafka 强在日志流吞吐，任务队列语义不是它的主场；
 * - 为什么 5.x Proxy 模式：客户端只认 Proxy 地址（gRPC），
 *   无需感知 Broker 拓扑，本地 Docker 单机与生产集群同一套代码。
 *
 * 幂等设计：消息可能重投（消费者宕机/处理超时），消费端 handler 用 runId 落库幂等兜底——
 * MQ 保证"至少一次"，业务保证"恰好一次"的效果。
 */
public class RocketMqTaskQueue implements AgentTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RocketMqTaskQueue.class);

    private final String topic;
    private final String consumerGroup;
    private final ClientConfiguration clientConfig;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    private volatile Producer producer;
    private volatile PushConsumer consumer;

    /**
     * @param endpoints     Proxy 地址，如 "localhost:10911"
     * @param topic         任务主题
     * @param consumerGroup 消费组（同组内负载均衡，多实例部署时天然分担任务）
     */
    public RocketMqTaskQueue(String endpoints, String topic, String consumerGroup) {
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.clientConfig = ClientConfiguration.newBuilder().setEndpoints(endpoints).build();
    }

    @Override
    public void enqueue(AgentTask task) {
        try {
            // 懒初始化 Producer：构造队列对象不等于建立连接，应用启动更快、失败更晚暴露
            if (producer == null) {
                synchronized (this) {
                    if (producer == null) {
                        producer = provider.newProducerBuilder()
                                .setClientConfiguration(clientConfig)
                                .setTopics(topic)
                                .build();
                    }
                }
            }
            // runId 设为消息 keys：MQ 控制台可按 key 反查消息，排查"任务丢没丢"不用翻日志
            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setKeys(task.runId())
                    .setBody(task.encode())
                    .build();
            producer.send(message);
        } catch (ClientException e) {
            throw new IllegalStateException("任务入队失败 topic=" + topic + " runId=" + task.runId(), e);
        }
    }

    @Override
    public void start(Consumer<AgentTask> handler) {
        try {
            consumer = provider.newPushConsumerBuilder()
                    .setClientConfiguration(clientConfig)
                    .setConsumerGroup(consumerGroup)
                    .setSubscriptionExpressions(Map.of(topic,
                            new FilterExpression("*", FilterExpressionType.TAG)))
                    .setMessageListener(messageView -> {
                        try {
                            java.nio.ByteBuffer buffer = messageView.getBody();
                            byte[] body = new byte[buffer.remaining()];
                            buffer.get(body);
                            handler.accept(AgentTask.decode(body));
                            return ConsumeResult.SUCCESS;
                        } catch (Exception e) {
                            // 返回 FAILURE 触发 MQ 重投：重投是兜底不是策略，
                            // 反复失败的消息最终进死信队列（%DLQ%<group>），需要人工介入
                            log.error("任务消费失败，等待重投 msgId={}", messageView.getMessageId(), e);
                            return ConsumeResult.FAILURE;
                        }
                    })
                    .build();
            log.info("RocketMQ 任务消费者已启动 topic={} group={}", topic, consumerGroup);
        } catch (ClientException e) {
            throw new IllegalStateException("任务消费者启动失败 topic=" + topic, e);
        }
    }

    @Override
    public void close() {
        // 应用停机时优雅关闭：先停消费（不再拉新消息），再关生产者
        try {
            if (consumer != null) {
                consumer.close();
            }
            if (producer != null) {
                producer.close();
            }
        } catch (IOException e) {
            log.warn("RocketMQ 客户端关闭异常已忽略", e);
        }
    }
}
