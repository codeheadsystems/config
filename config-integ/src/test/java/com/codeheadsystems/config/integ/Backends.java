package com.codeheadsystems.config.integ;

import com.codeheadsystems.config.datastore.testkit.TestEnvironment;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The real datastores, one container each, started on first use and shared by every test in the
 * run (Testcontainers removes them when the JVM exits). Each returns the options its backend's
 * provider takes, exactly as they would appear in config-server's {@code datastore.options}.
 *
 * <p>Images are pinned to the same versions as the backends' own tests: keep them in sync with
 * {@code Postgres.java}, {@code DynamodbLocal.java}, {@code RustFs.java} and docker-compose.yml.
 */
final class Backends {

  static final String POSTGRES_IMAGE = "postgres:18.7";
  static final String DYNAMODB_LOCAL_IMAGE = "amazon/dynamodb-local:3.3.2";
  static final String RUSTFS_IMAGE = "rustfs/rustfs:1.0.4";

  /** DynamoDB Local accepts only letters and digits in an access key. */
  private static final String DYNAMODB_ACCESS_KEY = "configinteg";
  private static final String RUSTFS_ACCESS_KEY = "config-integ";
  private static final String SECRET_KEY = "config-integ-secret";
  private static final String BUCKET = "config-integ";

  private static PostgreSQLContainer postgres;
  private static GenericContainer<?> dynamodb;
  private static GenericContainer<?> rustfs;

  private Backends() {
  }

  /** Skips the calling tests without Docker, or fails them on CI (see TestEnvironment). */
  static void requireDocker() {
    TestEnvironment.require(DockerClientFactory.instance().isDockerAvailable(), "Docker");
  }

  @SuppressWarnings("resource") // closed by Testcontainers at JVM exit
  static synchronized Map<String, String> postgres() {
    if (postgres == null) {
      postgres = new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName("config")
          .withUsername("config")
          .withPassword("config");
      postgres.start();
    }
    return Map.of(
        "url", postgres.getJdbcUrl(),
        "username", postgres.getUsername(),
        "password", postgres.getPassword(),
        "migrate", "true");
  }

  @SuppressWarnings("resource") // closed by Testcontainers at JVM exit
  static synchronized Map<String, String> dynamodb() {
    if (dynamodb == null) {
      dynamodb = new GenericContainer<>(DockerImageName.parse(DYNAMODB_LOCAL_IMAGE))
          .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
          .withExposedPorts(8000)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofMinutes(2));
      dynamodb.start();
    }
    return Map.of(
        "table", "config-integ",
        "create-table", "true",
        "endpoint", endpoint(dynamodb, 8000).toString(),
        "region", "us-east-1",
        "access-key", DYNAMODB_ACCESS_KEY,
        "secret-key", SECRET_KEY);
  }

  @SuppressWarnings("resource") // closed by Testcontainers at JVM exit
  static synchronized Map<String, String> s3() {
    if (rustfs == null) {
      rustfs = new GenericContainer<>(DockerImageName.parse(RUSTFS_IMAGE))
          .withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
          .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200))
          .withStartupTimeout(Duration.ofMinutes(2));
      rustfs.start();
      try (S3Client client = S3Client.builder()
          .endpointOverride(endpoint(rustfs, 9000))
          .region(Region.US_EAST_1)
          .forcePathStyle(true)
          .credentialsProvider(StaticCredentialsProvider.create(
              AwsBasicCredentials.create(RUSTFS_ACCESS_KEY, SECRET_KEY)))
          .build()) {
        client.createBucket(request -> request.bucket(BUCKET));
      }
    }
    return Map.of(
        "bucket", BUCKET,
        "endpoint", endpoint(rustfs, 9000).toString(),
        "path-style", "true",
        "region", "us-east-1",
        "access-key", RUSTFS_ACCESS_KEY,
        "secret-key", SECRET_KEY);
  }

  private static URI endpoint(final GenericContainer<?> container, final int port) {
    return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(port));
  }
}
