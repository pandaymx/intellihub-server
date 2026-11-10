package dev.ppmb.intellihub.app.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ppmb.intellihub.core.exception.BaseErrorCode;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import java.io.Serializable;

/**
 * 统一 RESTful API 响应体包装对象。
 *
 * @param <T> 数据负载泛型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private long timestamp;

    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                GlobalErrorCodeConstants.SUCCESS.code(),
                GlobalErrorCodeConstants.SUCCESS.msg(),
                data);
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(BaseErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    public static <T> ApiResponse<T> error(BaseErrorCode errorCode, String customMessage) {
        return new ApiResponse<>(errorCode.getCode(), customMessage, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
