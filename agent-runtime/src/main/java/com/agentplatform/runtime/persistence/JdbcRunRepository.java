package com.agentplatform.runtime.persistence;

import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.model.RunState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MySQL 实现（基于 JdbcTemplate）。
 *
 * 为什么用轻量 JDBC 而不是 JPA/MyBatis：运行记录与事件是"固定结构、高频追加、
 * 无需对象图映射"的日志型数据，ORM 的对象生命周期管理在这里纯属开销；
 * 事件表的写入路径要求极低延迟，直接 SQL 最可控。业务型数据（M2 会话）再评估 ORM。
 */
public class JdbcRunRepository implements RunRepository {

    private final JdbcTemplate jdbc;

    public JdbcRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveRun(AgentRun run) {
        // UPSERT：RUNNING 时插入，终态时更新。id 是 UUID，天然幂等——
        // 同一运行重复落库只会覆盖同一条记录，不会产生脏副本
        jdbc.update("""
                        INSERT INTO agent_run (id, agent_name, input, state, final_answer, error, started_at, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            state = VALUES(state),
                            final_answer = VALUES(final_answer),
                            error = VALUES(error),
                            finished_at = VALUES(finished_at)
                        """,
                run.getId(), run.getAgentName(), run.getInput(), run.getState().name(),
                run.getFinalAnswer(), run.getError(),
                Timestamp.from(run.getStartedAt()),
                run.getFinishedAt() == null ? null : Timestamp.from(run.getFinishedAt()));
    }

    @Override
    public void saveEvent(String runId, AgentEvent event, int seq) {
        jdbc.update("""
                        INSERT INTO agent_event (run_id, seq, type, detail, occurred_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                runId, seq, event.type().name(), event.detail(),
                Timestamp.from(event.timestamp()));
    }

    @Override
    public Optional<AgentRun> findRun(String runId) {
        List<AgentRun> runs = jdbc.query("""
                        SELECT id, agent_name, input, state, final_answer, error, started_at, finished_at
                        FROM agent_run WHERE id = ?
                        """, this::mapRun, runId);
        return runs.stream().findFirst();
    }

    @Override
    public List<AgentEvent> findEvents(String runId) {
        return jdbc.query("""
                        SELECT type, detail, occurred_at
                        FROM agent_event WHERE run_id = ? ORDER BY seq
                        """,
                (rs, rowNum) -> new AgentEvent(
                        AgentEvent.EventType.valueOf(rs.getString("type")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("detail")),
                runId);
    }

    @Override
    public List<AgentRun> findRecent(int limit) {
        return jdbc.query("""
                        SELECT id, agent_name, input, state, final_answer, error, started_at, finished_at
                        FROM agent_run ORDER BY started_at DESC LIMIT ?
                        """, this::mapRun, Math.max(1, Math.min(limit, 100)));
    }

    private AgentRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return AgentRun.restore(
                rs.getString("id"),
                rs.getString("agent_name"),
                rs.getString("input"),
                RunState.valueOf(rs.getString("state")),
                rs.getString("final_answer"),
                rs.getString("error"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
