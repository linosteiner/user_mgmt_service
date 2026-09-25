Spring 4.1 compliant app that demonstrates the authentication and authorization of a user via JWT

Spring Boot 4 / Java 25. Deployed from the Helm chart in the Ops repository
[`bernetlennard/user_mgmt_ops`](https://github.com/bernetlennard/user_mgmt_ops) through ArgoCD;
the overview is its [docs/ueberblick.md](https://github.com/bernetlennard/user_mgmt_ops/blob/main/docs/ueberblick.md).

## API (under `/api`)

| Endpoint | Who |
|---|---|
| `POST /users/register`, `POST /users/login` (token in the `Authorization` response header) | anyone |
| `GET /users/me`, `GET /users`, `GET /users/{id}` | logged in |
| `PUT /users/{id}` / `DELETE /users/{id}` | `USER_MODIFY` (and over 18) / `USER_DELETE` |
| `GET /modules` — the modules that can be assigned | logged in |
| `GET /users/{id}/modules` — a user's modules | the user themself or `USER_MODIFY` |
| `PUT /users/{id}/modules/{moduleId}` — assign a module (idempotent) | the user themself or `USER_MODIFY` |

The module endpoints call the [module_service](https://github.com/linosteiner/module_service)
over REST (`MODULE_SERVICE_BASE_URL`) with timeout, retry and circuit breaker; the module data
lives in its MySQL, which this service cannot reach. Status codes: 404 unknown module or user,
400 malformed id, 403 not allowed, **503 + `Retry-After: 15`** while the module_service is down.
Details and evidence: [docs/module-service.md](https://github.com/bernetlennard/user_mgmt_ops/blob/main/docs/module-service.md).

## Metrics (`:8081/actuator/prometheus`)

The management port is not in any Ingress: only the kubelet (probes) and Prometheus reach it.

| Metric | Use |
|---|---|
| `http_server_requests_seconds_{count,sum,bucket}{uri, status, outcome, source}` | request rate, response time (avg / p95), error rate |
| `user_mgmt_activity_total{event, source}` | logins, failed logins, registrations, module assignments, 5xx; every series starts at 0 |
| `http_client_requests_seconds_*`, `resilience4j_circuitbreaker_state`, `resilience4j_retry_calls_total` | the calls to the module_service |

`source` is `loadtest` for k6 (recognised by its User-Agent) or any request with an `X-Load-Test`
header, otherwise `user` — so the dashboards can show real users apart from load tests. Every
metric carries `application="user-mgmt-service"`, which the PrometheusRule and dashboards in the
Ops repo filter on.

## Configuration

Environment variables (in the cluster from the chart's ConfigMap and Secrets):
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
`SPRING_JPA_HIBERNATE_DDL_AUTO`, `JWT_ISSUER`, `JWT_SECRET`, `MODULE_SERVICE_BASE_URL`,
`MANAGEMENT_SERVER_PORT` (default 8081). The timeouts, retry and circuit breaker settings are in
`src/main/resources/application.properties` (`module-service.*`).

## Build and test

```bash
gradle test                       # unit tests: module client (retry, breaker), metrics filter
docker build -t user_mgmt_service .
```

A push to `main` runs `.github/workflows/build-and-promote.yml`: test → build and push
`xxpirl2knc5/user_mgmt_service:<commit-sha>` → write the tag into the Ops repo, from where
ArgoCD rolls it out to staging and prod.

`k8s/` only holds what is applied by hand outside the chart (Traefik, ArgoCD settings);
`docker-compose.yaml` is the old single-host setup with a local PostgreSQL.
