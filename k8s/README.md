# Kubernetes setup

> **Superseded (Aufgabe 2).** The application is no longer deployed from this directory.
> `postgres/`, `user-mgmt/`, `auth-portal/` and `ingress/` have been replaced by the Helm
> chart in the Ops repository [`bernetlennard/user_mgmt_ops`](https://github.com/bernetlennard/user_mgmt_ops)
> (`charts/user-mgmt`), installed into the `prod` and `staging` namespaces. Only
> `traefik/` is still applied from here — moving the ingress controller into a separate
> `charts/platform` is the last migration step, because it is the one that can cost the
> public IP and the TLS certificate.
>
> This README is kept as documentation of Aufgabe 1: it is still the clearest explanation
> of *what the objects are*, which the chart only changes the packaging of.

This directory deploys the whole application to a DigitalOcean Kubernetes cluster,
reachable at **https://vcs.lennardbernet.ch** and **https://vcs.linosteiner.ch** (both
DNS names point at the same LoadBalancer IP and are routed in
`k8s/ingress/app-ingress.yaml`).

Everything is plain YAML applied with `kubectl apply -f`. No Helm, no Kustomize, no
namespace other than `default` — on purpose, so there is nothing between you and the
objects Kubernetes actually stores. Every file carries comments explaining what the
object is and why it is configured that way; this README covers how they fit together.

