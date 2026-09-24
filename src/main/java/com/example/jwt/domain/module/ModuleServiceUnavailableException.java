package com.example.jwt.domain.module;

/**
 * The module_service could not be reached or failed: connection refused, timeout, 5xx after
 * all retries, or the circuit breaker is open. Temporary by nature, hence 503 to the client.
 */
public class ModuleServiceUnavailableException extends RuntimeException {

  public ModuleServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
