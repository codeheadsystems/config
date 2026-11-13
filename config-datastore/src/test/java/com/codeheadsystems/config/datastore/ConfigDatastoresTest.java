package com.codeheadsystems.config.datastore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.memory.InMemoryConfigDatastore;
import com.codeheadsystems.config.model.Configuration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigDatastoresTest {

  private static final Configuration NO_OPTIONS = Configuration.builder().withNullFunction().build();

  @Test
  void theInMemoryBackendIsAvailable() {
    assertThat(ConfigDatastores.available()).contains("memory");
  }

  @Test
  void openFindsTheBackendByName() {
    try (ConfigDatastore datastore = ConfigDatastores.open("memory", NO_OPTIONS)) {
      assertThat(datastore).isInstanceOf(InMemoryConfigDatastore.class);
      assertThat(datastore.maxSetBytes()).isEqualTo(InMemoryConfigDatastore.DEFAULT_MAX_SET_BYTES);
    }
  }

  @Test
  void optionsArePassedToTheBackend() {
    final Configuration options = Configuration.builder().with(Map.of("max-set-bytes", "2048")).build();

    try (ConfigDatastore datastore = ConfigDatastores.open("memory", options)) {
      assertThat(datastore.maxSetBytes()).isEqualTo(2048);
    }
  }

  @Test
  void namespacedOptionsWork() {
    final Configuration bootstrap = Configuration.builder()
      .with(Map.of("datastore.memory.max-set-bytes", "512"))
      .build();

    try (ConfigDatastore datastore = ConfigDatastores.open("memory", bootstrap.with("datastore.memory"))) {
      assertThat(datastore.maxSetBytes()).isEqualTo(512);
    }
  }

  @Test
  void unknownNameIsRejectedListingWhatIsAvailable() {
    assertThatThrownBy(() -> ConfigDatastores.open("nope", NO_OPTIONS))
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("nope")
      .hasMessageContaining("memory");
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> ConfigDatastores.open(null, NO_OPTIONS)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ConfigDatastores.open("memory", null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void inMemoryLimitMustBePositive() {
    assertThatThrownBy(() -> new InMemoryConfigDatastore(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
