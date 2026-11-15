package com.codeheadsystems.config.server.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/** Turns an {@link ApiException} into its response. */
public final class ApiExceptionMapper implements ExceptionMapper<ApiException> {

  @Override
  public Response toResponse(final ApiException exception) {
    final Response.ResponseBuilder response = Response.status(exception.status())
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(new ErrorResponse(exception.getMessage()));
    exception.headers().forEach(response::header);
    return response.build();
  }
}
