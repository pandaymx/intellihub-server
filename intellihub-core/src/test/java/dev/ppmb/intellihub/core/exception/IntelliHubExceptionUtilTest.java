package dev.ppmb.intellihub.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

class IntelliHubExceptionUtilTest {

    @Test
    void exception_withParams_formatsPlaceholders() {
        IntelliHubException ex =
                IntelliHubExceptionUtil.exception(
                        GlobalErrorCodeConstants.AI_PROVIDER_NOT_FOUND, "deepseek");

        assertThat(ex.getCode()).isEqualTo(1_001_000_002);
        assertThat(ex.getMessage()).isEqualTo("未找到可用的 AI 模型渠道: deepseek");
        assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    void wrap_timeoutException_returnsRetryableAiTimeoutException() {
        SocketTimeoutException cause = new SocketTimeoutException("Read timed out");

        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap("deepseek", cause);

        assertThat(wrapped).isInstanceOf(AiTimeoutException.class);
        assertThat(wrapped.getCode()).isEqualTo(GlobalErrorCodeConstants.REQUEST_TIMEOUT.getCode());
        assertThat(wrapped.getProvider()).isEqualTo("deepseek");
        assertThat(wrapped.isRetryable()).isTrue();
        assertThat(wrapped.getCause()).isSameAs(cause);
    }

    @Test
    void wrap_rateLimitError_returnsRetryableAiRateLimitException() {
        RuntimeException cause =
                new RuntimeException("HTTP 429 - Too Many Requests: rate limit reached");

        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap("openai", cause);

        assertThat(wrapped).isInstanceOf(AiRateLimitException.class);
        assertThat(wrapped.getCode())
                .isEqualTo(GlobalErrorCodeConstants.TOO_MANY_REQUESTS.getCode());
        assertThat(wrapped.getProvider()).isEqualTo("openai");
        assertThat(wrapped.isRetryable()).isTrue();
    }

    @Test
    void wrap_authError_returnsNonRetryableAiAuthenticationException() {
        RuntimeException cause = new RuntimeException("401 Unauthorized: invalid_api_key");

        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap("qwen", cause);

        assertThat(wrapped).isInstanceOf(AiAuthenticationException.class);
        assertThat(wrapped.getCode()).isEqualTo(GlobalErrorCodeConstants.UNAUTHORIZED.getCode());
        assertThat(wrapped.getProvider()).isEqualTo("qwen");
        assertThat(wrapped.isRetryable()).isFalse();
    }

    @Test
    void wrap_existingIntelliHubException_returnsSameInstance() {
        IntelliHubException original =
                IntelliHubExceptionUtil.invalidParamException(
                        "temperature 必须在 [0, 2] 之间，当前值: {}", 3.5);

        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap("openai", original);

        assertThat(wrapped).isSameAs(original);
        assertThat(wrapped.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
        assertThat(wrapped.getMessage()).isEqualTo("temperature 必须在 [0, 2] 之间，当前值: 3.5");
    }
}
