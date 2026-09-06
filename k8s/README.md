# Kubernetes setup

Plain YAML manifests for the DigitalOcean Kubernetes cluster behind
**https://vcs.lennardbernet.ch** and **https://vcs.linosteiner.ch** (both DNS names point
at the same LoadBalancer IP).

**Only `traefik/` and `argocd/` are applied from this directory.** The application itself
— `postgres/`, `user-mgmt/`, `auth-portal/`, `ingress/` — runs from the Helm chart
`charts/user-mgmt` in the Ops repository
[`bernetlennard/user_mgmt_ops`](https://github.com/bernetlennard/user_mgmt_ops), installed
into the `prod` and `staging` namespaces and reconciled by ArgoCD. Those four directories
are kept here as documentation of what the objects are; the chart only changes the
packaging. Moving Traefik into a `charts/platform` is the remaining migration step, and
the one that can cost the public IP and the TLS certificate.

> **This is a learning cluster.** Secrets are committed in plain text and there is one
> replica of everything. See [Known simplifications](#known-simplifications).

---

## What runs here

| Component | Image | Port | Role |
|---|---|---|---|
| `traefik` | `traefik:v3.7` | 80, 443, 8080 | Ingress controller. The only pod reachable from the internet. Terminates TLS and obtains Let's Encrypt certificates. |
| `argocd` | upstream install manifest | — | Reconciles the Ops repo into the cluster. Reachable at https://argo.linosteiner.ch. |
| `auth-portal` | `bernetlennard/auth_portal:latest` | 3000 | Next.js frontend. Serves everything except `/api`. |
| `user-mgmt-service` | `xxpirl2knc5/user_mgmt_service` | 8080 | Spring Boot REST API (JWT auth). Serves `/api`. |
| `postgres` | `postgres:16-alpine` | 5432 | Database. Internal only. |

```
k8s/
├── traefik/                   <- applied from here
│   ├── traefik-rbac.yaml         ServiceAccount + ClusterRole + ClusterRoleBinding
│   ├── traefik-ingressclass.yaml the name our Ingress points at
│   ├── traefik-config.yaml       static Traefik config, as a ConfigMap
│   ├── traefik-pvc.yaml          disk for acme.json (the TLS certificate)
│   ├── traefik-deployment.yaml
│   ├── traefik-service.yaml      type: LoadBalancer -> the public IP
│   └── traefik-dashboard-service.yaml  ClusterIP, port-forward only
├── argocd/                    <- applied from here, on top of the upstream install
│   ├── argocd-cmd-params-cm.yaml server flags (server.insecure)
│   ├── argocd-cm.yaml            local accounts
│   ├── argocd-rbac-cm.yaml       roles for those accounts
│   └── argocd-server-ingress.yaml
├── postgres/                  <- superseded by charts/user-mgmt
├── user-mgmt/                 <- superseded
├── auth-portal/               <- superseded
└── ingress/                   <- superseded
```

---

## How a request flows

```
  browser
     │  https://vcs.lennardbernet.ch/api/users
     ▼
  DNS A-record  ──────────────►  DigitalOcean LoadBalancer (public IP)
                                          │  created by Service/traefik (type: LoadBalancer)
                                          ▼
                                  Service/traefik  :80 :443
                                          │  selector app=traefik
                                          ▼
                                    Traefik pod
                                          │  reads the Ingress via the k8s API
                                          │  terminates TLS here
                        ┌─────────────────┴──────────────────┐
                path: /api                              path: /
                        ▼                                    ▼
        Service/user-mgmt-service :8080         Service/auth-portal :3000
                        │                                     │
                        ▼                                     ▼
              Spring Boot pod :8080                    Next.js pod :3000
                        │
                        │  jdbc:postgresql://postgres:5432/user_mgmt_db
                        ▼
                Service/postgres :5432
                        │
                        ▼
                  Postgres pod  ──►  postgres-pvc (block storage)
```

Two things about the routing are worth internalising:

**Longest prefix wins.** The Ingress has `/api` and `/` as `pathType: Prefix` rules.
A request to `/api/users` matches both, and the more specific one takes it. The order
of the rules in the YAML is irrelevant.

**No path rewriting happens.** The backend receives `/api/users`, not `/users`, because
the Spring app mounts itself under `/api` via `SERVER_SERVLET_CONTEXT_PATH`. This is the
one place the cluster differs from `docker-compose.yaml` at the repo root, which uses a
Traefik `stripprefix` middleware — in Kubernetes that would need a Traefik `Middleware`
CRD, and pushing the config into the app avoids the extra object.

---

## The object types, in this stack

**Deployment** — "keep N pods of this image running". It creates a ReplicaSet, which
creates the Pods, and replaces them on config change or crash. Every Deployment here uses
`replicas: 1` and `strategy: Recreate` (stop the old pod, then start the new one), because
the node is too small to hold two copies at once and ReadWriteOnce volumes cannot be
mounted twice.

**Service** — a stable name and virtual IP in front of a set of pods. The link is by
**label selector, not by name**: `Service/postgres` finds pods labelled `app: postgres` and
knows nothing about `Deployment/postgres`. Cluster DNS resolves the Service name, which is
why the backend can say `jdbc:postgresql://postgres:5432/…`. Two types appear here:
`ClusterIP` (internal, the default) and `LoadBalancer` (a real DigitalOcean load balancer
with a public IP).

**Ingress** — an HTTP routing table mapping host + path onto Services. It is only data; it
does nothing until an ingress controller reads it. That is Traefik's job here.

**IngressClass** — the name that connects an Ingress to a controller.
→ `traefik/traefik-ingressclass.yaml`

**ConfigMap** — non-secret key/value config, injected as environment variables or mounted
as files, where each key becomes a filename. `traefik-config.yaml` uses the second form:
the key `traefik.yaml` becomes `/etc/traefik/traefik.yaml`.

**Secret** — the same thing, flagged as sensitive. Kubernetes hides it from
`kubectl describe`, can encrypt it at rest, and mounts it as tmpfs. It is only
**base64-encoded, not encrypted** — the files here use `stringData`, so the plaintext is
visible directly.

**PersistentVolumeClaim** — a request for disk that outlives the pod. `ReadWriteOnce` means
one node can mount it at a time, which is what forces `strategy: Recreate`.
→ `postgres/postgres-pvc.yaml` (the database), `traefik/traefik-pvc.yaml` (the TLS cert)

**ServiceAccount / ClusterRole / ClusterRoleBinding** — identity and permissions for a pod
that talks to the Kubernetes API. Traefik needs this because it *watches* Ingress objects
rather than reading a static config file.
→ `traefik/traefik-rbac.yaml`

### Two things there is no object for

- **Namespace** — what is applied from here lands in `default`. The one hardcoded
  occurrence is the `ClusterRoleBinding` subject in `traefik-rbac.yaml`.
- **The TLS certificate** — the Ingress has no `spec.tls.secretName`: Traefik obtains and
  stores the certificate itself in `acme.json` on its PVC. `kubectl get secret` will never
  show it.

---

## How TLS works

1. The Ingress annotation `router.tls.certresolver: letsencrypt` names a resolver defined
   in `traefik-config.yaml`.
2. On startup, if `acme.json` has no valid certificate for the host, Traefik asks Let's
   Encrypt for one using the **HTTP-01 challenge**.
3. Let's Encrypt calls back to `http://<host>/.well-known/acme-challenge/<token>`. This is
   why port 80 must stay publicly reachable even though everything else is redirected to
   HTTPS — Traefik answers the challenge path *before* applying the redirect.
4. The certificate is written to `/letsencrypt/acme.json` on `traefik-acme-pvc`, and
   renewed automatically.

Consequences worth knowing:

- **Delete `traefik-acme-pvc` and the certificate is re-issued.** Let's Encrypt allows
  5 duplicate certificates per week.
- **DNS must be correct before the first request**, or step 3 fails. See below.
- Traefik does not automatically retry a failed ACME request
  ([traefik#9405](https://github.com/traefik/traefik/issues/9405)), which is why
  `traefik-service.yaml` lowers the DigitalOcean health-check threshold.

---

## Configuration reference

Where each key ends up. Spring Boot's **relaxed binding** maps a `SCREAMING_SNAKE_CASE`
environment variable onto a dotted property automatically, which is why several of these
work without appearing in `src/main/resources/application.properties`.

| Key | Source | Lands on |
|---|---|---|
| `POSTGRES_DB` | `postgres-config` | Postgres: creates the DB. Backend: interpolated into `SPRING_DATASOURCE_URL`. |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres-secret` | Postgres superuser; injected into the backend as `SPRING_DATASOURCE_USERNAME` / `_PASSWORD`. |
| `SPRING_DATASOURCE_URL` | literal in the Deployment | `spring.datasource.url` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `user-mgmt-config` | `spring.jpa.hibernate.ddl-auto`. Set to `update` (alter schema, keep data). |
| `JWT_ISSUER` | `user-mgmt-config` | `jwt.issuer` |
| `JWT_EXPIRATION_MILLIS` | `user-mgmt-config` | `jwt.expirationMillis`, bound onto `JwtProperties` purely by relaxed binding. Easy to mistake for dead config, because `application.properties` never mentions it. |
| `JWT_SECRET` | `user-mgmt-secret` | `jwt.secret`. The HMAC signing key. |
| `SERVER_SERVLET_CONTEXT_PATH` | `user-mgmt-config` | `server.servlet.context-path` = `/api`. What makes the Ingress work without stripPrefix. |
| `JAVA_TOOL_OPTIONS` | `user-mgmt-config` | `-XX:MaxRAMPercentage=60` — sizes the heap against the container limit, not the node's RAM. |
| `PORT`, `NODE_ENV` | `auth-portal-config` | Next.js runtime settings. |

`NEXT_PUBLIC_API_URL` is **not** configurable here. Next.js inlines `NEXT_PUBLIC_*` into
the client bundle at build time, so `https://vcs.lennardbernet.ch/api` is baked into the
image. Changing the domain means rebuilding the frontend.

### Health probes

| Pod | Probe | Why |
|---|---|---|
| `postgres` | `exec` `pg_isready` | Verifies the server accepts connections. |
| `traefik` | `tcpSocket :80` | Ready as soon as it is listening. |
| `user-mgmt-service` | `tcpSocket :8080` | Only checks that Tomcat bound its port. The app exposes real Actuator probes on `:8081`, which the Helm chart uses and this superseded manifest does not. |
| `auth-portal` | `httpGet /` | The frontend serves `/` unauthenticated. 2xx and 3xx both count as a pass. |

**Readiness** probes are what stop `strategy: Recreate` from serving 502s: until a probe
passes, the pod is not an endpoint of its Service and Traefik will not route to it.
**Liveness** probes restart a hung container, and have deliberately long
`initialDelaySeconds` so a slow JVM start can never trigger a restart loop.

---

## Deploy

```bash
# Point kubeconfig at the current cluster. After a cluster rebuild the old config
# points at a dead endpoint ("no such host").
doctl kubernetes cluster list
doctl kubernetes cluster kubeconfig save <cluster-id>

kubectl get nodes          # must report Ready

kubectl apply -f k8s/traefik/
kubectl get svc traefik -w      # wait for EXTERNAL-IP, then Ctrl-C

kubectl apply -f k8s/argocd/
kubectl rollout restart deployment/argocd-server -n argocd
```

The application follows from the Ops repo via ArgoCD; nothing else is applied by hand.

### DNS — and the AAAA trap

Point an **A-record** at the LoadBalancer IP:

```bash
kubectl get svc traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

> **This is the single most common way to break this deployment. Read it before
> blaming Traefik.**
>
> There must be **no AAAA record** on the hostname.
>
> Hostpoint serves a default wildcard `*.lennardbernet.ch AAAA` pointing at its own
> web server, and it matches wildcards **per record type**: an explicit A-record
> overrides the wildcard A, but leaves the wildcard AAAA in place. The result is IPv4
> resolving to the cluster and IPv6 resolving to Hostpoint. Both browsers and Let's
> Encrypt prefer IPv6, so the ACME challenge hits Hostpoint and fails with a 404 —
> while `curl -4` looks perfectly fine.
>
> The DigitalOcean load balancer is a `REGIONAL_NETWORK` type and has no IPv6 address
> at all, so there is no correct AAAA value to set. **Delete the wildcard AAAA.** The
> wildcard A can stay; it is overridden cleanly.

```bash
nslookup -type=A    vcs.lennardbernet.ch 8.8.8.8   # -> the EXTERNAL-IP
nslookup -type=AAAA vcs.lennardbernet.ch 8.8.8.8   # -> must be EMPTY
```

### Verify

```bash
kubectl get pods,svc,ingress -A

curl -4 -I http://vcs.lennardbernet.ch               # expect 308 -> https
curl -4 -I https://vcs.lennardbernet.ch              # expect 307 -> /dashboard
curl -4 -sk https://vcs.lennardbernet.ch/api/users   # 403 = backend answered. 404 or 502 = routing broken.

# The issuer must be "Let's Encrypt", not "STAGING" or "TRAEFIK DEFAULT CERT".
echo | openssl s_client -connect vcs.lennardbernet.ch:443 -servername vcs.lennardbernet.ch 2>/dev/null \
  | openssl x509 -noout -issuer -dates
```

A 403 from `/api/users` is the *success* case: the backend received the request and
rejected it for lack of a JWT. A 404 means Traefik routed it to the frontend instead.

---

## Debug

```bash
kubectl logs deployment/traefik | grep -i acme      # certificate issuance
kubectl logs deployment/user-mgmt-service -n prod

# On CrashLoopBackOff, the interesting logs belong to the PREVIOUS container:
kubectl logs -l app=postgres -n prod --previous --tail=100

# Why is a pod not Ready? Probe failures and image pull errors show up here:
kubectl describe pod -l app=user-mgmt-service -n prod

# Node capacity. A pod stuck in Pending is usually this.
kubectl describe node | sed -n '/Allocated resources/,/Events/p'

# Which routers did Traefik actually build from the Ingress?
kubectl port-forward svc/traefik-dashboard 8080:8080
# -> http://localhost:8080/dashboard/

# Does a hostname really answer from where you think? Bypasses DNS entirely —
# useful for confirming an A/AAAA problem.
curl -sS -o /dev/null -w "%{http_code}\n" \
  -H "Host: vcs.lennardbernet.ch" \
  "http://$(kubectl get svc traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}')/"
```

Validate the manifests without a cluster:

```bash
kubectl apply --dry-run=client -f k8s/traefik/ -f k8s/argocd/
```

---

## Teardown

```bash
kubectl delete -f k8s/traefik/

# The DigitalOcean load balancer is billed separately and is NOT always removed with
# the Service. Always check:
doctl compute load-balancer list
```

This deletes `traefik-acme-pvc`, so **the TLS certificate is gone** and will be re-issued
on the next start. Keep the Let's Encrypt rate limit in mind before doing it repeatedly.

---

## Known simplifications

Things that are deliberately wrong-but-simple here, and what the real answer is:

- **Secrets are committed in plain text.** `postgres-secret.yaml` (admin/admin) and
  `user-mgmt-secret.yaml` (the JWT signing key, shared with the local `.env`).
  Real options: Sealed Secrets, External Secrets Operator, or
  `kubectl create secret generic` and never committing them.
- **Postgres is a Deployment, not a StatefulSet.** With one replica the behaviour is
  identical; a StatefulSet is what you would use for stable pod identity and
  per-replica volumes.
- **`replicas: 1` and `strategy: Recreate` everywhere**, so every deploy has a short
  outage. Driven by node size, not by preference.
- **The frontend image uses the `:latest` tag**, so there is no way to roll back to a
  known version; `imagePullPolicy: Always` at least guarantees the newest one is pulled.
  The backend is tagged by commit SHA and promoted into the chart by
  `.github/workflows/build-and-promote.yml`.
- **The hostname is hardcoded** in `app-ingress.yaml` and baked into the frontend image at
  build time.
- **DigitalOcean-specific bits leak into generic manifests** — the load-balancer
  health-check annotation in `traefik-service.yaml`.
- **The Traefik dashboard runs with no authentication** (`api.insecure: true`). Only safe
  because port 8080 is never exposed through the LoadBalancer.
- **`docker-compose.yaml` at the repo root is a separate deployment path** targeting plain
  droplets, and it behaves differently (stripPrefix instead of a context path,
  `create-drop` instead of `update`).
- **App-side, outside this directory:** `src/main/resources/application.properties`
  hardcodes `logging.level.root=DEBUG` and `spring.jpa.show-sql=true` for every
  environment. Noisy and leaky in a public deployment; worth making env-driven.
