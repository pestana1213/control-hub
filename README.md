# Control Hub

Control Hub is a small control plane for applications running in Kubernetes. It keeps an application registry, observes Kubernetes resources, exposes application health, and records operational actions such as restarts.

The project is built around three domain concepts:

- **Application** — something deployed in the homelab, such as Jellyfin, Ollama, PostgreSQL or Pi-hole.
- **Operation** — an action requested against an application: `RESTART`, `SCALE`, `DEPLOY`, `SYNC` or `ROLLBACK`.
- **Observed state** — the latest Kubernetes snapshot for an application, collected by informers and exposed through the `KubernetesObservedState` interface.

## Architecture

Kubernetes informers watch Deployments, Pods, Services and Ingresses. Resource events are deduplicated into a reconciliation queue. The reconciliation worker waits until all informer caches are synced, reads the latest cache state, registers new applications, removes applications that no longer exist, and stores the observed snapshot.

The application API reads that snapshot instead of querying Kubernetes for every request. This reduces API-server traffic and keeps reads consistent with the reconciler's view of the cluster.

The current observed-state implementation is in-memory. It is behind an interface so it can later be replaced with Redis when Control Hub runs with multiple pods. The queue is also in-memory and is currently intended for one active Control Hub pod.

## Health

`ApplicationStatusService` calculates application health and returns one of:

- `HEALTHY`
- `DEGRADED`
- `UNHEALTHY`
- `UNKNOWN`

It also reports explicit reasons such as `POD_CRASH_LOOP`, `REPLICA_NOT_READY`, `IMAGE_PULL_ERROR`, `ROLLOUT_IN_PROGRESS`, `PROGRESS_DEADLINE_EXCEEDED`, `REPLICA_FAILURE` and `OBSERVED_STATE_UNAVAILABLE`.

Alongside the status and reasons, health carries the observed spec and signals needed to explain a failure: `desiredReplicas`, `readyReplicas`, `image`, `restartCount`, the Deployment `conditions` and `lastObservedAt` — the reconciler's observation time, not the time of the request.

`image` is read from the container named after the Deployment, falling back to its first container. `DEPLOY` patches the container by the same rule, so both agree on which image belongs to the application.

## Applications API

- `GET /v1/applications` — every registered application with its observed state.
- `GET /v1/applications/{id}` — a single application, or `404` when it is not registered.
- `GET /v1/applications/{id}/events?limit=20` — the recorded health transitions, newest first.
- `PUT /v1/applications/{id}/desired-state` — declare the spec an application should have.
- `GET /v1/applications/{id}/desired-state` — read it back, or `404` when nothing is declared.
- `DELETE /v1/applications/{id}/desired-state` — stop managing the application.
- `GET /v1/applications/{id}/diff` — drift between the declared spec and the cluster.

An application whose Kubernetes state cannot be read is returned with `UNKNOWN` health and an `OBSERVED_STATE_UNAVAILABLE` reason rather than being omitted.

### Health transitions

The reconciler records a transition whenever an application's health changes, which is what answers "what happened?" without storing every Kubernetes event indefinitely:

```json
[
  {
    "from": "HEALTHY",
    "to": "DEGRADED",
    "reasonCode": "POD_CRASH_LOOP",
    "message": "Pod/production/api-7f/api: Container repeatedly fails to start",
    "occurredAt": "2026-11-03T10:15:30Z"
  }
]
```

A row is written only when the status *or* its most severe reason changes, so `DEGRADED (REPLICA_NOT_READY)` becoming `DEGRADED (POD_CRASH_LOOP)` is recorded while a hundred unchanged reconciliations are not. The reconciler runs on every informer event, so this gate is what keeps a single restart from writing hundreds of near-identical rows.

These are Control Hub's own transitions. They are not the raw Kubernetes events, which remain available inline as the `events` field of an application.

History is pruned on a schedule, and transitions are removed with their application when it is deregistered. An application retained for its operation history records a transition to `UNKNOWN` once it disappears from the cluster, so its last known state is never a stale `HEALTHY`.

### Desired state and drift

Desired state is deliberately minimal: `image` and `replicas`. A field left null is not managed and never reports drift, so an application can have its replica count governed while its image is left to whatever is deployed.

```json
PUT /v1/applications/{id}/desired-state
{ "image": "ghcr.io/golias/task-manager:1.4.2", "replicas": 2 }
```

`GET /v1/applications/{id}/diff` then answers:

```json
{
  "name": "task-manager",
  "namespace": "production",
  "status": "DRIFTED",
  "desired": { "image": "ghcr.io/golias/task-manager:1.4.2", "replicas": 2 },
  "observed": { "image": "ghcr.io/golias/task-manager:1.4.1", "replicas": 1 },
  "drift": ["IMAGE", "REPLICAS"],
  "observedAt": "2026-11-03T10:15:30Z"
}
```

`status` separates the four cases a bare drift list cannot:

| Status | Meaning |
| --- | --- |
| `IN_SYNC` | Every managed field matches what is running. |
| `DRIFTED` | At least one managed field differs; `drift` names which. |
| `UNMANAGED` | No desired state declared, so there is nothing to compare. |
| `UNOBSERVED` | A spec is declared but no Deployment is visible, so drift is unknown rather than absent. |

The observed side is read by the same `ObservedSpecs` rule used for `ApplicationHealth.image` and for `DEPLOY`, so health, drift and deployment never disagree about which container is the application's.

