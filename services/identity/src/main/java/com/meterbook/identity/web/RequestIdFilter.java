package com.meterbook.identity.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a {@code Request-Id} (api-standards.md): reused from the caller when it has
 * our format, otherwise generated. It is set on the response before anything else runs, so error
 * responses carry it too, and it is in the logging context as {@code request_id}.
 */
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "Request-Id";
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
  public static final String MDC_KEY = "request_id";

  private final RequestIds ids;

  public RequestIdFilter(RequestIds ids) {
    this.ids = ids;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String id = RequestIds.isValid(incoming) ? incoming : ids.next();
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(HEADER, id);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** The current request's ID, for error bodies. */
  public static String of(HttpServletRequest request) {
    Object id = request.getAttribute(ATTRIBUTE);
    return id instanceof String s ? s : "";
  }
}
