package com.meterbook.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure inside a controller to the single error shape of api-standards.md. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrors.ErrorBody> handle(ApiException e, HttpServletRequest request) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
    if (e.retryAfterSeconds() != null) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
    }
    return response.body(body(e.type(), e.code(), e.getMessage(), e.param(), request));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrors.ErrorBody> unreadable(HttpServletRequest request) {
    // The parser's message can echo request content; keep it out of the response.
    return error(
        HttpStatus.BAD_REQUEST,
        "invalid_request",
        "malformed_body",
        "the request body is not valid JSON for this endpoint, or has unknown fields",
        null,
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrors.ErrorBody> invalid(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    FieldError field = e.getBindingResult().getFieldError();
    String param = field == null ? null : snakeCase(field.getField());
    String message =
        field == null ? "the request is invalid" : param + " " + field.getDefaultMessage();
    return error(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "invalid_request",
        "invalid_field",
        message,
        param,
        request);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiErrors.ErrorBody> missingHeader(
      MissingRequestHeaderException e, HttpServletRequest request) {
    String code =
        "Idempotency-Key".equalsIgnoreCase(e.getHeaderName())
            ? "idempotency_key_required"
            : "missing_header";
    return error(
        HttpStatus.BAD_REQUEST,
        "invalid_request",
        code,
        "the " + e.getHeaderName() + " header is required",
        e.getHeaderName(),
        request);
  }

  @ExceptionHandler({NoResourceFoundException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<ApiErrors.ErrorBody> notFound(HttpServletRequest request) {
    return error(HttpStatus.NOT_FOUND, "not_found", "not_found", "no such resource", null, request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiErrors.ErrorBody> methodNotAllowed(
      HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
    return error(
        HttpStatus.METHOD_NOT_ALLOWED,
        "invalid_request",
        "method_not_allowed",
        e.getMethod() + " is not supported here",
        null,
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiErrors.ErrorBody> unsupportedMediaType(HttpServletRequest request) {
    return error(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "invalid_request",
        "unsupported_media_type",
        "send application/json",
        null,
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrors.ErrorBody> unexpected(Exception e, HttpServletRequest request) {
    log.error("unhandled error", e);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal_error",
        "internal_error",
        "something went wrong on our side; quote the request_id when reporting it",
        null,
        request);
  }

  private static ResponseEntity<ApiErrors.ErrorBody> error(
      HttpStatus status,
      String type,
      String code,
      String message,
      String param,
      HttpServletRequest request) {
    return ResponseEntity.status(status).body(body(type, code, message, param, request));
  }

  private static ApiErrors.ErrorBody body(
      String type, String code, String message, String param, HttpServletRequest request) {
    return new ApiErrors.ErrorBody(
        new ApiErrors.ErrorBody.Detail(type, code, message, param, RequestIdFilter.of(request)));
  }

  static String snakeCase(String camel) {
    return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
  }
}
