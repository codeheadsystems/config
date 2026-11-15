package com.codeheadsystems.config.server.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.server.RateLimitConfig;
import com.codeheadsystems.config.server.api.ApiException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private final AtomicLong now = new AtomicLong(1_000);
  private final RateLimiter limiter;

  RateLimiterTest() {
    final RateLimitConfig config = new RateLimitConfig();
    config.setBurst(3);
    config.setRequestsPerMinute(6); // one every 10 seconds
    limiter = new RateLimiter(config, now::get);
  }

  @Test
  void allowsTheBurstThen429WithRetryAfter() {
    for (int i = 0; i < 3; i++) {
      limiter.acquire("token-a");
    }

    assertThatThrownBy(() -> limiter.acquire("token-a")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(429);
      assertThat(e.headers()).containsEntry("Retry-After", "10");
    });
  }

  @Test
  void refillsAtTheConfiguredRate() {
    for (int i = 0; i < 3; i++) {
      limiter.acquire("token-a");
    }
    now.addAndGet(TimeUnit.SECONDS.toNanos(9));
    assertThatThrownBy(() -> limiter.acquire("token-a")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.headers()).containsEntry("Retry-After", "1"));

    now.addAndGet(TimeUnit.SECONDS.toNanos(1));
    assertThatCode(() -> limiter.acquire("token-a")).doesNotThrowAnyException();
  }

  @Test
  void eachTokenHasItsOwnBucket() {
    for (int i = 0; i < 3; i++) {
      limiter.acquire("token-a");
    }

    assertThatCode(() -> limiter.acquire("token-b")).doesNotThrowAnyException();
  }

  @Test
  void aLongIdleDoesNotGrowTheBucketPastTheBurst() {
    limiter.acquire("token-a");
    now.addAndGet(TimeUnit.HOURS.toNanos(5));
    for (int i = 0; i < 3; i++) {
      limiter.acquire("token-a");
    }

    assertThatThrownBy(() -> limiter.acquire("token-a")).isInstanceOf(ApiException.class);
  }
}
