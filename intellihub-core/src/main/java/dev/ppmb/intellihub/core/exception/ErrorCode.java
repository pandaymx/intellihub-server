package dev.ppmb.intellihub.core.exception;

import java.util.Objects;

/**
 * 不可变错误码对象。
 *
 * @param code 错误码
 * @param msg 错误描述模板
 */
public record ErrorCode(Integer code, String msg) implements BaseErrorCode {

    public ErrorCode {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(msg, "msg must not be null");
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    @Override
    public String toString() {
        return code + ":" + msg;
    }
}
