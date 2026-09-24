package com.example.jwt.domain.module;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Wires the REST client for the module_service: timeouts on the HTTP client, Resilience4j's
 * retry and circuit breaker around every call, and both published as Prometheus metrics
 * (resilience4j_circuitbreaker_state, resilience4j_retry_calls_total, ...).
 *
 * <p>Resilience4j is used as a plain library rather than through its Spring Boot starter: the
 * starter's auto-configuration targets Spring Boot 3, this service runs on Spring Boot 4.
 */
@Configuration
@EnableConfigurationProperties(ModuleServiceProperties.class)
public class ModuleServiceConfig {

  static final String NAME = "moduleService";

  private static final Logger log = LoggerFactory.getLogger(ModuleServiceConfig.class);

  /**
   * Built from Spring Boot's RestClient.Builder so the calls are observed: they show up as
   * http_client_requests_seconds with the target host and status in Prometheus.
   */
  @Bean
  RestClient moduleServiceRestClient(RestClient.Builder builder,
      ModuleServiceProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return builder
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  @Bean
  CircuitBreaker moduleServiceCircuitBreaker(ModuleServiceProperties properties,
      MeterRegistry meterRegistry) {
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
        circuitBreakerConfig(properties.circuitBreaker()));
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    CircuitBreaker circuitBreaker = registry.circuitBreaker(NAME);
    circuitBreaker.getEventPublisher().onStateTransition(event ->
        log.warn("module_service circuit breaker: {}", event.getStateTransition()));
    return circuitBreaker;
  }

  @Bean
  Retry moduleServiceRetry(ModuleServiceProperties properties, MeterRegistry meterRegistry) {
    RetryRegistry registry = RetryRegistry.of(retryConfig(properties.retry()));
    TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
    Retry retry = registry.retry(NAME);
    retry.getEventPublisher().onRetry(event ->
        log.info("module_service call failed, retry {} after {}: {}",
            event.getNumberOfRetryAttempts(), event.getWaitInterval(),
            String.valueOf(event.getLastThrowable())));
    return retry;
  }

  static CircuitBreakerConfig circuitBreakerConfig(ModuleServiceProperties.CircuitBreaker cb) {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(cb.slidingWindowSize())
        .minimumNumberOfCalls(cb.minimumNumberOfCalls())
        .failureRateThreshold(cb.failureRateThreshold())
        .waitDurationInOpenState(cb.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpenState())
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        // Only an unreachable or failing module_service opens the circuit. A 404 is a valid
        // answer ("no such module") and must not count against it.
        .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
        .ignoreExceptions(HttpClientErrorException.class)
        .build();
  }

  static RetryConfig retryConfig(ModuleServiceProperties.Retry retry) {
    return RetryConfig.custom()
        .maxAttempts(retry.maxAttempts())
        .intervalFunction(IntervalFunction.ofExponentialBackoff(
            retry.initialBackoff(), retry.backoffMultiplier()))
        // Transient failures only. Not a 4xx (repeating it gives the same answer), and not
        // CallNotPermittedException (the circuit is open, retrying would just hammer it).
        .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
        .build();
  }
}
