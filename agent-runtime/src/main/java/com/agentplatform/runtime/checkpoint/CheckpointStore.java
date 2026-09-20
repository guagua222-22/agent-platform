package com.agentplatform.runtime.checkpoint;

import java.util.Optional;

/**
 * Checkpoint 存储契约。
 * Redis 实现负责热数据（带 TTL），未来可换其他后端；接口保持存储无关。
 */
public interface CheckpointStore {

    void save(Checkpoint checkpoint);

    Optional<Checkpoint> load(String runId);

    void delete(String runId);
}
