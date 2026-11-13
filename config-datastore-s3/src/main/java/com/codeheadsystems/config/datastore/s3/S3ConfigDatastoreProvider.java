package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastoreProvider;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Value;
import java.net.URI;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Provides {@link S3ConfigDatastore} under the name {@value #NAME}.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code bucket}: the bucket; required, and must already exist. It is checked when the
 *   datastore is opened, so a wrong bucket or credentials fail immediately.</li>
 *   <li>{@code prefix}: the key prefix; defaults to {@code config/}.</li>
 *   <li>{@code region}: the AWS region; defaults to {@code us-east-1}.</li>
 *   <li>{@code endpoint}: the service URL, for S3-compatible stores such as RustFS
 *   ({@code http://localhost:9000}); defaults to Amazon S3.</li>
 *   <li>{@code path-style}: {@code true} to address the bucket in the path rather than the host
 *   name, which most S3-compatible stores need; defaults to {@code false}.</li>
 *   <li>{@code access-key} and {@code secret-key}: static credentials; both or neither. Without
 *   them, the AWS default credentials chain is used.</li>
 *   <li>{@code max-set-bytes}: the size limit; defaults to
 *   {@link S3ConfigDatastore#DEFAULT_MAX_SET_BYTES}.</li>
 * </ul>
 */
public final class S3ConfigDatastoreProvider implements ConfigDatastoreProvider {

  /**
   * The backend's name.
   */
  public static final String NAME = "s3";

  /**
   * Creates the provider. Called by {@link java.util.ServiceLoader}.
   */
  public S3ConfigDatastoreProvider() {
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ConfigDatastore create(final Configuration options) {
    final String bucket = option(options, "bucket")
      .orElseThrow(() -> new ConfigDatastoreException("The s3 datastore needs a 'bucket' option"));
    final String prefix = option(options, "prefix").orElse("config/");
    final long maxSetBytes = option(options, "max-set-bytes")
      .map(Long::parseLong)
      .orElse(S3ConfigDatastore.DEFAULT_MAX_SET_BYTES);

    final S3ClientBuilder builder = S3Client.builder()
      .region(Region.of(option(options, "region").orElse("us-east-1")))
      .forcePathStyle(option(options, "path-style").map(Boolean::parseBoolean).orElse(false));
    option(options, "endpoint").map(URI::create).ifPresent(builder::endpointOverride);

    final Optional<String> accessKey = option(options, "access-key");
    final Optional<String> secretKey = option(options, "secret-key");
    if (accessKey.isPresent() != secretKey.isPresent()) {
      throw new ConfigDatastoreException("The s3 datastore needs both 'access-key' and 'secret-key', or neither");
    }
    builder.credentialsProvider(accessKey.isPresent()
      ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.get(), secretKey.get()))
      : DefaultCredentialsProvider.builder().build());

    final S3Client s3 = builder.build();
    try {
      // Fail now on a wrong bucket or credentials, rather than on the first read.
      s3.headBucket(request -> request.bucket(bucket));
    } catch (final SdkException e) {
      s3.close();
      throw new ConfigDatastoreException("Cannot open the s3 datastore on bucket '" + bucket + "': "
        + e.getMessage(), e);
    }
    return new S3ConfigDatastore(s3, bucket, prefix, maxSetBytes, true);
  }

  private static Optional<String> option(final Configuration options, final String key) {
    return options.find(key).map(Value::value);
  }
}
