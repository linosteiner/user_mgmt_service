package com.example.jwt.core.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts what users do -- log in, register, assign a module -- and server errors, split into
 * real users and load tests (user_mgmt_activity_total{event, source}). The "Ruhiger Betrieb"
 * dashboard reads these rather than http_server_requests for two reasons:
 *
 * <ul>
 *   <li>Every event/source combination is registered at 0 on startup. A series that only
 *   appears with its first request is invisible to increase() and rate(), which need a value
 *   before the increment -- so on a quiet system the first registration after a deploy simply
 *   did not show up.</li>
 *   <li>The label says what happened ("login_failed") instead of a URI and a status code.</li>
 * </ul>
 *
 * Ordered ahead of the Spring Security filter chain because the login is answered inside that
 * chain (CustomAuthenticationFilter) and never reaches a controller.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
public class UserActivityMetricsFilter extends OncePerRequestFilter {

  static final String METRIC = "user_mgmt.activity";

  enum Event {
    LOGIN, LOGIN_FAILED, REGISTER, MODULE_ASSIGN, SERVER_ERROR;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private static final Pattern MODULE_ASSIGN_PATH =
      Pattern.compile("/users/[^/]+/modules/[^/]+/?");

  private final Map<String, Counter> counters = new HashMap<>();

  public UserActivityMetricsFilter(MeterRegistry registry) {
    for (Event event : Event.values()) {
      for (String source : List.of(TrafficSource.USER, TrafficSource.LOAD_TEST)) {
        counters.put(key(event, source), Counter.builder(METRIC)
            .description("User actions and server errors, by traffic source")
            .tag("event", event.tag())
            .tag("source", source)
            .register(registry));
      }
    }
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      // An exception escaping the chain becomes a 500 only later, in the container's error
      // dispatch, so the response does not carry that status yet.
      int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      Event event = classify(request.getMethod(), request.getServletPath(), status);
      if (event != null) {
        counters.get(key(event, TrafficSource.of(request))).increment();
      }
    }
  }

  static Event classify(String method, String path, int status) {
    if (status >= 500) {
      return Event.SERVER_ERROR;
    }
    if ("POST".equals(method) && "/users/login".equals(path)) {
      return switch (status) {
        case 200 -> Event.LOGIN;
        case 401 -> Event.LOGIN_FAILED;
        default -> null;
      };
    }
    if ("POST".equals(method) && "/users/register".equals(path) && status == 201) {
      return Event.REGISTER;
    }
    if ("PUT".equals(method) && MODULE_ASSIGN_PATH.matcher(path).matches() && status == 200) {
      return Event.MODULE_ASSIGN;
    }
    return null;
  }

  private static String key(Event event, String source) {
    return event.tag() + "/" + source;
  }
}
