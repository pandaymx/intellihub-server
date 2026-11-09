package dev.ppmb.intellihub.core.exception;

/**
 * AI 模型调用超时异常（连接超时或读取超时，支持重试或降级）。
 */
public class AiTimeoutException extends IntelliHubException {

    public AiTimeoutException(String provider, String message, Throwable cause) {
        super(GlobalErrorCodeConstants.REQUEST_TIMEOUT.getCode(), message, provider, true, cause);
    }
}
