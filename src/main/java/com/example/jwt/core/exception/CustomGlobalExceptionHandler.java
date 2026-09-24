package com.example.jwt.core.exception;

import com.example.jwt.domain.module.ModuleNotFoundException;
import com.example.jwt.domain.module.ModuleServiceUnavailableException;
import java.time.LocalDate;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class CustomGlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(CustomGlobalExceptionHandler.class);

  // Seconds a client should wait before trying again after a 503; matches the circuit
  // breaker's default open-state wait (module-service.circuit-breaker.wait-duration-in-open-state).
  private static final String RETRY_AFTER_SECONDS = "15";

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ResponseError handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
    return new ResponseError()
        .setTimeStamp(LocalDate.now())
        .setErrors(ex.getBindingResult().getFieldErrors().stream().collect(
            Collectors.toMap(error -> error.getField(), error -> error.getDefaultMessage())))
        .build();
  }

  // e.g. a path variable that is not a UUID. Handled here so the client gets the 400 directly:
  // Spring's fallback would forward to /error, which answers with the security chain's status.
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ResponseError handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return error(ex.getName(), String.format("'%s' is not a valid value", ex.getValue()));
  }

  // Thrown by ExtendedServiceImpl for an unknown id. Without this handler it surfaced as a 500
  // in the metrics and, after the /error forward, as a misleading 403 to the client.
  @ExceptionHandler(NoSuchElementException.class)
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  public ResponseError handleNoSuchElement(NoSuchElementException ex) {
    return error("entity", ex.getMessage());
  }

  @ExceptionHandler(ModuleNotFoundException.class)
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  public ResponseError handleModuleNotFound(ModuleNotFoundException ex) {
    return error("module", ex.getMessage());
  }

  @ExceptionHandler(ModuleServiceUnavailableException.class)
  public ResponseEntity<ResponseError> handleModuleServiceUnavailable(
      ModuleServiceUnavailableException ex) {
    log.warn(ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(error("moduleService", "The module service is temporarily unavailable"));
  }

  private static ResponseError error(String field, String message) {
    return new ResponseError()
        .setTimeStamp(LocalDate.now())
        .setErrors(Map.of(field, message))
        .build();
  }
}