**Nothing is reconciled.** The diff is reporting only, and `SYNC` remains unimplemented. Declaring a desired state does not change the cluster; closing the loop needs its own safety rules first.

## Operations API

Interactive API documentation is available at `GET /swagger-ui/index.html`; the
OpenAPI document is available at `GET /v3/api-docs`.

Operations use `POST /v1/operations` with a JSON body:

```json
{
  "namespace": "kube-system",
  "application": "traefik",
  "type": "RESTART"
}
```

Scaling requires `replicas`:

```json
{
  "namespace": "kube-system",
  "application": "traefik",
  "type": "SCALE",
  "replicas": 3
}
```

Deploying an image requires `image`:

```json
{
  "namespace": "kube-system",
  "application": "traefik",
  "type": "DEPLOY",
  "image": "traefik:v3.0"
}
```

The request returns `202 Accepted` with an operation record. Processing happens asynchronously in a virtual thread. Poll `GET /v1/operations/{id}` to follow the operation through `PENDING`, `RUNNING`, `COMPLETED` or `ERROR`.

`RESTART`, `SCALE` and `DEPLOY` are confirmed, not fire-and-forget. Each patch returns the Deployment generation it produced, and the operation stays `RUNNING` until that exact revision has been observed with every replica ready. Confirming the generation from the patch response rather than reading it beforehand is what stops a rollout being accepted against the previous revision.

An operation that does not converge within `control.hub.operations.rollout-timeout` is recorded as `ERROR` with the observed reasons, so the record explains the failure:

```
Rollout of production/api did not become ready within 180s: REPLICA_NOT_READY
Deployment/production/api (1 of 2 replicas ready); POD_CRASH_LOOP
Pod/production/api-7f/api (Container repeatedly fails to start)
```

The wait polls the observed state the informers already maintain rather than the API server, so it costs no extra API traffic. It fails immediately when the informer caches have not synced, rather than timing out misleadingly.

Before accepting an operation, Control Hub validates that the application is registered, that its Kubernetes state has been discovered, that the namespace and application are both allowed, and that no other operation is active for the same application. Operations are persisted with their type, status, request time, start time, completion time and error message. Validation and Kubernetes execution failures are recorded as `ERROR`.

`ROLLBACK` accepts a `revision` field. The request and operation history are modelled, but applying Kubernetes revision history still needs to be completed. `SYNC` likewise requires a desired-state source to be connected.

## Observability

Actuator endpoints are exposed for health and metrics:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

Health is also split into two probe groups, so Kubernetes can route traffic without restarting the pod when a dependency is briefly unavailable:

- `GET /actuator/health/liveness` — the process itself is healthy. It tracks the application availability state only, so an unreachable database or a slow cluster never causes a restart loop.
- `GET /actuator/health/readiness` — the process can serve requests. It requires the database and `kubernetesInformers`, the contributor backed by `KubernetesResourceInformers.hasSynced()`.

`kubernetesInformers` is `DOWN` until every informer cache has synced, which keeps the pod out of the Service during startup instead of letting requests read an empty observed state. Defining a group replaces Spring Boot's default membership rather than extending it, so `readinessState` and `livenessState` are listed explicitly.

Reconciliation metrics cover queue depth, enqueue outcomes, reconciliation duration, retries, worker state and informer synchronization. Operation metrics cover request counts and duration by operation and outcome. See [`docs/reconciliation-metrics.md`](docs/reconciliation-metrics.md) for metric names and PromQL examples.

## Running locally

Requirements:

- Java 25
- Docker, for PostgreSQL and a Kubernetes cluster if you want live reconciliation
- Access to a Kubernetes configuration visible to the Kubernetes Java client

Start local dependencies and the application with:

```bash
docker compose up -d
./mvnw spring-boot:run
```

Operation behaviour is configured with:

| Property | Default | Meaning |
| --- | --- | --- |
| `control.hub.operations.allowed-namespaces` | `*` | Comma-separated namespaces that may be operated on; `*` allows every namespace. |
| `control.hub.operations.allowed-applications` | `*` | Comma-separated application names that may be operated on. |
| `control.hub.operations.rollout-timeout` | `180s` | How long an operation waits for its revision to become ready before failing. |
| `control.hub.operations.rollout-poll-interval` | `1s` | How often the observed state is checked while waiting. |
| `control.hub.applications.status-events.retention` | `14d` | How long health transitions are kept before being pruned. |
| `control.hub.applications.status-events.purge-interval` | `1h` | How often the pruning job runs. |
| `control.hub.applications.status-events.max-returned` | `50` | Hard cap on transitions returned by the events endpoint. |

Liquibase creates and updates the PostgreSQL schema on startup. Local database settings are in `src/main/resources/application.properties`; use environment-specific configuration elsewhere.

Run tests with:

```bash
./mvnw test
```

## Current boundaries

The current deployment is designed for one active Control Hub pod. The informer caches, reconciliation queue and observed-state store are local to the process. Running multiple pods requires shared observed state, a durable queue or leader election, and idempotent coordination for database registration and operations.

Desired state is declared through the API and compared on demand. Automatic reconciliation is the next control-plane step and is deliberately absent: `SYNC` stays unimplemented, and applying a diff should only become a workflow once its safety rules, approval model and rollback path are defined. `ROLLBACK` still needs Kubernetes revision history.
