package com.example.jwt.domain.module;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection to the module_service, bound from {@code module-service.*}.
 *
 * <p>Worst case for one call with the defaults: 3 attempts x 2s read timeout + 0.2s + 0.4s
 * backoff = 6.6s before the client gets its 503. Once the circuit breaker has opened, calls
 * fail immediately instead, until the open-state wait has passed.
 */
@ConfigurationProperties("module-service")
public record ModuleServiceProperties(
    String baseUrl,
    @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("2s") Duration readTimeout,
    @DefaultValue Retry retry,
    @DefaultValue CircuitBreaker circuitBreaker) {

  /** Retries transient failures only: I/O errors, timeouts and 5xx answers. */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("200ms") Duration initialBackoff,
      @DefaultValue("2") double backoffMultiplier) {

  }

  /** Count-based window over the last calls; 4xx answers do not count as failures. */
  public record CircuitBreaker(
      @DefaultValue("10") int slidingWindowSize,
      @DefaultValue("5") int minimumNumberOfCalls,
      @DefaultValue("50") float failureRateThreshold,
      @DefaultValue("15s") Duration waitDurationInOpenState,
      @DefaultValue("2") int permittedCallsInHalfOpenState) {

  }
}
