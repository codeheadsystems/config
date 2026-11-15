package com.codeheadsystems.config.server.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code 500} for anything else; the details are logged, not sent to the client. */
public final class UnexpectedExceptionMapper implements ExceptionMapper<Throwable> {

  private static final Logger LOG = LoggerFactory.getLogger(UnexpectedExceptionMapper.class);

  @Override
  public Response toResponse(final Throwable exception) {
    LOG.error("Unexpected error serving a request", exception);
    return Response.serverError()
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(new ErrorResponse("Internal error"))
        .build();
  }
}
