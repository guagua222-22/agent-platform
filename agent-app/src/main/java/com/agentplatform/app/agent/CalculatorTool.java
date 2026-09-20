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
 * 参数设计（结构化 record）：
 * 1. 模型按 inputType 生成的 JSON Schema 输出对象参数 {"expression": "..."}，
 *    规避 DashScope 兼容层对请求回放中字符串 arguments 的严格校验（实测 400）；
 * 2. 安全设计不变：正则白名单校验内容——工具参数来自模型输出（不可信输入），
 *    任何"先解析再校验"的写法都可能被注入（M3 沙箱会再加进程级隔离）。
 */
@Component
public class CalculatorTool implements Tool<CalculatorTool.CalcArgs> {

    private static final Pattern EXPR =
            Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)$");

    /** 工具参数契约：expression 为数学表达式字符串 */
    public record CalcArgs(String expression) {
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public Class<CalcArgs> inputType() {
        return CalcArgs.class;
    }

    @Override
    public String description() {
        return "计算二元四则运算。参数是 JSON 对象，格式：{\"expression\": \"数学表达式\"}，" +
                "例如 {\"expression\": \"23*47\"}。仅支持 a+b、a-b、a*b、a/b 形式的整数或小数运算。";
    }

    @Override
    public String execute(CalcArgs args) {
        if (args == null || args.expression() == null) {
            throw new IllegalArgumentException("缺少 expression 参数");
        }
        // 工具输入归一化：模型输出的参数存在格式漂移（实测 qwen 会给字符串多包一层引号），
        // 工具入口必须宽容——剥掉成对引号后再校验，而不是直接拒绝
        String expr = normalize(args.expression());
        Matcher m = EXPR.matcher(expr);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "表达式格式非法（支持 a+b a-b a*b a/b，如 23*47）: " + expr);
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

    /** 剥掉参数首尾成对的引号（可能多层），并 trim 空白 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
