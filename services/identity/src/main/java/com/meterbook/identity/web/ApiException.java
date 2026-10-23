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
  private final Integer retryAfterSeconds;

  private ApiException(
      HttpStatus status,
      String type,
      String code,
      String message,
      String param,
      Integer retryAfterSeconds) {
    super(message);
    this.status = status;
    this.type = type;
    this.code = code;
    this.param = param;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public static ApiException badRequest(String code, String message, String param) {
    return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", code, message, param, null);
  }

  public static ApiException conflict(String code, String message, Integer retryAfterSeconds) {
    return new ApiException(
        HttpStatus.CONFLICT, "conflict", code, message, null, retryAfterSeconds);
  }

  public static ApiException unavailable(String code, String message, int retryAfterSeconds) {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", code, message, null, retryAfterSeconds);
  }

  public static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "permission_error", code, message, null, null);
  }

  public static ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", code, message, null, null);
  }

  public static ApiException invalid(String code, String message, String param) {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_CONTENT, "invalid_request", code, message, param, null);
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

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
