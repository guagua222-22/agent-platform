package com.agentplatform.app.agent;

import com.agentplatform.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具（多工具 Agent 的示例零件）：
 * 与 CalculatorTool 一起组成"通用任务 Agent"的工具集，
 * 演示模型如何按用户意图在两个工具间自主选择（工具调用路由）。
 */
@Component
public class TimeTool implements Tool<TimeTool.TimeArgs> {

    public record TimeArgs(String zone) {
    }

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public Class<TimeArgs> inputType() {
        return TimeArgs.class;
    }

    @Override
    public String description() {
        return "获取当前日期和时间。参数是 JSON 对象：{\"zone\": \"时区名，如 Asia/Shanghai，可省略\"}。" +
                "适用于用户询问现在几点、今天日期等问题。";
    }

    @Override
    public String execute(TimeArgs args) {
        ZoneId zone;
        try {
            zone = args == null || args.zone() == null || args.zone().isBlank()
                    ? ZoneId.systemDefault() : ZoneId.of(args.zone());
        } catch (Exception e) {
            throw new IllegalArgumentException("无效时区: " + (args == null ? "null" : args.zone()));
        }
        return "当前时间: " + LocalDateTime.now(zone).format(FORMAT) + " (" + zone + ")";
    }
}
