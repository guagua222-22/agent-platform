package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.harness.memory.ConversationMemory;
import com.agentplatform.harness.memory.ConversationMemory.MemoryMessage;
import com.agentplatform.harness.eval.MockChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReActLoop 的会话记忆集成：注入与回写两条路径。
 * 用内存版 ConversationMemory stub 隔离 MySQL——Loop 只依赖记忆抽象，
 * 分层策略（窗口/摘要）的正确性由用户侧真实 MySQL 验证。
 */
class ReActLoopMemoryTest {

    /** 内存 stub：load 返回预置历史，append 记录调用参数供断言 */
    private static class StubMemory implements ConversationMemory {
        private final List<MemoryMessage> history;
        private final List<String[]> appended = new ArrayList<>();

        StubMemory(List<MemoryMessage> history) {
            this.history = history;
        }

        @Override
        public List<MemoryMessage> load(String conversationId) {
            return history;
        }

        @Override
        public void append(String conversationId, String agentName, String userInput, String assistantAnswer) {
            appended.add(new String[]{conversationId, agentName, userInput, assistantAnswer});
        }
    }

    private AgentDefinition definition(ReActLoop loop) {
        return AgentDefinition.builder()
                .name("chat")
                .systemPrompt("你是助手")
                .loopStrategy(loop)
                .maxSteps(3)
                .build();
    }

    /** 带 conversationId 的运行：记忆注入提示词（system 摘要 + 历史消息），闭环后回写 */
    @Test
    void memoryInjectedAndAppended() {
        StubMemory memory = new StubMemory(List.of(
                new MemoryMessage("system", "【历史对话摘要】用户叫小王"),
                new MemoryMessage("user", "我叫小王"),
                new MemoryMessage("assistant", "好的小王")));
        MockChatModel model = new MockChatModel();
        ReActLoop loop = new ReActLoop(model, null, memory);
        model.scriptAnswer("你叫小王");

        AgentRun run = new AgentRun("chat", "我叫什么名字");
        run.setConversationId("conv-1");
        loop.execute(definition(loop), run, run::addEvent);

        // 提示词结构：人设 system + 摘要 system + 历史 user/assistant + 本次 user
        List<Message> prompt = model.lastPrompt().getInstructions();
        assertEquals(5, prompt.size());
        assertInstanceOf(SystemMessage.class, prompt.get(0));
        assertEquals("你是助手", ((SystemMessage) prompt.get(0)).getText());
        assertEquals("【历史对话摘要】用户叫小王", ((SystemMessage) prompt.get(1)).getText());
        assertEquals("我叫小王", ((UserMessage) prompt.get(2)).getText());
        assertEquals("我叫什么名字", ((UserMessage) prompt.get(4)).getText());

        // 回写只存"问 + 答"，agent 名与会话 id 一并落档
        assertEquals(1, memory.appended.size());
        assertEquals("conv-1", memory.appended.get(0)[0]);
        assertEquals("chat", memory.appended.get(0)[1]);
        assertEquals("我叫什么名字", memory.appended.get(0)[2]);
        assertEquals("你叫小王", memory.appended.get(0)[3]);

        assertTrue(run.getEvents().stream().anyMatch(e ->
                e.type() == AgentEvent.EventType.MEMORY_LOADED && e.detail().contains("conv-1")));
    }

    /** 无 conversationId 的一次性运行：不读记忆也不写记忆（兼容 M0/M1 的无会话调用） */
    @Test
    void noConversationIdSkipsMemory() {
        StubMemory memory = new StubMemory(List.of(new MemoryMessage("user", "不该被注入")));
        MockChatModel model = new MockChatModel();
        ReActLoop loop = new ReActLoop(model, null, memory);
        model.scriptAnswer("你好");

        AgentRun run = new AgentRun("chat", "你好");
        loop.execute(definition(loop), run, run::addEvent);

        assertEquals(2, model.lastPrompt().getInstructions().size(), "只应有人设 + 本次输入");
        assertTrue(memory.appended.isEmpty(), "无会话运行不得回写记忆");
    }

    /** 记忆为 null（未配置）：行为与 M1 完全一致，记忆是可选增强 */
    @Test
    void nullMemoryKeepsOriginalBehavior() {
        MockChatModel model = new MockChatModel();
        ReActLoop loop = new ReActLoop(model);
        model.scriptAnswer("你好");

        AgentRun run = new AgentRun("chat", "你好");
        run.setConversationId("conv-x"); // 即使有会话 id，没有记忆实现也不注入
        loop.execute(definition(loop), run, run::addEvent);

        assertEquals(2, model.lastPrompt().getInstructions().size());
        assertEquals("你好", run.getFinalAnswer());
    }
}
