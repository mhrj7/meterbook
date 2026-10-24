package com.meterbook.identity.scim;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** SCIM's error format (RFC 7644 §3.12), which SCIM clients parse instead of ours. */
final class ScimErrors {

  static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
  static final String MEDIA_TYPE = "application/scim+json";

  private final JsonMapper json;

  ScimErrors(JsonMapper json) {
    this.json = json;
  }

  static Map<String, Object> body(int status, String scimType, String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemas", List.of(ERROR_SCHEMA));
    body.put("status", String.valueOf(status));
    if (scimType != null) {
      body.put("scimType", scimType);
    }
    body.put("detail", detail);
    return body;
  }

  void write(HttpServletResponse response, int status, String scimType, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MEDIA_TYPE);
    json.writeValue(response.getOutputStream(), body(status, scimType, detail));
  }
}
