package com.dailymoney.shared.web;

import org.springframework.http.HttpStatus;

/** Business error returned to clients as RFC 9457 Problem Details with a stable `code`. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
