package dev.ppmb.intellihub.core.advisor;

/**
 * AI 调用耗时与 Token 统计汇总快照。
 *
 * @param totalCalls 总请求数
 * @param successCalls 成功请求数
 * @param failedCalls 失败请求数
 * @param totalDurationMs 累计耗时（毫秒）
 * @param avgDurationMs 平均单次耗时（毫秒）
 * @param totalPromptTokens 累计输入 Token 数
 * @param totalCompletionTokens 累计输出 Token 数
 * @param totalTokens 累计总 Token 数
 */
public record AiExecutionStats(
        long totalCalls,
        long successCalls,
        long failedCalls,
        long totalDurationMs,
        double avgDurationMs,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalTokens) {}
