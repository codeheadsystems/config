package com.codeheadsystems.config.server.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.eclipse.jetty.io.EofException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client went away mid-request. Nothing can be sent, so there is nothing to log at error
 * level; this replaces Dropwizard's own mapper, which the server turns off with the others.
 */
public final class EofExceptionMapper implements ExceptionMapper<EofException> {

  private static final Logger LOG = LoggerFactory.getLogger(EofExceptionMapper.class);

  @Override
  public Response toResponse(final EofException exception) {
    LOG.debug("Client disconnected", exception);
    return Response.status(Response.Status.BAD_REQUEST).build();
  }
}
