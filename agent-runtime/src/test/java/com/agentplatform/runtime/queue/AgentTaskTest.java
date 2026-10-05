package com.agentplatform.runtime.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AgentTask 编解码测试：消息体要跨网络/跨语言工具传输，
 * 编解码对称性是"消息在队列里不坏"的最小保证。
 */
class AgentTaskTest {

    @Test
    void encodeDecodeRoundTrip() {
        AgentTask task = new AgentTask("run-1", "assistant", "conv-9", "帮我查天气");
        AgentTask decoded = AgentTask.decode(task.encode());

        assertEquals(task, decoded);
    }

    @Test
    void inputContainingSeparatorSurvives() {
        // input 含分隔符自身/换行/引号等任意字符，Base64 编码段不受影响
        String nasty = "第一行第二行\n带 分隔符 和 \"引号\" 以及 emoji 🚀";
        AgentTask decoded = AgentTask.decode(new AgentTask("r", "a", "c", nasty).encode());

        assertEquals(nasty, decoded.input());
    }

    @Test
    void nullConversationIdRoundTripsAsNull() {
        AgentTask decoded = AgentTask.decode(new AgentTask("r", "a", null, "hi").encode());

        assertNull(decoded.conversationId());
        assertEquals("hi", decoded.input());
    }

    @Test
    void malformedBodyRejected() {
        byte[] bad = "只有两段哦".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> AgentTask.decode(bad));
    }

    @Test
    void malformedBase64Rejected() {
        byte[] bad = "rac!!!not-base64!!!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> AgentTask.decode(bad));
    }
}
