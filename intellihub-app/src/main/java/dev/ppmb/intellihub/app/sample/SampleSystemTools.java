package dev.ppmb.intellihub.app.sample;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供 Agent 联调测试调用的内置示例工具组件（含常规工具与高风险人工审批工具）。
 */
@Component
public class SampleSystemTools {

    public static final String TOOL_SERVER_TIME = "get_current_server_time";
    public static final String TOOL_CALCULATE_TAX = "calculate_tax";
    public static final String TOOL_REFUND_TRANSFER = "execute_refund_transfer";

    @Tool(name = TOOL_SERVER_TIME, description = "获取当前系统服务器的标准日期时间（格式：yyyy-MM-dd HH:mm:ss）")
    public String getCurrentServerTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(name = TOOL_CALCULATE_TAX, description = "根据金额（元）和税率百分比计算含税总额与应纳税额")
    public String calculateTax(
            @ToolParam(description = "不含税原始金额（元）") double amount,
            @ToolParam(description = "税率百分比（例如 6 或 13）") double taxRatePercent) {
        double tax = amount * (taxRatePercent / 100.0);
        double total = amount + tax;
        return String.format("原始金额: %.2f 元, 税额: %.2f 元, 含税总计: %.2f 元", amount, tax, total);
    }

    @Tool(name = TOOL_REFUND_TRANSFER, description = "执行售后工单资金原路退回打款操作（高风险敏感操作，需人工审批放行）")
    public String executeRefundTransfer(
            @ToolParam(description = "售后订单编号") String orderId,
            @ToolParam(description = "退回金额（元）") double refundAmount) {
        return String.format(
                "{\"status\":\"SUCCESS\",\"orderId\":\"%s\",\"refundedAmount\":%.2f}",
                orderId, refundAmount);
    }
}
