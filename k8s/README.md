# Cluster infrastructure applied by hand

Only what the app chart deliberately does not contain is left here:

| Directory | What | Why not in the chart |
|---|---|---|
| `traefik/` | the ingress controller (namespace `default`) | cluster-scoped `IngressClass`/`ClusterRole` and a billed DigitalOcean load balancer; one Traefik serves every namespace |
| `argocd/` | ArgoCD's own settings (local account, RBAC, its Ingress) | ArgoCD cannot bootstrap itself |

```bash
kubectl apply -f k8s/traefik/
kubectl apply -n argocd -f k8s/argocd/
```

The application itself (backend, frontend, module_service, Ingress, HPA, ...) is deployed from
the Helm chart in the Ops repository
[`bernetlennard/user_mgmt_ops`](https://github.com/bernetlennard/user_mgmt_ops), through
ArgoCD. The earlier static manifests for the app and the in-cluster PostgreSQL (Deployment,
Service, PersistentVolumeClaim, Secret) were removed once the database moved to DigitalOcean
Managed PostgreSQL (Aufgabe 4); they are still in the git history.
