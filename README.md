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

It also reports explicit reasons such as `POD_CRASH_LOOP`, `REPLICA_NOT_READY`, `IMAGE_PULL_ERROR`, `ROLLOUT_IN_PROGRESS` and `OBSERVED_STATE_UNAVAILABLE`.

## Operations API

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

Before accepting an operation, Control Hub validates that the application is registered, that its Kubernetes state has been discovered, that the namespace is allowed, and that no other operation is active for the same application. Operations are persisted with their type, status, request time, start time, completion time and error message. Validation and Kubernetes execution failures are recorded as `ERROR`.

`ROLLBACK` accepts a `revision` field. The request and operation history are modelled, but applying Kubernetes revision history still needs to be completed. `SYNC` likewise requires a desired-state source to be connected.

## Observability

Actuator endpoints are exposed for health and metrics:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

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

Liquibase creates and updates the PostgreSQL schema on startup. Local database settings are in `src/main/resources/application.properties`; use environment-specific configuration elsewhere.

Run tests with:

```bash
./mvnw test
```

## Current boundaries

The current deployment is designed for one active Control Hub pod. The informer caches, reconciliation queue and observed-state store are local to the process. Running multiple pods requires shared observed state, a durable queue or leader election, and idempotent coordination for database registration and operations.

Desired state is the next control-plane step: introduce the desired representation and diff workflow before applying changes automatically. `SYNC`, `DEPLOY` and `ROLLBACK` should only become fully mutating workflows once that source and its safety rules are defined.
