package dev.ppmb.intellihub.core.exception;

/**
 * 上游 AI 模型渠道服务异常（如 5xx 错误、网关断开等，支持切换备用渠道重试）。
 */
public class AiProviderException extends IntelliHubException {

    public AiProviderException(
            String provider, String message, boolean retryable, Throwable cause) {
        super(
                GlobalErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode(),
                message,
                provider,
                retryable,
                cause);
    }
}
