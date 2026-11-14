package com.codeheadsystems.config.datastore.dynamodb;

import com.codeheadsystems.config.datastore.testkit.TestEnvironment;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * One DynamoDB Local container shared by every DynamoDB test in the run, started on first use.
 * Each test gets its own table, so it starts with an empty store. Testcontainers removes the
 * container when the JVM exits.
 */
final class DynamodbLocal {

  /** Keep in sync with the dynamodb service in docker-compose.yml. */
  static final String DYNAMODB_LOCAL_IMAGE = "amazon/dynamodb-local:3.3.2";
  static final String ACCESS_KEY = "configtest"; // DynamoDB Local 2+ accepts only letters and digits here
  static final String SECRET_KEY = "config-test-secret";
  static final String REGION = "us-east-1";

  private static GenericContainer<?> container;
  private static DynamoDbClient client;

  private DynamodbLocal() {
  }

  /** Skips the calling tests without Docker, or fails them on CI (see TestEnvironment). */
  static void requireDocker() {
    TestEnvironment.require(DockerClientFactory.instance().isDockerAvailable(), "Docker");
  }

  @SuppressWarnings("resource") // closed by Testcontainers at JVM exit
  static synchronized DynamoDbClient client() {
    if (client == null) {
      container = new GenericContainer<>(DockerImageName.parse(DYNAMODB_LOCAL_IMAGE))
        .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
        .withExposedPorts(8000)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofMinutes(2));
      container.start();
      client = DynamoDbClient.builder()
        .endpointOverride(containerEndpoint())
        .region(Region.of(REGION))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build();
    }
    return client;
  }

  /** Starts the container if needed. */
  static URI endpoint() {
    client();
    return containerEndpoint();
  }

  /**
   * The running container's endpoint. Must not call {@link #client()}: client() uses this while
   * the client is still null, and calling back into it would start another container, endlessly.
   */
  private static URI containerEndpoint() {
    return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8000));
  }

  /** A table name no other test uses; not created. */
  static String freshTableName() {
    return "test-" + UUID.randomUUID();
  }

  /** A new, empty table with the datastore's key schema. */
  static String freshTable() {
    final String table = freshTableName();
    DynamodbConfigTable.create(client(), table);
    return table;
  }
}
