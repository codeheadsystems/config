package com.codeheadsystems.config.server.api;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

/**
 * Keeps {@code Content-Length} off {@code HEAD .../settings} responses. That {@code HEAD} answers
 * from the set's version alone, so the server does not know how long the {@code GET} body would
 * be, and RFC 9110 (section 8.6) forbids any other value; Jersey would send {@code 0}. The
 * response is committed without a length instead. Every other request passes through untouched
 * ({@code HEAD} on one key is answered from the full {@code GET}, so its length is right).
 */
public final class HeadContentLengthFilter implements Filter {

  private static final String CONTENT_LENGTH = "Content-Length";

  @Override
  public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse servlet)
        || !"HEAD".equals(http.getMethod()) || !http.getRequestURI().endsWith("/settings")) {
      chain.doFilter(request, response);
      return;
    }
    chain.doFilter(request, new HttpServletResponseWrapper(servlet) {
      @Override
      public void setContentLength(final int length) {
        // dropped: see the class comment
      }

      @Override
      public void setContentLengthLong(final long length) {
        // dropped: see the class comment
      }

      @Override
      public void setHeader(final String name, final String value) {
        if (!CONTENT_LENGTH.equalsIgnoreCase(name)) {
          super.setHeader(name, value);
        }
      }

      @Override
      public void addHeader(final String name, final String value) {
        if (!CONTENT_LENGTH.equalsIgnoreCase(name)) {
          super.addHeader(name, value);
        }
      }

      @Override
      public void setIntHeader(final String name, final int value) {
        if (!CONTENT_LENGTH.equalsIgnoreCase(name)) {
          super.setIntHeader(name, value);
        }
      }

      @Override
      public void addIntHeader(final String name, final int value) {
        if (!CONTENT_LENGTH.equalsIgnoreCase(name)) {
          super.addIntHeader(name, value);
        }
      }
    });
    if (!servlet.isCommitted()) {
      // Committing now, with no length set, stops the server computing one (0) at completion.
      servlet.flushBuffer();
    }
  }
}
