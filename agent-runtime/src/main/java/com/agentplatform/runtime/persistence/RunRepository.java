package com.agentplatform.runtime.persistence;

import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;

import java.util.List;
import java.util.Optional;

/**
 * 运行记录的持久化契约（Runtime 基础设施之一）。
 *
 * 为什么定义成接口：M1 用 MySQL（JdbcRunRepository），
 * 测试用内存实现（零成本），将来换存储（M4 可能加归档）不改调用方；
 * Runtime 只依赖"能存能查"的能力，不绑定具体存储。
 */
public interface RunRepository {

    /** 保存/更新运行记录（start 时插入 RUNNING，终态时更新为 COMPLETED/FAILED） */
    void saveRun(AgentRun run);

    /** 追加一条事件（seq 由调用方保证递增且唯一） */
    void saveEvent(String runId, AgentEvent event, int seq);

    /** 按 ID 查运行记录（历史查询/恢复） */
    Optional<AgentRun> findRun(String runId);

    /** 查某次运行的全部事件（按 seq 升序，用于过程回放） */
    List<AgentEvent> findEvents(String runId);

    /** 最近 N 条运行记录（按开始时间倒序） */
    List<AgentRun> findRecent(int limit);
}
