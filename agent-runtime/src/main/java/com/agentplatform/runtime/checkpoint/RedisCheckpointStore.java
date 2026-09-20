package com.agentplatform.runtime.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 版 checkpoint：热数据 + TTL 自动过期。
 *
 * 为什么 checkpoint 放 Redis 而非 MySQL：
 * 1. 写入频率高（每轮循环一次），Redis 内存写入远快于磁盘表；
 * 2. 生命周期短——运行结束即失效，TTL 到期自动清理，无需手工回收；
 * 3. 恢复场景是"热启动"，读 Redis 延迟远低于数据库。
 */
public class RedisCheckpointStore implements CheckpointStore {

    private static final String KEY_PREFIX = "agent:checkpoint:";
    /** checkpoint 只服务于运行中恢复，1 小时未结束视为已失效 */
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisCheckpointStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(Checkpoint checkpoint) {
        try {
            String json = mapper.writeValueAsString(checkpoint);
            redis.opsForValue().set(KEY_PREFIX + checkpoint.runId(), json, TTL);
        } catch (JsonProcessingException e) {
            // checkpoint 序列化失败属于程序缺陷，必须暴露而不是静默吞掉
            throw new IllegalStateException("checkpoint 序列化失败 run=" + checkpoint.runId(), e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        String json = redis.opsForValue().get(KEY_PREFIX + runId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, Checkpoint.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checkpoint 反序列化失败 run=" + runId, e);
        }
    }

    @Override
    public void delete(String runId) {
        redis.delete(KEY_PREFIX + runId);
    }
}
