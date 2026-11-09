package dev.ppmb.intellihub.core.exception;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 统一异常构建、{@code {}} 占位符格式化与底层异常（含 Spring AI / HTTP / 超时异常）包装工具类。
 */
public final class IntelliHubExceptionUtil {

    private IntelliHubExceptionUtil() {}

    /**
     * 根据错误码直接构建异常。
     */
    public static IntelliHubException exception(BaseErrorCode errorCode) {
        return new IntelliHubException(errorCode.getCode(), errorCode.getMsg());
    }

    /**
     * 根据错误码与 {@code {}} 占位符参数构建异常。
     */
    public static IntelliHubException exception(BaseErrorCode errorCode, Object... params) {
        return new IntelliHubException(errorCode.getCode(), format(errorCode.getMsg(), params));
    }

    /**
     * 构建参数校验失败异常（400）。
     */
    public static IntelliHubException invalidParamException(String pattern, Object... params) {
        return new IntelliHubException(
                GlobalErrorCodeConstants.BAD_REQUEST.getCode(), format(pattern, params));
    }

    /**
     * 将任意底层异常包装转换为 {@link IntelliHubException}。
     */
    public static IntelliHubException wrap(Throwable throwable) {
        return wrap(null, throwable);
    }

    /**
     * 将指定模型渠道抛出的底层异常（如超时、401/429、Spring AI Transient/NonTransient 异常）
     * 统一包装为语义化的 {@link IntelliHubException} 子类。
     */
    public static IntelliHubException wrap(String provider, Throwable throwable) {
        if (throwable == null) {
            return exception(GlobalErrorCodeConstants.UNKNOWN);
        }
        if (throwable instanceof IntelliHubException existing) {
            return existing;
        }

        Throwable root = unwrapRootCause(throwable);
        String msg = throwable.getMessage() != null ? throwable.getMessage() : root.toString();

        // 1. 识别超时类异常
        if (isTimeout(throwable) || isTimeout(root)) {
            return new AiTimeoutException(provider, format("模型调用超时: {}", msg), throwable);
        }

        // 2. 识别参数非法异常
        if (throwable instanceof IllegalArgumentException) {
            return new IntelliHubException(
                    GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    format(GlobalErrorCodeConstants.BAD_REQUEST.getMsg(), msg),
                    provider,
                    false,
                    throwable);
        }

        // 3. 识别 HTTP 401/403 鉴权失败与 429 限流
        String lowerMsg = msg.toLowerCase();
        if (lowerMsg.contains("401")
                || lowerMsg.contains("unauthorized")
                || lowerMsg.contains("invalid api key")
                || lowerMsg.contains("invalid_api_key")) {
            return new AiAuthenticationException(provider, format("模型凭证鉴权失败: {}", msg), throwable);
        }
        if (lowerMsg.contains("429")
                || lowerMsg.contains("rate limit")
                || lowerMsg.contains("too many requests")
                || lowerMsg.contains("quota")) {
            return new AiRateLimitException(provider, format("模型请求触发限流: {}", msg), throwable);
        }

        // 4. 识别 Spring AI 瞬态/非瞬态异常（通过类名层次判断，保持 core 零 Spring 强耦合）
        boolean retryable =
                isTransientInHierarchy(throwable)
                        || lowerMsg.contains("502")
                        || lowerMsg.contains("503")
                        || lowerMsg.contains("504");

        return new AiProviderException(
                provider,
                format(GlobalErrorCodeConstants.AI_MODEL_CALL_FAILED.getMsg(), msg),
                retryable,
                throwable);
    }

    /**
     * 使用 {@code {}} 占位符格式化字符串。
     */
    public static String format(String pattern, Object... params) {
        if (pattern == null) {
            return "";
        }
        if (params == null || params.length == 0) {
            return pattern;
        }
        StringBuilder result = new StringBuilder(pattern.length() + 16);
        int from = 0;
        for (Object param : params) {
            int marker = pattern.indexOf("{}", from);
            if (marker < 0) {
                break;
            }
            result.append(pattern, from, marker).append(param);
            from = marker + 2;
        }
        return result.append(pattern, from, pattern.length()).toString();
    }

    private static boolean isTimeout(Throwable t) {
        return t instanceof SocketTimeoutException
                || t instanceof HttpTimeoutException
                || t instanceof TimeoutException;
    }

    private static boolean isTransientInHierarchy(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            Class<?> clazz = curr.getClass();
            while (clazz != null && clazz != Object.class) {
                if ("TransientAiException".equals(clazz.getSimpleName())) {
                    return true;
                }
                if ("NonTransientAiException".equals(clazz.getSimpleName())) {
                    return false;
                }
                clazz = clazz.getSuperclass();
            }
            curr = curr.getCause();
        }
        return false;
    }

    private static Throwable unwrapRootCause(Throwable t) {
        Throwable curr = t;
        while (curr.getCause() != null && curr.getCause() != curr) {
            curr = curr.getCause();
        }
        return curr;
    }
}
