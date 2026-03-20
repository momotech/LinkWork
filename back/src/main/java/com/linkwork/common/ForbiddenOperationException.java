package com.linkwork.common;

/**
 * 禁止访问异常
 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
