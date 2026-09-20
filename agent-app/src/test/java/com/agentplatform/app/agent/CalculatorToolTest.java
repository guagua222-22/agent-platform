package com.agentplatform.app.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    @Test
    void plainExpression() {
        assertEquals("1081", tool.execute("23*47"));
        assertEquals("42", tool.execute("21*2"));
        assertEquals("0.25", tool.execute("1/4"));
        assertEquals("3.3333333333", tool.execute("10/3"), "除法保留 10 位小数");
    }

    /** 模型给字符串参数多包一层引号（qwen 实测行为）：入口归一化必须能处理 */
    @Test
    void quotedExpressionIsNormalized() {
        assertEquals("42", tool.execute("\"21*2\""));
    }

    /** 双重引号同样剥掉：防御极端格式漂移 */
    @Test
    void doubleQuotedExpressionIsNormalized() {
        assertEquals("42", tool.execute("\"\"21*2\"\""));
        assertEquals("42", tool.execute("  \"21*2\"  "));
    }

    @Test
    void divideByZeroFails() {
        assertThrows(ArithmeticException.class, () -> tool.execute("10/0"));
    }

    /** 白名单防线：任何非 a op b 形式的输入（含注入尝试）必须被拒绝 */
    @Test
    void illegalInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("1+2*3"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("abc"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(null));
    }
}
