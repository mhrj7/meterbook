package com.meterbook.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps {@link ApiException} to the single error shape of api-standards.md. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrors.ErrorBody> handle(ApiException e, HttpServletRequest request) {
    return ResponseEntity.status(e.status())
        .body(
            new ApiErrors.ErrorBody(
                new ApiErrors.ErrorBody.Detail(
                    e.type(), e.code(), e.getMessage(), e.param(), RequestIdFilter.of(request))));
  }
}
