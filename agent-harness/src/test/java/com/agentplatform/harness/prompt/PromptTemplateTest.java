package com.agentplatform.harness.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptTemplateTest {

    @Test
    void rendersVariables() {
        PromptTemplate t = new PromptTemplate("calc", 1, "你是${role}，请计算 ${expr}");
        assertEquals("你是计算助手，请计算 23*47", t.render(Map.of("role", "计算助手", "expr", "23*47")));
    }

    @Test
    void missingVariableFailsFast() {
        PromptTemplate t = new PromptTemplate("calc", 1, "你好 ${user_name}");
        // 占位符残留 = 变量漏传，必须抛异常而不是把 ${user_name} 原样发给模型
        assertThrows(IllegalStateException.class, () -> t.render(Map.of()));
    }

    @Test
    void templateWithoutVariablesRendersAsIs() {
        PromptTemplate t = new PromptTemplate("chat", 2, "你是一个助手");
        assertEquals("你是一个助手", t.render(Map.of()));
    }
}
