package dev.ppmb.intellihub.core.exception;

/**
 * 统一错误码契约接口。
 */
public interface BaseErrorCode {

    /**
     * 错误码数值。
     */
    Integer getCode();

    /**
     * 错误提示信息（支持 {@code {}} 占位符）。
     */
    String getMsg();
}
