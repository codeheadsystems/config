package com.codeheadsystems.config.server.api;

/**
 * The API's error body, {@code {"message": "..."}} (the {@code Error} schema).
 *
 * @param message what went wrong, for a person to read.
 */
public record ErrorResponse(String message) {
}
