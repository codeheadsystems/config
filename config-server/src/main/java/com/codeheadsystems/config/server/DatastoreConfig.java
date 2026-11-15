package com.codeheadsystems.config.server;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastores;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Value;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which datastore to serve: a backend name, as registered with {@link ConfigDatastores}, and its
 * options.
 *
 * <pre>
 * datastore:
 *   name: s3
 *   options:
 *     bucket: config
 * </pre>
 */
public class DatastoreConfig {

  @NotEmpty
  private String name = "memory";

  @NotNull
  private Map<String, @NotNull String> options = Map.of();

  /**
   * The backend's name, such as {@code sql}, {@code s3} or {@code memory}.
   *
   * @return the name.
   */
  @JsonProperty
  public String getName() {
    return name;
  }

  /**
   * Sets the backend's name.
   *
   * @param name the name.
   */
  @JsonProperty
  public void setName(final String name) {
    this.name = name;
  }

  /**
   * The backend's options; see each backend's provider for the ones it takes.
   *
   * @return the options.
   */
  @JsonProperty
  public Map<String, String> getOptions() {
    return options;
  }

  /**
   * Sets the backend's options.
   *
   * @param options the options; null fails validation.
   */
  @JsonProperty
  public void setOptions(final Map<String, String> options) {
    this.options = options == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(options));
  }

  /**
   * Opens the datastore.
   *
   * @return the datastore; the caller closes it.
   */
  public ConfigDatastore open() {
    final Map<String, String> values = options;
    return ConfigDatastores.open(name,
        Configuration.from(key -> Optional.ofNullable(values.get(key.name())).flatMap(Value::from)));
  }
}
