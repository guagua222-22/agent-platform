package com.agentplatform.harness.tool;

import com.agentplatform.core.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public String execute(String arguments) {
                return "ok";
            }
        };
    }

    @Test
    void registerAndLookup() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("calculator"));

        assertEquals("calculator", registry.get("calculator").name());
        assertTrue(registry.contains("calculator"));
        assertEquals(1, registry.all().size());
    }

    @Test
    void duplicateNameFailsFast() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("calculator"));
        // 重名会让模型选择歧义，是隐蔽事故源，注册期必须拦截
        assertThrows(IllegalStateException.class, () -> registry.register(tool("calculator")));
    }

    @Test
    void unknownToolFailsFast() {
        ToolRegistry registry = new ToolRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.get("ghost"));
    }
}
