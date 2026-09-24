package com.example.jwt.core.monitoring;

import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Adds a `source` label (user | loadtest) to http_server_requests. Spring Boot's WebMvc
 * observation auto-configuration uses this bean in place of the default convention, so every
 * other label stays as it was and the existing dashboard and alert queries are unaffected.
 */
@Component
public class TrafficSourceObservationConvention extends DefaultServerRequestObservationConvention {

  @Override
  public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
    return super.getLowCardinalityKeyValues(context)
        .and("source", TrafficSource.of(context.getCarrier()));
  }
}
