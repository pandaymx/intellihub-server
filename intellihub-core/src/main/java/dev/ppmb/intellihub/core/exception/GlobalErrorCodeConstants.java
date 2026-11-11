package dev.ppmb.intellihub.core.exception;

/**
 * 全局系统级与 AI 核心错误码常量。
 *
 * <ul>
 *   <li>{@code 0 ~ 999}：系统通用与 HTTP 标准映射错误码
 *   <li>{@code 1_001_000_000+}：AI 组件细分业务错误码
 * </ul>
 */
public final class GlobalErrorCodeConstants {

    private GlobalErrorCodeConstants() {}

    // ========== 系统通用错误码 (0 ~ 999) ==========
    public static final ErrorCode SUCCESS = new ErrorCode(0, "成功");
    public static final ErrorCode BAD_REQUEST = new ErrorCode(400, "请求参数不正确: {}");
    public static final ErrorCode UNAUTHORIZED = new ErrorCode(401, "模型凭证无效或未授权");
    public static final ErrorCode FORBIDDEN = new ErrorCode(403, "没有该模型或接口的访问权限");
    public static final ErrorCode NOT_FOUND = new ErrorCode(404, "请求的模型或资源不存在: {}");
    public static final ErrorCode REQUEST_TIMEOUT = new ErrorCode(408, "模型调用超时");
    public static final ErrorCode TOO_MANY_REQUESTS = new ErrorCode(429, "模型请求过于频繁或配额已耗尽");
    public static final ErrorCode INTERNAL_SERVER_ERROR = new ErrorCode(500, "系统内部异常");
    public static final ErrorCode NOT_IMPLEMENTED = new ErrorCode(501, "功能未实现或未开启");
    public static final ErrorCode BAD_GATEWAY = new ErrorCode(502, "上游模型服务响应异常: {}");
    public static final ErrorCode SERVICE_UNAVAILABLE = new ErrorCode(503, "模型服务暂时不可用，请稍后重试");
    public static final ErrorCode UNKNOWN = new ErrorCode(999, "未知异常");

    // ========== AI 核心组件错误码 (1_001_000_000+) ==========
    public static final ErrorCode AI_PROMPT_EMPTY = new ErrorCode(1_001_000_001, "提问或提示词内容不能为空");
    public static final ErrorCode AI_PROVIDER_NOT_FOUND =
            new ErrorCode(1_001_000_002, "未找到可用的 AI 模型渠道: {}");
    public static final ErrorCode AI_KEY_POOL_EXHAUSTED =
            new ErrorCode(1_001_000_003, "当前渠道无可用 API Key（均处于熔断冷却或未配置）");
    public static final ErrorCode AI_MODEL_CALL_FAILED =
            new ErrorCode(1_001_000_004, "AI 模型调用失败: {}");
    public static final ErrorCode AI_STRUCTURED_OUTPUT_PARSE_ERROR =
            new ErrorCode(1_001_000_005, "AI 结构化输出解析失败: {}");
    public static final ErrorCode AI_GUARD_REJECTED =
            new ErrorCode(1_001_000_006, "内容触发安全护栏拦截: {}");
    public static final ErrorCode AI_TOOL_EXECUTION_ERROR =
            new ErrorCode(1_001_000_007, "AI 工具调用执行异常: {}");
    public static final ErrorCode AI_DOCUMENT_PARSE_ERROR =
            new ErrorCode(1_001_000_008, "文档解析失败: {}");
    public static final ErrorCode AI_UNSUPPORTED_FILE_FORMAT =
            new ErrorCode(1_001_000_009, "不支持的文件格式: {}");
}
