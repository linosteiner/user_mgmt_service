package com.example.jwt.domain.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Retry, circuit breaker and error mapping of the module_service client against a mocked
 * module_service. Plain unit test -- no Spring context, no database.
 */
class ModuleServiceClientTest {

  private static final String BASE_URL = "http://module-service:8080";
  private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID MODULE_ID = UUID.fromString("c02f58f2-3aca-4f1e-8076-bacf6f1999e6");
  private static final String MODULE_URL = BASE_URL + "/api/v1/modules/" + MODULE_ID;
  private static final String MODULE_JSON = """
      {"id": "%s", "code": "CLOUD-ARCH", "name": "Cloud Architecture",
       "description": "Designing reliable and scalable cloud systems",
       "created_at": "2026-09-16T09:02:09", "updated_at": "2026-09-16T09:02:09"}
      """.formatted(MODULE_ID);

  private MockRestServiceServer server;
  private CircuitBreaker circuitBreaker;
  private ModuleServiceClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();

    // Production defaults, except for the waits: no need to sleep through real backoffs.
    ModuleServiceProperties.Retry retryProperties =
        new ModuleServiceProperties.Retry(3, Duration.ofMillis(1), 1);
    ModuleServiceProperties.CircuitBreaker breakerProperties =
        new ModuleServiceProperties.CircuitBreaker(10, 5, 50, Duration.ofSeconds(15), 2);
    circuitBreaker = CircuitBreaker.of("test",
        ModuleServiceConfig.circuitBreakerConfig(breakerProperties));
    Retry retry = Retry.of("test", ModuleServiceConfig.retryConfig(retryProperties));
    client = new ModuleServiceClient(builder.build(), circuitBreaker, retry);
  }

  @Test
  void findModuleReturnsTheModule() {
    server.expect(once(), requestTo(MODULE_URL)).andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(MODULE_JSON, MediaType.APPLICATION_JSON));

    ModuleDTO module = client.findModule(MODULE_ID);

    assertThat(module.id()).isEqualTo(MODULE_ID);
    assertThat(module.code()).isEqualTo("CLOUD-ARCH");
    server.verify();
  }

  @Test
  void unknownModuleIsNotFoundAndNotRetried() {
    server.expect(once(), requestTo(MODULE_URL)).andRespond(withResourceNotFound());

    assertThatThrownBy(() -> client.findModule(MODULE_ID))
        .isInstanceOf(ModuleNotFoundException.class);
    server.verify();
    // A 404 is an answer, not an outage: it must not count against the circuit breaker.
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void transientServerErrorsAreRetried() {
    server.expect(times(2), requestTo(MODULE_URL)).andRespond(withServiceUnavailable());
    server.expect(once(), requestTo(MODULE_URL))
        .andRespond(withSuccess(MODULE_JSON, MediaType.APPLICATION_JSON));

    assertThat(client.findModule(MODULE_ID).code()).isEqualTo("CLOUD-ARCH");
    server.verify();
  }

  @Test
  void timeoutsAreRetriedThenReportedAsUnavailable() {
    server.expect(times(3), requestTo(MODULE_URL))
        .andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertThatThrownBy(() -> client.findModule(MODULE_ID))
        .isInstanceOf(ModuleServiceUnavailableException.class);
    server.verify();
  }

  @Test
  void persistentServerErrorIsReportedAsUnavailableAfterThreeAttempts() {
    server.expect(times(3), requestTo(MODULE_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> client.findModule(MODULE_ID))
        .isInstanceOf(ModuleServiceUnavailableException.class);
    server.verify();
  }

  @Test
  void openCircuitFailsFastWithoutCallingTheModuleService() {
    // The first client call fails 3 attempts, the second one 2 more: at 5 failed calls (the
    // minimum, 100% failure rate) the circuit opens, so the second call's third attempt is
    // already rejected without reaching the module_service -- 5 requests in total, not 6.
    server.expect(times(5), requestTo(MODULE_URL))
        .andRespond(withException(new IOException("Connection refused")));
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> client.findModule(MODULE_ID))
          .isInstanceOf(ModuleServiceUnavailableException.class);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // No further request is expected by the mock server: a call now must not reach it.
    assertThatThrownBy(() -> client.findModule(MODULE_ID))
        .isInstanceOf(ModuleServiceUnavailableException.class)
        .hasMessageContaining("circuit breaker is open");
    server.verify();
  }

  @Test
  void assignModuleCallsTheIdempotentPut() {
    server.expect(once(), requestTo(BASE_URL + "/api/v1/users/" + USER_ID + "/modules/" + MODULE_ID))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    client.assignModule(USER_ID, MODULE_ID);
    server.verify();
  }

  @Test
  void findModulesReadsTheWholeList() {
    server.expect(once(), requestTo(BASE_URL + "/api/v1/modules"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[" + MODULE_JSON + "]", MediaType.APPLICATION_JSON));

    assertThat(client.findModules()).extracting(ModuleDTO::code).containsExactly("CLOUD-ARCH");
    server.verify();
  }

  @Test
  void findModulesIsGuardedLikeEveryOtherCall() {
    server.expect(times(3), requestTo(BASE_URL + "/api/v1/modules"))
        .andRespond(withServiceUnavailable());

    assertThatThrownBy(() -> client.findModules())
        .isInstanceOf(ModuleServiceUnavailableException.class);
    server.verify();
  }

  @Test
  void findModulesOfUserReadsTheAssignments() {
    server.expect(once(), requestTo(BASE_URL + "/api/v1/users/" + USER_ID + "/modules"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[" + MODULE_JSON + "]", MediaType.APPLICATION_JSON));

    assertThat(client.findModulesOfUser(USER_ID)).extracting(ModuleDTO::code)
        .containsExactly("CLOUD-ARCH");
    server.verify();
  }
}
