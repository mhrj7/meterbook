package com.meterbook.identity.scim;

/** A SCIM error (RFC 7644 §3.12): HTTP status, optional {@code scimType}, and a detail message. */
public final class ScimException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String scimType;
  private final Integer retryAfterSeconds;

  private ScimException(int status, String scimType, String detail, Integer retryAfterSeconds) {
    super(detail);
    this.status = status;
    this.scimType = scimType;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  static ScimException invalidValue(String detail) {
    return new ScimException(400, "invalidValue", detail, null);
  }

  static ScimException invalidSyntax(String detail) {
    return new ScimException(400, "invalidSyntax", detail, null);
  }

  static ScimException invalidFilter(String detail) {
    return new ScimException(400, "invalidFilter", detail, null);
  }

  static ScimException invalidPath(String detail) {
    return new ScimException(400, "invalidPath", detail, null);
  }

  static ScimException mutability(String detail) {
    return new ScimException(400, "mutability", detail, null);
  }

  static ScimException uniqueness(String detail) {
    return new ScimException(409, "uniqueness", detail, null);
  }

  static ScimException notFound(String detail) {
    return new ScimException(404, null, detail, null);
  }

  static ScimException preconditionFailed() {
    return new ScimException(412, null, "the resource changed since the version in If-Match", null);
  }

  static ScimException unavailable() {
    return new ScimException(
        503, null, "the identity provider is unavailable; nothing was changed", 2);
  }

  public int status() {
    return status;
  }

  public String scimType() {
    return scimType;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
