package com.meterbook.identity.web;

import org.springframework.http.HttpStatus;

/**
 * A failure with a status, type and code from api-standards.md; mapped by {@link
 * ApiExceptionHandler}.
 */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;
  private final String type;
  private final String code;
  private final String param;

  private ApiException(HttpStatus status, String type, String code, String message, String param) {
    super(message);
    this.status = status;
    this.type = type;
    this.code = code;
    this.param = param;
  }

  public static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "permission_error", code, message, null);
  }

  public static ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", code, message, null);
  }

  public static ApiException invalid(String code, String message, String param) {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_CONTENT, "invalid_request", code, message, param);
  }

  public HttpStatus status() {
    return status;
  }

  public String type() {
    return type;
  }

  public String code() {
    return code;
  }

  public String param() {
    return param;
  }
}