> **This is a learning cluster.** Secrets are committed in plain text and there is one
> replica of everything. See [Known simplifications](#known-simplifications).

---

## What runs here

| Component | Image | Port | Role |
|---|---|---|---|
| `traefik` | `traefik:v3.7` | 80, 443, 8080 | Ingress controller. The only pod reachable from the internet. Terminates TLS and obtains Let's Encrypt certificates. |
| `auth-portal` | `bernetlennard/auth_portal:latest` | 3000 | Next.js frontend. Serves everything except `/api`. |
| `user-mgmt-service` | `xxpirl2knc5/user_mgmt_service:latest` | 8080 | Spring Boot REST API (JWT auth). Serves `/api`. |
| `postgres` | `postgres:16-alpine` | 5432 | Database. Internal only. |

```
k8s/
├── README.md                  <- you are here
├── traefik/                   <- apply FIRST (LoadBalancer takes longest to provision)
│   ├── traefik-rbac.yaml         ServiceAccount + ClusterRole + ClusterRoleBinding
│   ├── traefik-ingressclass.yaml the name our Ingress points at
│   ├── traefik-config.yaml       static Traefik config, as a ConfigMap
│   ├── traefik-pvc.yaml          disk for acme.json (the TLS certificate)
│   ├── traefik-deployment.yaml
│   ├── traefik-service.yaml      type: LoadBalancer -> the public IP
│   └── traefik-dashboard-service.yaml  ClusterIP, port-forward only
├── postgres/                  <- apply SECOND (backend needs it at startup)
│   ├── postgres-config.yaml      POSTGRES_DB
│   ├── postgres-secret.yaml      POSTGRES_USER / POSTGRES_PASSWORD
│   ├── postgres-pvc.yaml         the database disk
│   ├── postgres-deployment.yaml
│   └── postgres-service.yaml
├── user-mgmt/
│   ├── user-mgmt-config.yaml     JWT + Spring settings
│   ├── user-mgmt-secret.yaml     JWT_SECRET
│   ├── user-mgmt-deployment.yaml
│   └── user-mgmt-service.yaml
├── auth-portal/
│   ├── auth-portal-config.yaml
│   ├── auth-portal-deployment.yaml
│   └── auth-portal-service.yaml
└── ingress/
    └── app-ingress.yaml       <- apply LAST (references the Services by name)
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
                                          │  reads Ingress/app-ingress via the k8s API
                                          │  terminates TLS here
                        ┌─────────────────┴──────────────────┐
                path: /api                              path: /
                        ▼                                    ▼
        Service/user-mgmt-service :8080         Service/auth-portal :3000
                        │  selector app=user-mgmt-service     │
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

**No path rewriting happens.** The backend receives `/api/users`, not `/users`. That
works because the Spring app mounts itself under `/api` via
`SERVER_SERVLET_CONTEXT_PATH`. (This is the one place the k8s setup differs from
`docker-compose.yaml` at the repo root, which uses a Traefik `stripprefix` middleware
instead. In Kubernetes that would need a Traefik `Middleware` CRD — pushing the config
back into the app avoids the extra object.)

---

## The object types, in this stack

Kubernetes has a lot of nouns. Here is each one used here, anchored to a real file.

**Deployment** — "keep N pods of this image running". You never create pods directly;
the Deployment creates a ReplicaSet which creates the Pods, and it handles replacing
them on config change or crash. All four Deployments here use `replicas: 1` and
`strategy: Recreate` (stop the old pod, then start the new one) rather than the default
RollingUpdate, because the node is too small to hold two copies at once and because
ReadWriteOnce volumes cannot be mounted twice.
→ `*/[name]-deployment.yaml`

**Service** — a stable name and virtual IP in front of a set of pods. Pod IPs change on
every restart; the Service name does not. Crucially the link is **by label selector,
not by name** — `Service/postgres` finds pods labelled `app: postgres`, and knows
nothing about `Deployment/postgres`. Cluster DNS resolves the Service name, which is
why the backend can just say `jdbc:postgresql://postgres:5432/…`.
Two types appear here: `ClusterIP` (internal only — the default) and `LoadBalancer`
(asks DigitalOcean for a real external load balancer and public IP).
→ `*/[name]-service.yaml`

**Ingress** — an HTTP routing table mapping host + path onto Services. It is *only
data*; it does nothing until an ingress controller reads it. That is Traefik's job here.
→ `ingress/app-ingress.yaml`

**IngressClass** — the name that connects an Ingress to a controller. Our Ingress says
`ingressClassName: traefik`, which resolves to the IngressClass object, which names
Traefik as the implementation.
→ `traefik/traefik-ingressclass.yaml`

**ConfigMap** — non-secret key/value config. Can be injected as environment variables
(all of ours except one) or mounted as files, where each key becomes a filename. The
Traefik config uses that second form: the key `traefik.yaml` becomes the file
`/etc/traefik/traefik.yaml`.
→ `*/[name]-config.yaml`

**Secret** — the same thing, flagged as sensitive. Kubernetes hides it from
`kubectl describe`, can encrypt it at rest, and mounts it as tmpfs. Note it is only
**base64-encoded, not encrypted** — the files here use `stringData` so you can see the
plaintext directly.
→ `postgres/postgres-secret.yaml`, `user-mgmt/user-mgmt-secret.yaml`

**PersistentVolumeClaim (PVC)** — a request for disk that outlives the pod. A pod's
filesystem is destroyed with the pod, so anything that must survive a restart needs one.
`ReadWriteOnce` means one node can mount it at a time — that constraint is what forces
`strategy: Recreate`.
→ `postgres/postgres-pvc.yaml` (the database), `traefik/traefik-pvc.yaml` (the TLS cert)

**ServiceAccount / ClusterRole / ClusterRoleBinding** — identity and permissions for a
pod that talks to the Kubernetes API. Traefik needs this because it *watches* Ingress
objects rather than reading a static config file. Three objects: the account (who), the
role (what may be done), the binding (glue).
→ `traefik/traefik-rbac.yaml`

### Two things there is no object for

- **Namespace** — everything lands in `default`. The one place this is hardcoded is the
  `ClusterRoleBinding` subject in `traefik-rbac.yaml`; that line must change if the
  stack ever moves.
- **The TLS certificate** — normally `spec.tls.secretName` in the Ingress would point at
  a Secret holding the cert. Ours has no `secretName`: Traefik obtains and stores the
  certificate itself in `acme.json` on its PVC. `kubectl get secret` will never show it.

---

## How TLS works

1. The Ingress annotation `router.tls.certresolver: letsencrypt` names a resolver
   defined in `traefik-config.yaml`.
2. On startup, if `acme.json` has no valid certificate for the host, Traefik asks
   Let's Encrypt for one using the **HTTP-01 challenge**.
3. Let's Encrypt calls back to `http://vcs.lennardbernet.ch/.well-known/acme-challenge/<token>`.
   This is why port 80 must stay publicly reachable even though everything else is
   redirected to HTTPS — Traefik answers the challenge path *before* applying the
   redirect.
4. The certificate is written to `/letsencrypt/acme.json` on `traefik-acme-pvc`, and
   renewed automatically.

Consequences worth knowing:

- **Delete `traefik-acme-pvc` and you re-issue the certificate.** Let's Encrypt allows
  5 duplicate certificates per week — burn through that and you are locked out until
  the window rolls.
- **DNS must be correct before the first request**, or step 3 fails. See below.
- Traefik does not automatically retry a failed ACME request
  ([traefik#9405](https://github.com/traefik/traefik/issues/9405)). That is why
  `traefik-service.yaml` lowers the DigitalOcean health-check threshold — see the
  comment in that file.

---

## Configuration reference

Where each key ends up. The mechanism for the Spring app is **relaxed binding**: Spring
Boot maps a `SCREAMING_SNAKE_CASE` environment variable onto a dotted property
automatically, which is why several of these work without appearing in
`src/main/resources/application.properties` at all.

| Key | Source | Lands on |
|---|---|---|
| `POSTGRES_DB` | `postgres-config` | Postgres: creates the DB. Backend: interpolated into `SPRING_DATASOURCE_URL`. |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres-secret` | Postgres superuser; injected into the backend as `SPRING_DATASOURCE_USERNAME` / `_PASSWORD`. |
| `SPRING_DATASOURCE_URL` | literal in the Deployment | `spring.datasource.url` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `user-mgmt-config` | `spring.jpa.hibernate.ddl-auto`. Set to `update` (alter schema, keep data). |
| `JWT_ISSUER` | `user-mgmt-config` | `jwt.issuer` |
| `JWT_EXPIRATION_MILLIS` | `user-mgmt-config` | `jwt.expirationMillis`. **Live config** — bound onto `JwtProperties` (`@ConfigurationProperties("jwt")`, `src/main/java/com/example/jwt/core/security/helpers/JwtProperties.java`) purely by relaxed binding. It is easy to mistake for dead config because `application.properties` never mentions it. |
| `JWT_SECRET` | `user-mgmt-secret` | `jwt.secret`. The HMAC signing key. |
| `SERVER_SERVLET_CONTEXT_PATH` | `user-mgmt-config` | `server.servlet.context-path` = `/api`. Same relaxed-binding trick; this is what makes the Ingress work without stripPrefix. |
| `JAVA_TOOL_OPTIONS` | `user-mgmt-config` | `-XX:MaxRAMPercentage=60` — sizes the heap against the container limit, not the node's RAM. |
| `PORT`, `NODE_ENV` | `auth-portal-config` | Next.js runtime settings. |

`NEXT_PUBLIC_API_URL` is **not** configurable here. Next.js inlines `NEXT_PUBLIC_*` into
the client bundle at build time, so `https://vcs.lennardbernet.ch/api` is baked into the
image. Changing the domain means rebuilding the frontend.

### Health probes

| Pod | Probe | Why |
|---|---|---|
| `postgres` | `exec` `pg_isready` | Real check — verifies the server accepts connections. |
| `traefik` | `tcpSocket :80` | Ready as soon as it is listening. |
| `user-mgmt-service` | `tcpSocket :8080` | No `spring-boot-starter-actuator` dependency, so there is no `/actuator/health`; and `WebSecurityConfig` requires auth on every other path, so an HTTP probe would get 401/403 and be counted as a failure. Adding the actuator would upgrade this to a real health check. |
| `auth-portal` | `httpGet /` | The frontend serves `/` unauthenticated. 2xx and 3xx both count as a pass. |

The **readiness** probes are what stop `strategy: Recreate` from serving 502s: until a
probe passes, the pod is not an endpoint of its Service and Traefik will not route to it.
The **liveness** probes restart a hung container, and have deliberately long
`initialDelaySeconds` so a slow JVM start can never trigger a restart loop.

---

## Deploy

### Prepare

```bash
# Point kubeconfig at the current cluster. After a cluster rebuild the old config
# points at a dead endpoint ("no such host").
doctl kubernetes cluster list
doctl kubernetes cluster kubeconfig save <cluster-id>

kubectl get nodes          # must report Ready
```

### 1. Traefik first

The LoadBalancer takes the longest to provision, and nothing else is reachable without it.

```bash
kubectl apply -f k8s/traefik/
kubectl get svc traefik -w      # wait for EXTERNAL-IP, then Ctrl-C
```

### 2. DNS — and the AAAA trap

Point an **A-record** for `vcs.lennardbernet.ch` at that EXTERNAL-IP:

```bash
kubectl get svc traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

> **This is the single most common way to break this deployment. Read it before
> blaming Traefik.**
>
> There must be **no AAAA record** on `vcs.lennardbernet.ch`.
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

Verify:

```bash
nslookup -type=A    vcs.lennardbernet.ch 8.8.8.8   # -> the EXTERNAL-IP
nslookup -type=AAAA vcs.lennardbernet.ch 8.8.8.8   # -> must be EMPTY
```

### 3. The rest

```bash
kubectl apply -f k8s/postgres/
kubectl wait --for=condition=ready pod -l app=postgres --timeout=120s

kubectl apply -f k8s/user-mgmt/ -f k8s/auth-portal/
kubectl apply -f k8s/ingress/
```

### 4. Verify

```bash
kubectl get pods,svc,ingress

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
kubectl logs deployment/postgres
kubectl logs deployment/user-mgmt-service
kubectl logs deployment/auth-portal

# On CrashLoopBackOff, the interesting logs belong to the PREVIOUS container:
kubectl logs -l app=postgres --previous --tail=100

# Why is a pod not Ready? Probe failures and image pull errors show up here:
kubectl describe pod -l app=user-mgmt-service

# Node capacity — this node is small (~1.5Gi allocatable, requests around 87%).
# A pod stuck in Pending is usually this.
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
kubectl apply --dry-run=client -f k8s/traefik/ -f k8s/postgres/ \
  -f k8s/user-mgmt/ -f k8s/auth-portal/ -f k8s/ingress/
```

---

## Teardown

```bash
kubectl delete -f k8s/ingress/ -f k8s/auth-portal/ -f k8s/user-mgmt/ \
  -f k8s/postgres/ -f k8s/traefik/

# The DigitalOcean load balancer is billed separately and is NOT always removed with
# the Service. Always check:
doctl compute load-balancer list
```

This deletes both PVCs, so **the database and the TLS certificate are gone**. Keep the
Let's Encrypt rate limit in mind before tearing down and rebuilding repeatedly.

---

## Known simplifications

Things that are deliberately wrong-but-simple here, and what the real answer is:

- **Secrets are committed in plain text.** `postgres-secret.yaml` (admin/admin) and
  `user-mgmt-secret.yaml` (the real JWT signing key, shared with the local `.env`).
  Real options: Sealed Secrets, External Secrets Operator, or
  `kubectl create secret generic` and never committing them.
- **Postgres is a Deployment, not a StatefulSet.** With one replica the behaviour is
  identical; a StatefulSet is what you would use for stable pod identity and
  per-replica volumes.
- **`replicas: 1` and `strategy: Recreate` everywhere**, so every deploy has a short
  outage. Driven by node size (~1.5Gi allocatable), not by preference.
- **Both application images use the `:latest` tag**, so there is no way to roll back to
  a known version. `imagePullPolicy: Always` is set on both to at least guarantee the
  newest one is pulled. Tagging by commit SHA is the fix, and for the backend the
  tags already exist — `.github/workflows/deploy.yml` pushes both `:latest` and
  `:${{ github.sha }}`. The frontend image is built elsewhere and has no SHA tag.
- **The hostname is hardcoded** in `app-ingress.yaml` (twice) and baked into the
  frontend image at build time. This is exactly the kind of thing Helm values solve.
- **DigitalOcean-specific bits leak into generic manifests** — the load-balancer
  health-check annotation in `traefik-service.yaml`.
- **The Traefik dashboard runs with no authentication** (`api.insecure: true`). Only
  safe because port 8080 is never exposed through the LoadBalancer.
- **`docker-compose.yaml` at the repo root is a completely separate deployment path**
  targeting plain droplets, and it behaves differently (stripPrefix instead of a
  context path, `create-drop` instead of `update`). `.github/workflows/deploy.yml`
  drives *that*, not this cluster — **nothing in CI applies these manifests.** The
  cluster is deployed by hand from this README.
- **App-side, outside this directory:** `src/main/resources/application.properties`
  hardcodes `logging.level.root=DEBUG` and `spring.jpa.show-sql=true` for every
  environment. Noisy and leaky in a public deployment; worth making env-driven.
