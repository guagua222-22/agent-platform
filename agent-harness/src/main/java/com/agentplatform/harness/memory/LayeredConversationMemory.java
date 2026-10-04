package com.agentplatform.harness.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层会话记忆：短期窗口（MySQL 最近 N 条原文） + 长期摘要（LLM 压缩，存会话行）。
 *
 * 为什么不把全量历史塞给模型：
 * 1. token 成本——全量历史随轮次线性膨胀，长会话必爆上下文窗口；
 * 2. 注意力稀释——历史越长，模型越容易忽略关键信息（"lost in the middle"）。
 * 分层策略：最近 windowSize 条原文直出（短期，保真），更早的压缩成一段摘要（长期，保意）。
 * 这对应业内 MemGPT / LangGraph 的 short-term + long-term memory 划分。
 *
 * 摘要采用增量合并：summarized_upto 水位线记录"id 小于等于它的消息已并入摘要"，
 * 每轮只把窗口外新增的部分交给模型与旧摘要合并——全量重摘要成本会随会话长度平方增长。
 * 摘要失败只降级（长期层本轮不更新），不阻断对话主链路。
 */
public class LayeredConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(LayeredConversationMemory.class);

    /** 摘要专用提示词：只要求压缩事实，不续写对话，避免模型"接着聊" */
    private static final String SUMMARY_PROMPT =
            "你是记忆压缩器。把【已有摘要】与【新增对话】合并为一段不超过 200 字的第三人称摘要，" +
            "保留关键事实（用户偏好、结论、待办），丢弃寒暄。只输出摘要本身。";

    private final JdbcTemplate jdbc;
    private final ChatModel summaryModel;
    private final int windowSize;

    /**
     * @param summaryModel 可为 null：无模型时退化为纯窗口记忆（长期层关闭），
     *                     测试与本地开发不依赖模型也能跑通
     */
    public LayeredConversationMemory(JdbcTemplate jdbc, ChatModel summaryModel, int windowSize) {
        this.jdbc = jdbc;
        this.summaryModel = summaryModel;
        this.windowSize = windowSize;
    }

    @Override
    public List<MemoryMessage> load(String conversationId) {
        List<MemoryMessage> out = new ArrayList<>();
        // 长期层：摘要有内容才注入，作为一条 system 旁白放在历史最前
        String summary = queryForSummary(conversationId);
        if (summary != null && !summary.isBlank()) {
            out.add(new MemoryMessage("system", "【历史对话摘要】" + summary));
        }
        // 短期层：最近 windowSize 条原文（先倒序取数再正序，保证对话时序）
        out.addAll(jdbc.query(
                "SELECT role, content FROM (" +
                        "  SELECT id, role, content FROM agent_conversation_message" +
                        "  WHERE conversation_id = ? ORDER BY id DESC LIMIT ?" +
                        ") recent ORDER BY id",
                (rs, i) -> new MemoryMessage(rs.getString("role"), rs.getString("content")),
                conversationId, windowSize));
        return out;
    }

    @Override
    public void append(String conversationId, String agentName, String userInput, String assistantAnswer) {
        jdbc.update("INSERT IGNORE INTO agent_conversation (id, agent_name) VALUES (?, ?)",
                conversationId, agentName);
        jdbc.update("INSERT INTO agent_conversation_message (conversation_id, role, content) VALUES (?, 'user', ?)",
                conversationId, userInput);
        jdbc.update("INSERT INTO agent_conversation_message (conversation_id, role, content) VALUES (?, 'assistant', ?)",
                conversationId, assistantAnswer);
        jdbc.update("UPDATE agent_conversation SET msg_count = msg_count + 2 WHERE id = ?", conversationId);
        maybeSummarize(conversationId);
    }

    /**
     * 增量摘要：水位线 = 窗口外最大消息 id。
     * 若水位线超过 summarized_upto，说明有旧消息新被挤出窗口，把这段增量并入摘要。
     */
    private void maybeSummarize(String conversationId) {
        if (summaryModel == null) {
            return;
        }
        try {
            Long watermark = jdbc.queryForObject(
                    "SELECT IFNULL(MAX(id), 0) - ? FROM agent_conversation_message WHERE conversation_id = ?",
                    Long.class, windowSize, conversationId);
            Long summarizedUpto = jdbc.queryForObject(
                    "SELECT summarized_upto FROM agent_conversation WHERE id = ?",
                    Long.class, conversationId);
            if (watermark == null || watermark <= 0 || watermark <= (summarizedUpto == null ? 0 : summarizedUpto)) {
                return; // 窗口还没满，或没有新增需要摘要的内容
            }
            List<MemoryMessage> increment = jdbc.query(
                    "SELECT role, content FROM agent_conversation_message" +
                            " WHERE conversation_id = ? AND id > ? AND id <= ? ORDER BY id",
                    (rs, i) -> new MemoryMessage(rs.getString("role"), rs.getString("content")),
                    conversationId, summarizedUpto, watermark);
            if (increment.isEmpty()) {
                return;
            }
            String merged = summarize(queryForSummary(conversationId), increment);
            jdbc.update("UPDATE agent_conversation SET summary = ?, summarized_upto = ? WHERE id = ?",
                    merged, watermark, conversationId);
        } catch (Exception e) {
            // 记忆是增强项不是主链路：摘要失败只丢长期层精度，对话照常
            log.warn("会话摘要失败已忽略 conversation={}", conversationId, e);
        }
    }

    private String summarize(String oldSummary, List<MemoryMessage> increment) {
        StringBuilder dialogue = new StringBuilder();
        for (MemoryMessage m : increment) {
            dialogue.append("user".equals(m.role()) ? "用户: " : "助手: ").append(m.content()).append('\n');
        }
        return summaryModel.call(new Prompt(List.of(
                new SystemMessage(SUMMARY_PROMPT),
                new UserMessage("【已有摘要】" + (oldSummary == null || oldSummary.isBlank() ? "（无）" : oldSummary)
                        + "\n【新增对话】\n" + dialogue))))
                .getResult().getOutput().getText();
    }

    private String queryForSummary(String conversationId) {
        List<String> rows = jdbc.query("SELECT summary FROM agent_conversation WHERE id = ?",
                (rs, i) -> rs.getString(1), conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
