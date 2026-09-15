package com.agentplatform.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionTest {

    private static final LoopStrategy DUMMY_LOOP = new LoopStrategy() {
        @Override
        public String name() {
            return "dummy";
        }

        @Override
        public void execute(AgentDefinition definition, com.agentplatform.core.model.AgentRun run, AgentEventSink sink) {
            run.complete("done");
        }
    };

    @Test
    void buildWithRequiredFields() {
        AgentDefinition def = AgentDefinition.builder()
                .name("calculator")
                .systemPrompt("你是计算助手")
                .loopStrategy(DUMMY_LOOP)
                .build();

        assertEquals("calculator", def.getName());
        assertEquals(10, def.getMaxSteps(), "maxSteps 默认 10，防止无限循环");
        assertTrue(def.getTools().isEmpty());
    }

    @Test
    void missingNameFails() {
        // name 是 API 路由与持久化主键，缺失必须在装配期暴露
        assertThrows(IllegalArgumentException.class,
                () -> AgentDefinition.builder().loopStrategy(DUMMY_LOOP).build());
    }

    @Test
    void missingLoopStrategyFails() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentDefinition.builder().name("x").build());
    }

    @Test
    void maxStepsAtLeastOne() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentDefinition.builder().name("x").loopStrategy(DUMMY_LOOP).maxSteps(0).build());
    }
}
