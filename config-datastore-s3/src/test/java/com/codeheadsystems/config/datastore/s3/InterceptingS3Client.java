package com.codeheadsystems.config.datastore.s3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.function.Consumer;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Wraps an S3 client so a test can see, change or fail individual calls: for forcing races and
 * errors at an exact point instead of hoping a timing window opens.
 *
 * <p>Calls made with a request builder lambda ({@code putObject(r -> r.bucket(...), body)}) are
 * turned into the request-object form first, so an interceptor always sees a request object as
 * the first argument.
 */
final class InterceptingS3Client {

  /** Decides what happens to one call. */
  @FunctionalInterface
  interface Interceptor {
    /**
     * Handles a call.
     *
     * @param method  the S3Client method, in its request-object form.
     * @param args    its arguments; {@code args[0]} is the request.
     * @param proceed makes the real call with these arguments.
     * @return the call's result.
     * @throws Throwable to fail the call.
     */
    Object intercept(Method method, Object[] args, Call proceed) throws Throwable;
  }

  /** The real call. */
  @FunctionalInterface
  interface Call {
    /**
     * Makes the call.
     *
     * @param args the arguments to use.
     * @return the result.
     * @throws Throwable whatever the real client throws.
     */
    Object with(Object[] args) throws Throwable;
  }

  private InterceptingS3Client() {
  }

  static S3Client wrap(final S3Client delegate, final Interceptor interceptor) {
    return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class},
      (proxy, method, args) -> {
        if (method.getDeclaringClass() == Object.class) {
          return method.invoke(delegate, args);
        }
        final Object[] actualArgs = args == null ? new Object[0] : args;
        final Method target = requestForm(method, actualArgs);
        final Object[] targetArgs = target == method ? actualArgs : withRequest(method, target, actualArgs);
        return interceptor.intercept(target, targetArgs, callArgs -> {
          try {
            return target.invoke(delegate, callArgs);
          } catch (final InvocationTargetException e) {
            throw e.getCause();
          }
        });
      });
  }

  /** Whether a call is the given S3 operation on the given key. */
  static boolean is(final Method method, final Object[] args, final String operation, final String key) {
    if (!method.getName().equals(operation) || args.length == 0 || !(args[0] instanceof SdkRequest request)) {
      return false;
    }
    return key == null || request.getValueForField("Key", String.class).map(key::equals).orElse(false);
  }

  /** An S3 error as the service would return it. */
  static S3Exception error(final int status, final String code) {
    return (S3Exception) S3Exception.builder()
      .statusCode(status)
      .message(code)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build())
      .build();
  }

  private static Method requestForm(final Method method, final Object[] args) {
    if (args.length == 0 || !(args[0] instanceof Consumer)) {
      return method;
    }
    return Arrays.stream(S3Client.class.getMethods())
      .filter(m -> m.getName().equals(method.getName()) && m.getParameterCount() == method.getParameterCount())
      .filter(m -> SdkRequest.class.isAssignableFrom(m.getParameterTypes()[0]))
      .filter(m -> Arrays.equals(Arrays.copyOfRange(m.getParameterTypes(), 1, m.getParameterCount()),
        Arrays.copyOfRange(method.getParameterTypes(), 1, method.getParameterCount())))
      .findFirst()
      .orElse(method);
  }

  @SuppressWarnings("unchecked")
  private static Object[] withRequest(final Method lambdaForm, final Method requestForm, final Object[] args)
    throws ReflectiveOperationException {
    final Class<?> requestType = requestForm.getParameterTypes()[0];
    final Method builderFactory = requestType.getMethod("builder");
    final Object builder = builderFactory.invoke(null);
    ((Consumer<Object>) args[0]).accept(builder);
    final Object request = builderFactory.getReturnType().getMethod("build").invoke(builder);
    final Object[] result = args.clone();
    result[0] = request;
    return result;
  }
}
