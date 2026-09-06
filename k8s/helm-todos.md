# Helm migration — what is left

The chart lives in [`bernetlennard/user_mgmt_ops`](https://github.com/bernetlennard/user_mgmt_ops)
at `charts/user-mgmt`, passes `helm lint --strict`, and is installed as two releases:
`um-prod` in namespace `prod` (serving both hostnames) and `um-staging` in `staging`
(ingress disabled). The application workloads are gone from `default`; only Traefik still
runs from `k8s/traefik/`.

## Open

**Move Traefik into `charts/platform`.** Templatising `k8s/traefik/` is mostly mechanical;
the ACME email and the DigitalOcean health-check annotation become values, and the
`namespace: default` hardcoded in the ClusterRoleBinding subject becomes
`{{ .Release.Namespace }}`.

Traefik stays **one installation for the whole cluster**, separate from the app chart,
because `IngressClass` and the `ClusterRole`/`ClusterRoleBinding` are cluster-scoped (a
second install collides), a second `type: LoadBalancer` Service means a second billed DO
load balancer and public IP, and two Traefiks would share one Let's Encrypt rate limit.

To keep the IP `129.212.193.64` when the Service moves namespace, set the adoption
annotation and verify it binds **before** deleting the old Service:

```yaml
service:
  annotations:
    service.beta.kubernetes.io/do-loadbalancer-id: "f52b840c-2c08-45ff-a210-3d829e63bb20"
    service.beta.kubernetes.io/do-loadbalancer-healthcheck-healthy-threshold: "2"
```

The ACME PVC does not follow across namespaces either way, so the certificate is re-issued
once — fine within the 5-per-week limit, but not repeatedly in one session.

**`NEXT_PUBLIC_API_URL` in the frontend repo.** `https://vcs.lennardbernet.ch/api` is
inlined into the bundle at build time, so one image cannot serve two hostnames. The
browser-side `signup/page.tsx` can use the relative `/api`, but `app/auth/login/route.ts`
and `app/auth/me/route.ts` are server-side handlers running in Node, where a relative
`fetch()` has no base URL to resolve against — they need the in-cluster absolute URL of
the backend Service.

## Cluster facts worth keeping

- The CNI is Cilium, so NetworkPolicies are actually enforced rather than silently ignored.
- DO LoadBalancer `f52b840c-2c08-45ff-a210-3d829e63bb20` → `129.212.193.64`.
- An HPA needs `metrics-server`; without it it sits at `<unknown>/70%` and never scales.
