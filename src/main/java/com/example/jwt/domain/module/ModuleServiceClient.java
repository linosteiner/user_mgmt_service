package com.example.jwt.domain.module;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Synchronous REST client for the module_service, reached through its Kubernetes Service.
 *
 * <p>Every call runs as Retry(CircuitBreaker(http call)): each attempt is one call in the
 * circuit breaker's window, and an open circuit fails fast without a network round trip. All
 * operations are safe to repeat -- GETs, and a PUT that the module_service handles
 * idempotently.
 */
@Component
public class ModuleServiceClient {

  private static final ParameterizedTypeReference<List<ModuleDTO>> MODULE_LIST =
      new ParameterizedTypeReference<>() {
      };

  private final RestClient restClient;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;

  public ModuleServiceClient(RestClient moduleServiceRestClient,
      CircuitBreaker moduleServiceCircuitBreaker, Retry moduleServiceRetry) {
    this.restClient = moduleServiceRestClient;
    this.circuitBreaker = moduleServiceCircuitBreaker;
    this.retry = moduleServiceRetry;
  }

  /** The availability check: returns the module, or throws ModuleNotFoundException. */
  public ModuleDTO findModule(UUID moduleId) {
    return call(moduleId, () -> restClient.get()
        .uri("/api/v1/modules/{moduleId}", moduleId)
        .retrieve()
        .body(ModuleDTO.class));
  }

  /** Every module the module_service offers: the list a client picks an assignment from. */
  public List<ModuleDTO> findModules() {
    return call(null, () -> restClient.get()
        .uri("/api/v1/modules")
        .retrieve()
        .body(MODULE_LIST));
  }

  public void assignModule(UUID userId, UUID moduleId) {
    call(moduleId, () -> restClient.put()
        .uri("/api/v1/users/{userId}/modules/{moduleId}", userId, moduleId)
        .retrieve()
        .toBodilessEntity());
  }

  public List<ModuleDTO> findModulesOfUser(UUID userId) {
    return call(null, () -> restClient.get()
        .uri("/api/v1/users/{userId}/modules", userId)
        .retrieve()
        .body(MODULE_LIST));
  }

  private <T> T call(UUID moduleId, Supplier<T> request) {
    Supplier<T> guarded = Retry.decorateSupplier(retry,
        CircuitBreaker.decorateSupplier(circuitBreaker, request));
    try {
      return guarded.get();
    } catch (HttpClientErrorException.NotFound e) {
      throw new ModuleNotFoundException(moduleId);
    } catch (CallNotPermittedException e) {
      throw new ModuleServiceUnavailableException("module_service circuit breaker is open", e);
    } catch (ResourceAccessException | HttpServerErrorException e) {
      throw new ModuleServiceUnavailableException(
          "module_service is not reachable: " + e.getMessage(), e);
    }
  }
}
