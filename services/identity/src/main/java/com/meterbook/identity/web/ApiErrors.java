package com.meterbook.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the single error shape of api-standards.md for failures that happen before a controller
 * runs.
 */
public final class ApiErrors {

  /** Body of every error response. */
  public record ErrorBody(Detail error) {
    /** Error details; {@code param} is omitted when null. */
    public record Detail(
        String type, String code, String message, String param, String requestId) {}
  }

  private final JsonMapper json;

  public ApiErrors(JsonMapper json) {
    this.json = json;
  }

  public AuthenticationEntryPoint unauthorized() {
    return (request, response, e) -> {
      // RFC 6750: tell the client which scheme to use.
      response.setHeader("WWW-Authenticate", "Bearer realm=\"meterbook\"");
      write(
          request,
          response,
          401,
          "authentication_error",
          "invalid_credentials",
          "missing, invalid or expired credentials");
    };
  }

  public AccessDeniedHandler forbidden() {
    return (request, response, e) ->
        write(
            request,
            response,
            403,
            "permission_error",
            "insufficient_role",
            "the credentials do not grant this operation");
  }

  void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String type,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ErrorBody body =
        new ErrorBody(new ErrorBody.Detail(type, code, message, null, RequestIdFilter.of(request)));
    json.writeValue(response.getOutputStream(), body);
  }
}
