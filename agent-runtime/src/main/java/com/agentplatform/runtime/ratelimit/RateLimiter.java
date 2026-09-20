package com.agentplatform.runtime.ratelimit;

/**
 * 限流契约：给定维度 key（如 agent 名、调用方 IP），返回本次请求是否放行。
 */
public interface RateLimiter {

    /**
     * @param key 限流维度（"agent:calculator"、"ip:1.2.3.4" 等）
     * @return true=放行，false=拒绝（调用方应返回 429）
     */
    boolean tryAcquire(String key);
}
