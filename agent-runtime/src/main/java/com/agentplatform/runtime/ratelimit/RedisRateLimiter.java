package com.agentplatform.runtime.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * 令牌桶限流（Redis Lua 实现）。
 *
 * 为什么用 Lua 脚本而不是 Java 侧"读-算-写"三步：
 * 三步操作跨三个 Redis 往返，并发下会产生竞态（两个请求同时读到 N 个令牌都认为能放行）；
 * Lua 脚本在 Redis 服务端单线程内原子执行，"取令牌+回写时间戳"一步完成，
 * 这是 Redis 限流的行业标准做法。
 *
 * 为什么选令牌桶而不是固定窗口：令牌桶允许短时突发（桶容量内瞬间放行），
 * 对 Agent 场景更合适——突发几轮对话不拒绝，持续高频才被限。
 */
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "agent:ratelimit:";

    private static final DefaultRedisScript<Long> LUA = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])   -- 桶容量（允许的突发量）
            local refill = tonumber(ARGV[2])     -- 每秒补充令牌数
            local now = tonumber(ARGV[3])        -- 当前毫秒时间戳
            local requested = 1

            -- 首次访问满桶
            local tokens = tonumber(redis.call('get', key) or capacity)
            local last = tonumber(redis.call('get', key .. ':ts') or now)
            -- 按流逝时间补充令牌，封顶容量
            tokens = math.min(capacity, tokens + (now - last) / 1000.0 * refill)

            if tokens >= requested then
              tokens = tokens - requested
              redis.call('set', key, tokens)
              redis.call('set', key .. ':ts', now)
              redis.call('pexpire', key, 60000)
              redis.call('pexpire', key .. ':ts', 60000)
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final double capacity;
    private final double refillPerSecond;

    public RedisRateLimiter(StringRedisTemplate redis, double capacity, double refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public boolean tryAcquire(String key) {
        Long result = redis.execute(LUA, List.of(KEY_PREFIX + key),
                String.valueOf(capacity), String.valueOf(refillPerSecond),
                String.valueOf(System.currentTimeMillis()));
        return result != null && result == 1L;
    }
}
