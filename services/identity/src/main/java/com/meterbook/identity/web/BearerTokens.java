package com.meterbook.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** The credential in {@code Authorization: Bearer <credential>}, if the request carries one. */
public final class BearerTokens {

  private static final String PREFIX = "Bearer ";

  private BearerTokens() {}

  /**
   * Only extracts: the caller decides whether the credential is valid (a hash lookup), so the value
   * never grants anything on its own.
   */
  public static Optional<String> of(HttpServletRequest request) {
    return Optional.ofNullable(request.getHeader("Authorization"))
        .filter(header -> header.regionMatches(true, 0, PREFIX, 0, PREFIX.length()))
        .map(header -> header.substring(PREFIX.length()).trim())
        .filter(credential -> !credential.isEmpty());
  }
}
