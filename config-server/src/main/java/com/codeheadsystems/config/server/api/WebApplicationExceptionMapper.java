package com.codeheadsystems.config.server.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Gives Jersey's own errors (unknown path {@code 404}, {@code 405 Method Not Allowed},
 * {@code 406}) the API's error body, keeping their status and headers such as {@code Allow}.
 */
public final class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

  @Override
  public Response toResponse(final WebApplicationException exception) {
    final Response original = exception.getResponse();
    final Response.ResponseBuilder response = Response.status(original.getStatus())
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(new ErrorResponse(original.getStatusInfo().getReasonPhrase()));
    original.getHeaders().forEach((name, values) -> {
      if (!name.equalsIgnoreCase("Content-Type")) {
        values.forEach(value -> response.header(name, value));
      }
    });
    return response.build();
  }
}
