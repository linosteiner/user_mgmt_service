package com.example.jwt.core.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Which requests count as which event, and from which source. */
class UserActivityMetricsFilterTest {

  private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0";
  private static final String K6 = "Grafana k6/1.3.0 (https://github.com/grafana/k6)";

  private SimpleMeterRegistry registry;
  private UserActivityMetricsFilter filter;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    filter = new UserActivityMetricsFilter(registry);
  }

  @Test
  void everySeriesExistsAtZeroBeforeTheFirstRequest() {
    assertThat(registry.find(UserActivityMetricsFilter.METRIC).counters())
        .hasSize(10)
        .allSatisfy(counter -> assertThat(counter.count()).isZero());
  }

  @Test
  void browserLoginCountsAsUser() throws Exception {
    run("POST", "/users/login", BROWSER, respondWith(200));

    assertThat(count("login", "user")).isEqualTo(1);
    assertThat(count("login", "loadtest")).isZero();
  }

  @Test
  void k6CountsAsLoadTest() throws Exception {
    run("POST", "/users/login", K6, respondWith(401));

    assertThat(count("login_failed", "loadtest")).isEqualTo(1);
    assertThat(count("login_failed", "user")).isZero();
  }

  @Test
  void registrationAndModuleAssignment() throws Exception {
    run("POST", "/users/register", BROWSER, respondWith(201));
    run("PUT", "/users/0b0c/modules/4f2a", BROWSER, respondWith(200));

    assertThat(count("register", "user")).isEqualTo(1);
    assertThat(count("module_assign", "user")).isEqualTo(1);
  }

  @Test
  void escapingExceptionCountsAsServerError() {
    FilterChain failing = (req, res) -> {
      throw new IllegalStateException("boom");
    };

    assertThatThrownBy(() -> run("GET", "/users/me", BROWSER, failing))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("server_error", "user")).isEqualTo(1);
  }

  @Test
  void otherRequestsAreNotCounted() throws Exception {
    run("GET", "/users/me", BROWSER, respondWith(200));
    run("PUT", "/users/0b0c/modules/4f2a", BROWSER, respondWith(404));

    assertThat(registry.find(UserActivityMetricsFilter.METRIC).counters())
        .allSatisfy(counter -> assertThat(counter.count()).isZero());
  }

  private void run(String method, String path, String userAgent, FilterChain chain)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + path);
    request.setContextPath("/api");
    request.setServletPath(path);
    request.addHeader("User-Agent", userAgent);
    filter.doFilter(request, new MockHttpServletResponse(), chain);
  }

  private static FilterChain respondWith(int status) {
    return (req, res) -> ((HttpServletResponse) res).setStatus(status);
  }

  private double count(String event, String source) {
    Counter counter = registry.get(UserActivityMetricsFilter.METRIC)
        .tag("event", event).tag("source", source).counter();
    return counter.count();
  }
}
