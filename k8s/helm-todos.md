# Helm Chart (Aufgabe 2) — learning path + migration plan

> **Status 2026-08-31: Phases 0–4 are done.** The chart lives in
> [`bernetlennard/user_mgmt_ops`](https://github.com/bernetlennard/user_mgmt_ops) at
> `charts/user-mgmt`. It passes `helm lint --strict`, and is installed as two releases:
> `um-prod` in namespace `prod` (serving both hostnames) and `um-staging` in namespace
> `staging` (ingress disabled). The Aufgabe 1 workloads have been deleted from `default`;
> only Traefik still runs from `k8s/traefik/`. **Phase 5 (Traefik → `charts/platform`)
> is still open.**
>
> Corrections to what is written below, found while doing it:
>
> - The migration plan assumed the chart was unwritten. It was already largely complete;
>   what it was missing was `SPRING_DATASOURCE_USERNAME`/`PASSWORD` on the backend (the
>   old manifest wired them from the Postgres Secret via `secretKeyRef`; `envFrom` cannot
>   rename keys, so they have to stay explicit `env` entries).
> - Phase 3's "install on a test hostname" needed no DNS record: installing with
>   `--set ingress.enabled=false` and verifying through `kubectl port-forward` proves the
>   same things without touching DNS or spending a Let's Encrypt issuance.
> - Phase 0.4's advice to change `NEXT_PUBLIC_API_URL` to the relative `/api` is wrong for
>   `app/auth/login/route.ts` and `app/auth/me/route.ts`: those are server-side route
>   handlers running in Node, where a relative `fetch()` has no base URL to resolve
>   against. They need an in-cluster absolute URL (the backend Service name); only the
>   browser-side `signup/page.tsx` can use the relative path. **Still open.**

## Context

Aufgabe 1 is done: the Docker Compose stack is migrated to DOKS as plain manifests in
[k8s/](k8s/), documented in [k8s/README.md](k8s/README.md), and running (all 4 pods healthy).

Aufgabe 2 replaces those manifests with a Helm chart. All six Aufgaben are due together, so
the chart is not a standalone deliverable — it is the foundation the remaining four sit on:

| Aufgabe | What it needs from the chart |
|---|---|
| 3 ArgoCD | Chart lives in a separate **Ops repository** on GitHub |
| 4 Pipeline | CI writes the image tag into `values.yaml` → tag must be a value, `:latest` must go |
| 5 Namespaces | `values-staging.yaml` / `values-prod.yaml`; chart must be namespace-agnostic and installable **twice** |
| 6 Scaling (40%) | `replicas`, `resources`, `strategy`, HPA, PDB all templated |

Building the chart with 3–6 already in view is the whole point. Decisions below are made
for that reason, not for Aufgabe 2 in isolation.

---

## Cluster reality (measured, 2026-08-26)

Two things will block Aufgaben 5 and 6 and are worth fixing early, because they also make
the Helm migration itself safer.

**1. No metrics-server.** `kubectl top nodes` → `error: Metrics API not available`.
There is no `metrics-server` in `kube-system` and no `metrics.k8s.io` APIService.
**An HPA without it will sit at `<unknown>/70%` and never scale.** Aufgabe 6 is 40% of the grade.

**2. Memory is effectively full.**

```
cpu      737m  (38%)  of 1900m     <- room to spare
memory  1286Mi (87%)  of 1499Mi    <- ~213Mi free
```

One extra backend replica requests 384Mi. Aufgabe 5 (two full stacks) needs ~640Mi more.
Neither fits. You said you'll resize when needed — **do it in Phase 0**, because the safe
migration path below runs the new Helm release alongside the old manifests for a while.

**Good news:** the CNI is Cilium (`cilium`, `hubble-relay`, `hubble-ui` in `kube-system`),
so the NetworkPolicies in Aufgabe 5 will actually be enforced rather than silently ignored.

**Also captured:** DO LoadBalancer `f52b840c-2c08-45ff-a210-3d829e63bb20` → `129.212.193.64`.
That ID is how you keep your IP when Traefik moves into a chart (Phase 5).

---

## Decisions

### Two charts, not one

You said "no idea" on this one. Here is the reasoning, then the call.

Traefik is **cluster** infrastructure. Three concrete things break if it lives in the app chart
and that chart gets installed twice for staging + prod:

1. `IngressClass` and `ClusterRole`/`ClusterRoleBinding` are **cluster-scoped** — they have no
   namespace. The second install tries to create an object that already exists under another
   release's ownership, and Helm refuses.
2. `Service type: LoadBalancer` provisions a **real, billed** DigitalOcean load balancer. Two
   installs = two LBs = two public IPs = two sets of DNS records.
3. Two Traefiks would independently ask Let's Encrypt for certificates. The limit is 5
   duplicate certs per week and you would be sharing it with yourself.

The correct shape — and the one that makes Aufgabe 5 fall out almost for free — is **one
ingress controller for the whole cluster**, routing by hostname into Ingress objects that live
in different namespaces. Traefik's ClusterRole already lets it watch every namespace, so this
needs no extra work.

```
charts/
├── platform/      Traefik, IngressClass, RBAC, LoadBalancer, ACME PVC   -> installed ONCE
└── user-mgmt/     postgres + backend + frontend + ingress               -> installed PER environment
```

Both are Helm charts, so *"sämtliche Kubernetes Manifests als Helm Templates"* is satisfied.

### The rest

| Decision | Choice | Why |
|---|---|---|
| Ops repo | `user_mgmt_ops`, **private**, created now | Aufgabe 3 needs it; building there from day one avoids re-wiring ArgoCD later |
| Secrets | `stringData` from values, private repo, tradeoff documented | Sealed Secrets is the real answer but is a whole extra topic; note it in the README as a known simplification, like the current one does |
| JWT secret | **Rotate it** | It is committed in plaintext at `k8s/user-mgmt/user-mgmt-secret.yaml` and shared with the local `.env`. If `linosteiner/user_mgmt_service` is public, that key is public. Generate a new one as part of this move |
| Traefik migration | **Last**, after the app chart is proven | It is the only step that can cost you the public IP and the TLS cert |

---

## Phase 0 — Prerequisites (do before writing any template)

These are independent of Helm but every one of them blocks something later.

**0.1 Create the Ops repo.** No `gh` CLI on this machine — create `user_mgmt_ops` on
github.com, **private**, then clone it next to this repo.

**0.2 Resize the DOKS node pool.** Target ~2vCPU/4GB. Note that DOKS resizes by draining onto
new nodes: the postgres pod is rescheduled and its DO block volume detaches and reattaches.
Expect a short outage; verify the PVC comes back bound before continuing.

**0.3 Install metrics-server — and make this your first Helm exercise.**

The gentlest way into Helm is to *consume* a chart before you author one. This is real,
required work and it teaches `repo add` / `install` / `list` / `get values` with zero risk:

```bash
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm repo update
helm install metrics-server metrics-server/metrics-server --namespace kube-system
kubectl top nodes
```

If `kubectl top` still errors after a minute, re-run with
`--set args={--kubelet-insecure-tls}`. Then poke at what you just did — `helm list -A`,
`helm get values metrics-server -n kube-system`, `helm get manifest metrics-server -n kube-system`.
That last one is the whole concept in one command: **a release is just rendered YAML that Helm remembers.**

**0.4 Fix `NEXT_PUBLIC_API_URL` in the frontend repo.** Currently `https://vcs.lennardbernet.ch/api`
is inlined into the JS bundle at build time, so **one image cannot serve two hostnames** — which
is exactly what Aufgabe 5 asks for. Change the frontend to call the relative path `/api` and
rebuild. It is roughly a one-line change and it is far cheaper now than discovering it at Aufgabe 5.

---

## Phase 1 — Learn Helm (half a day, hands-on)

### The one idea

> **Helm is a Go-template renderer plus a ledger of what it applied.** That's it.

`helm template` renders and prints. `helm install` renders, applies, and records a release.
Everything else is detail. Three inputs are in scope inside a template: `.Values` (your
values.yaml), `.Release` (name, namespace), `.Chart` (name, version).

### The loop that makes this verifiable

You are in an unusually good position to learn this: **you already have known-good YAML.**
So you never have to wonder whether a template is right — you can diff it.

```bash
helm template um ./charts/user-mgmt > rendered.yaml
```

Render → compare against the manifest you're converting → fix → repeat. Do not install
anything until the rendered output matches what is running. Names and label blocks will
differ on purpose; the container spec, env vars, ports and probes should not.

Work through the manifests **easiest first**, learning exactly one new construct per file:

| Order | File | New thing you learn |
|---|---|---|
| 1 | `postgres-config.yaml` | `{{ .Values.x }}` — plain substitution |
| 2 | `postgres-secret.yaml` | `quote`, and why `stringData` saves you from base64 |
| 3 | `postgres-service.yaml` | `.Release.Name`, and the selector/label contract |
| 4 | `postgres-deployment.yaml` | `toYaml \| nindent` for the resources and probe blocks |
| 5 | `user-mgmt-*` | `_helpers.tpl` — refactor the labels you have now copy-pasted 4× |
| 6 | `auth-portal-*` | proves the helper is actually reusable |
| 7 | `app-ingress.yaml` | `range` over hosts, `if` for TLS |

**Do not start with `_helpers.tpl`.** Copy-paste the label block the first four times and let
it annoy you. Refactoring it into a helper at step 5 is when `define`/`include` becomes obvious
instead of abstract.

### Five constructs cover ~95% of real charts

```gotemplate
{{ .Values.backend.image.tag }}                        {{/* substitute        */}}
{{ include "user-mgmt.labels" . | nindent 4 }}         {{/* call a helper     */}}
{{- if .Values.backend.autoscaling.enabled }}          {{/* conditional block */}}
{{- range .Values.ingress.hosts }}                     {{/* loop              */}}
{{ toYaml .Values.backend.resources | nindent 12 }}    {{/* splice a subtree  */}}
```

### Gotchas — read these now, they save hours

- **Whitespace.** `{{-` eats preceding whitespace, `-}}` eats following. Use `nindent N`
  (newline + indent), not `indent N`, when splicing a block onto its own line. Indentation
  errors are the #1 source of "why is my YAML invalid".
- **`spec.selector` on a Deployment is immutable.** Change your label helper after installing
  and `helm upgrade` fails outright. This is the single most confusing Helm error for beginners.
  Settle your labels early.
- **Numbers vs strings.** `expirationMillis: 3600000` in values renders as a number, but
  ConfigMap `data:` values must be strings. Use `| quote`. Symptom: a confusing API server
  validation error, not a template error.
- **Scope inside `range`.** `.` is rebound to the current element, so `.Values` breaks. Use
  `$.Values` or capture `{{- $root := . -}}` first.
- **`helm lint --strict`** turns warnings into errors, including "chart has no icon". Aufgabe 2
  only requires no *errors*, so plain `helm lint` is the bar — but pass `--strict` anyway and
  fix the warnings, they're cheap.

### Reference, in the order it's worth reading

1. `helm create demo` in the scratchpad, then `helm template demo`. Read its `_helpers.tpl` —
   it is the canonical example of exactly what Aufgabe 2's `_helpers.tpl` criterion is asking for.
   Then **throw the rest away**; the generated Deployment is more confusing than helpful.
2. Helm docs → *Chart Template Guide* (the only section worth reading front to back).
3. `helm install --dry-run --debug` when you want to see the computed values, not just output.

---

## Phase 2 — Aufgabe 2: the app chart

### Layout

```
user_mgmt_ops/
├── README.md
├── charts/
│   ├── user-mgmt/
│   │   ├── Chart.yaml
│   │   ├── values.yaml                  <- the single source of config
│   │   ├── .helmignore
│   │   └── templates/
│   │       ├── _helpers.tpl
│   │       ├── NOTES.txt
│   │       ├── postgres/   {configmap,secret,pvc,deployment,service}.yaml
│   │       ├── backend/    {configmap,secret,deployment,service,hpa,pdb}.yaml
│   │       ├── frontend/   {configmap,deployment,service}.yaml
│   │       └── ingress.yaml
│   └── platform/                        <- Phase 5
├── values-staging.yaml                  <- Aufgabe 5
└── values-prod.yaml                     <- Aufgabe 5
```

Let `helm create` generate `Chart.yaml` and keep whatever `apiVersion` it emits (you're on
Helm v4.1.4), then delete everything under `templates/` and build up from nothing.

### values.yaml shape

Everything currently hardcoded becomes a value. The keys that matter most for later:

```yaml
backend:
  image:
    repository: xxpirl2knc5/user_mgmt_service
    tag: latest                 # <- Aufgabe 4's pipeline rewrites exactly this line
  replicaCount: 1
  strategy: { type: Recreate }  # <- becomes RollingUpdate in Aufgabe 6
  resources:
    requests: { cpu: 100m, memory: 384Mi }
    limits:   { memory: 700Mi }
  autoscaling:                  # <- Aufgabe 6
    enabled: false
    minReplicas: 1
    maxReplicas: 4
    targetCPUUtilizationPercentage: 70
  podDisruptionBudget:          # <- Aufgabe 6
    enabled: false
    minAvailable: 1

ingress:
  className: traefik
  hosts: [vcs.lennardbernet.ch, vcs.linosteiner.ch]   # <- per-environment in Aufgabe 5
  tls: { enabled: true, certResolver: letsencrypt }
```

Ship the Aufgabe 6 keys **now, disabled**. `{{- if .Values.backend.autoscaling.enabled }}`
costs nothing today and means Aufgabe 6 is a values change rather than a chart change.

The two `/api` → backend and `/` → frontend paths are *structural*, not configuration — keep
them literal in `ingress.yaml` and `range` only over `hosts`.

### _helpers.tpl — the Aufgabe 2 criterion

The chart has three components that all need names and labels. That repetition is what makes
a genuinely reusable helper possible, rather than a token one. Parameterise by component
using `dict`:

```gotemplate
{{- define "user-mgmt.selectorLabels" -}}
app.kubernetes.io/name: {{ include "user-mgmt.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}
```

Called as `{{- include "user-mgmt.labels" (dict "ctx" $ "component" "backend") | nindent 4 }}`.

Planned helpers:

| Helper | Purpose |
|---|---|
| `user-mgmt.name` / `.fullname` / `.chart` | standard naming, release-prefixed |
| `user-mgmt.componentName` | `<fullname>-postgres`, `-backend`, `-frontend` |
| `user-mgmt.labels` / `.selectorLabels` | the component-parameterised pair above |
| `user-mgmt.image` | `repository:tag` from an image dict |
| `user-mgmt.postgres.jdbcUrl` | **kills the hardcoded `jdbc:postgresql://postgres:5432/...`** |

That last one is worth calling out when you present: the current
[user-mgmt-deployment.yaml](k8s/user-mgmt/user-mgmt-deployment.yaml) hardcodes the JDBC URL and
relies on the Service being named exactly `postgres`. Once names are release-prefixed that
breaks, and the helper is what fixes it — a concrete demonstration of *warum kein Hardcoding*.

### One extra worth adding

The checksum trick, on both app Deployments:

```gotemplate
annotations:
  checksum/config: {{ include (print $.Template.BasePath "/backend/configmap.yaml") . | sha256sum }}
```

Change a ConfigMap value → the pod template hash changes → the pod restarts and picks it up.
Plain `kubectl apply` never does this; it is the clearest single example of Helm earning its keep.

---

## Phase 3 — Migrate onto the chart safely

Your running Deployments select on `app: postgres`. The chart selects on
`app.kubernetes.io/*`. **`spec.selector` is immutable, so there is no in-place upgrade path.**

Rather than delete-and-pray, install the chart into a *new namespace* on a *test hostname*,
verify it end-to-end, and only then cut over. This is also Aufgabe 5's namespace requirement,
proven early and for free. It needs the Phase 0 node resize to have headroom for both stacks.

```bash
# 1. DNS: A-record staging.vcs.lennardbernet.ch -> 129.212.193.64
#    NO AAAA record. See k8s/README.md — this is the trap that eats the most time.

# 2. Install alongside the running stack
helm install um-staging ./charts/user-mgmt \
  --namespace staging --create-namespace \
  --set ingress.hosts={staging.vcs.lennardbernet.ch}

# 3. Verify (see below), then cut over
helm install um-prod ./charts/user-mgmt --namespace prod --create-namespace
kubectl delete -f k8s/postgres/ -f k8s/user-mgmt/ -f k8s/auth-portal/ -f k8s/ingress/
```

The existing Traefik in `default` routes into both namespaces without any change — its
ClusterRole already watches cluster-wide.

**Note:** the old `postgres-pvc` is not carried over, so data in the `default` database is lost.
With `ddl-auto: update` the schema rebuilds itself; only registered users disappear. For a
learning cluster that is fine — just don't be surprised.

---

## Phase 4 — Verification

```bash
# Acceptance criterion for Aufgabe 2
helm lint ./charts/user-mgmt
helm lint ./charts/user-mgmt --strict          # stretch goal, fix the warnings

# Prove it templates for every environment before installing anything
helm template um ./charts/user-mgmt -f values-staging.yaml | kubectl apply --dry-run=client -f -
helm template um ./charts/user-mgmt -f values-prod.yaml    | kubectl apply --dry-run=client -f -

# Prove no hardcoding: two releases must not collide on any name
diff <(helm template a ./charts/user-mgmt) <(helm template b ./charts/user-mgmt)
# every difference should be a name or label, nothing else

# End-to-end, same checks as k8s/README.md
kubectl get pods,svc,ingress -n staging
curl -4 -I  https://staging.vcs.lennardbernet.ch          # 307 -> /dashboard
curl -4 -sk https://staging.vcs.lennardbernet.ch/api/users # 403 = backend answered
                                                           # 404 = routed to frontend, ingress wrong
```

A **403** from `/api/users` is the success case — the backend got the request and rejected it
for lack of a JWT. **404** means Traefik sent it to the frontend.

---

## Phase 5 — The platform chart (Traefik)

Do this **only after Phase 4 passes.** It is the one step that can cost you the public IP and
the TLS certificate.

Templatize `k8s/traefik/` into `charts/platform` — mostly mechanical, plus one important value:
the ACME email and the DO health-check annotation come out of `traefik-service.yaml` and
`traefik-config.yaml` into `values.yaml`.

To keep `129.212.193.64` when the Service moves namespace, set the DO adoption annotation:

```yaml
service:
  annotations:
    service.beta.kubernetes.io/do-loadbalancer-id: "f52b840c-2c08-45ff-a210-3d829e63bb20"
    service.beta.kubernetes.io/do-loadbalancer-healthcheck-healthy-threshold: "2"
```

**Verify this actually binds** before deleting the old Service — if the IP changes anyway, the
fallback is simply to update both DNS A-records. Either way the ACME PVC does not follow across
namespaces, so **the certificate is re-issued once**. That is well within Let's Encrypt's
5-per-week limit, but don't do it repeatedly in one session.

Also fix while you're in there: `traefik-rbac.yaml` hardcodes `namespace: default` in the
ClusterRoleBinding subject. That becomes `{{ .Release.Namespace }}`.

Afterwards `k8s/` is fully superseded. Keep it in the app repo as documentation — its README is
a genuinely good explanation of the objects — but add a line at the top saying the cluster is
now deployed from the Ops repo.

---

## What comes after (so the chart doesn't need revisiting)

| Aufgabe | Work | Already handled by the plan above |
|---|---|---|
| 3 ArgoCD | Install in `argocd` namespace, `Application` manifests pointing at `charts/user-mgmt` | Ops repo exists from Phase 0; install ArgoCD via Helm — you'll be fluent by then |
| 4 Pipeline | Strip the two `deploy-*` ssh jobs from [deploy.yml](.github/workflows/deploy.yml); add a job that commits the new SHA into `values.yaml` | `backend.image.tag` is already the single line to rewrite |
| 5 Namespaces | `values-staging.yaml` / `values-prod.yaml`, ResourceQuota, NetworkPolicy | Namespaces proven in Phase 3; Cilium enforces the policies |
| 6 Scaling | Flip `autoscaling.enabled`, `strategy: RollingUpdate`, `pdb.enabled` | Keys shipped disabled in Phase 2; metrics-server installed in Phase 0 |

**Critical path:** Phase 0 gates everything — no metrics-server means no Aufgabe 6 (40%), and no
node resize means no Aufgabe 5. Do Phase 0 first even though it feels like a detour from Helm.
