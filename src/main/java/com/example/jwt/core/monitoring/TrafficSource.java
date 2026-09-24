package com.example.jwt.core.monitoring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/**
 * Tells load-test traffic apart from real users, so the Grafana dashboards can show the two
 * separately. k6 sends "Grafana k6/&lt;version&gt;" as its User-Agent unless a script overrides
 * it; the X-Load-Test header marks any other load generator.
 */
public final class TrafficSource {

  public static final String USER = "user";
  public static final String LOAD_TEST = "loadtest";
  public static final String LOAD_TEST_HEADER = "X-Load-Test";

  private TrafficSource() {
  }

  public static String of(HttpServletRequest request) {
    if (request.getHeader(LOAD_TEST_HEADER) != null) {
      return LOAD_TEST;
    }
    String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
    // "k6/" and not just "k6": a browser's device model may contain the two characters.
    return userAgent != null && userAgent.toLowerCase(Locale.ROOT).contains("k6/")
        ? LOAD_TEST
        : USER;
  }
}
