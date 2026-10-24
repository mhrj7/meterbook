package com.meterbook.identity.scim;

import com.meterbook.identity.directory.Directory;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errors from the SCIM endpoints, in SCIM's format instead of ours (RFC 7644 §3.12). */
@RestControllerAdvice(assignableTypes = {ScimUsersController.class, ScimGroupsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScimExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ScimExceptionHandler.class);
  private static final MediaType SCIM = MediaType.parseMediaType(ScimErrors.MEDIA_TYPE);

  @ExceptionHandler(ScimException.class)
  ResponseEntity<Map<String, Object>> scim(ScimException e) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).contentType(SCIM);
    if (e.retryAfterSeconds() != null) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
    }
    return response.body(ScimErrors.body(e.status(), e.scimType(), e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable() {
    return scim(ScimException.invalidSyntax("the request body is not valid JSON"));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType() {
    return scim(ScimException.invalidSyntax("send application/scim+json"));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> badId() {
    return scim(ScimException.notFound("no such resource"));
  }

  @ExceptionHandler(Directory.UnavailableException.class)
  ResponseEntity<Map<String, Object>> unavailable() {
    return scim(ScimException.unavailable());
  }

  @ExceptionHandler({DataIntegrityViolationException.class, Directory.ConflictException.class})
  ResponseEntity<Map<String, Object>> conflict() {
    return scim(ScimException.uniqueness("a resource with this name already exists"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    log.error("unhandled SCIM error", e);
    return ResponseEntity.status(500)
        .contentType(SCIM)
        .body(ScimErrors.body(500, null, "internal error"));
  }
}
