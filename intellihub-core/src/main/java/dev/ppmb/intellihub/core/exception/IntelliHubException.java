package dev.ppmb.intellihub.core.exception;

/**
 * Intelli-Hub 统一运行时异常基类。
 *
 * <p>封装错误码 {@code code}、模型渠道标识 {@code provider} 以及是否可重试标识 {@code retryable}，
 * 便于上层调用方统一捕获处理，或由路由器/KeyPool 根据 {@link #isRetryable()} 决定是否熔断降级。
 */
public class IntelliHubException extends RuntimeException {

    private final Integer code;
    private final String provider;
    private final boolean retryable;

    public IntelliHubException(BaseErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMsg(), null, false, null);
    }

    public IntelliHubException(BaseErrorCode errorCode, Throwable cause) {
        this(errorCode.getCode(), errorCode.getMsg(), null, false, cause);
    }

    public IntelliHubException(Integer code, String message) {
        this(code, message, null, false, null);
    }

    public IntelliHubException(Integer code, String message, Throwable cause) {
        this(code, message, null, false, cause);
    }

    public IntelliHubException(
            Integer code, String message, String provider, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.provider = provider;
        this.retryable = retryable;
    }

    public Integer getCode() {
        return code;
    }

    public String getProvider() {
        return provider;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
