package com.agentplatform.core.tool;

/**
 * 一次工具调用的执行记录（用于事件流、评测断言与 Tracing）。
 *
 * @param toolName   工具名
 * @param arguments  模型传入的参数字符串
 * @param result     成功时的返回值；失败时为 null
 * @param error      失败时的异常信息；成功时为 null
 * @param durationMs 执行耗时（毫秒），M3 沙箱超时控制与成本统计都要用它
 */
public record ToolExecution(String toolName, String arguments, String result, String error, long durationMs) {

    public boolean isSuccess() {
        return error == null;
    }
}
