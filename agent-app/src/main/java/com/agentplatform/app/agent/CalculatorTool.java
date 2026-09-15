package com.agentplatform.app.agent;

import com.agentplatform.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计算器工具（M0 示例工具）：二元四则运算。
 *
 * 安全设计：先用正则白名单校验输入再计算——
 * 工具参数来自模型输出（不可信输入），任何"先解析再校验"的写法都可能被注入
 * （M3 沙箱会再加进程级隔离，这里是第一道防线）。
 */
@Component
public class CalculatorTool implements Tool {

    private static final Pattern EXPR =
            Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)$");

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算二元四则运算。参数格式：一个数学表达式字符串，如 \"23*47\" 或 \"100/3\"。" +
                "仅支持 a+b、a-b、a*b、a/b 形式的整数或小数运算。";
    }

    @Override
    public String execute(String arguments) {
        Matcher m = EXPR.matcher(arguments == null ? "" : arguments.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("表达式格式非法（仅支持 a+b a-b a*b a/b）: " + arguments);
        }
        BigDecimal a = new BigDecimal(m.group(1));
        String op = m.group(2);
        BigDecimal b = new BigDecimal(m.group(3));

        BigDecimal result = switch (op) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "/" -> {
                if (b.compareTo(BigDecimal.ZERO) == 0) {
                    throw new ArithmeticException("除数不能为 0");
                }
                yield a.divide(b, 10, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default -> throw new IllegalStateException("不可达: " + op);
        };
        return result.toPlainString();
    }
}
