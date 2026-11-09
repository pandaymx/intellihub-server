package dev.ppmb.intellihub.core.exception;

/**
 * AI 模型凭证鉴权异常（如 API Key 无效、过期或无权限，默认不可重试）。
 */
public class AiAuthenticationException extends IntelliHubException {

    public AiAuthenticationException(String provider, String message, Throwable cause) {
        super(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), message, provider, false, cause);
    }
}
