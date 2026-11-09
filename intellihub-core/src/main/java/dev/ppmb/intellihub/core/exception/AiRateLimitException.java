package dev.ppmb.intellihub.core.exception;

/**
 * AI 模型触发限流或配额耗尽异常（HTTP 429，支持切换 Key 或备用渠道重试）。
 */
public class AiRateLimitException extends IntelliHubException {

    public AiRateLimitException(String provider, String message, Throwable cause) {
        super(GlobalErrorCodeConstants.TOO_MANY_REQUESTS.getCode(), message, provider, true, cause);
    }
}
