package com.codeheadsystems.config.server.settings;

/**
 * The {@code Setting} schema: one key and its value.
 *
 * @param key   the key.
 * @param value the value.
 */
public record SettingResponse(String key, String value) {
}
