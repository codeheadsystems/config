package com.codeheadsystems.config.datastore.s3;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import com.codeheadsystems.config.datastore.testkit.TestEnvironment;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One RustFS container shared by every S3 test in the run, started on first use. Testcontainers
 * removes it when the JVM exits.
 */
final class RustFs {

  /** Keep in sync with the rustfs service in docker-compose.yml. */
  static final String RUSTFS_IMAGE = "rustfs/rustfs:1.0.4";
  static final String ACCESS_KEY = "config-test";
  static final String SECRET_KEY = "config-test-secret";
  static final String BUCKET = "config-test";

  private static GenericContainer<?> container;
  private static S3Client client;

  private RustFs() {
  }

  /** Skips the calling tests without Docker, or fails them on CI (see TestEnvironment). */
  static void requireDocker() {
    TestEnvironment.require(DockerClientFactory.instance().isDockerAvailable(), "Docker");
  }

  @SuppressWarnings("resource") // closed by Testcontainers at JVM exit
  static synchronized S3Client client() {
    if (client == null) {
      container = new GenericContainer<>(DockerImageName.parse(RUSTFS_IMAGE))
        .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
        .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(2));
      container.start();
      client = S3Client.builder()
        .endpointOverride(endpoint())
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build();
      client.createBucket(request -> request.bucket(BUCKET));
    }
    return client;
  }

  static URI endpoint() {
    return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(9000));
  }

  /** A prefix no other test uses, so each test starts with an empty store. */
  static String freshPrefix() {
    return "test-" + UUID.randomUUID() + "/";
  }
}
