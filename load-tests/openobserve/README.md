# OpenObserve dashboard for v1/applications

`v1-applications-dashboard.json` is an OpenObserve dashboard covering the read
path behind `GET /v1/applications` and the saturation signals that explain it.

## Import

In OpenObserve: **Dashboards -> Import** and select the JSON file.

Three tabs:

| Tab | What it answers |
| --- | --- |
| Load test | How hard is the endpoint being hit, and is it keeping up? |
| Saturation | Is the reconciler keeping up, or is it the bottleneck? |
| JVM and resources | Is the process itself running out of anything? |

A `namespace` variable filters every panel, defaulting to `control-hub`.

## Panel layout is a 192-column grid

Each panel's `layout` (`x`, `y`, `w`, `h`) is handed straight to GridStack
without any rescaling, and OpenObserve initialises that grid with
`column: 192`, `cellHeight: 17px`. A layout authored against a 96-unit grid —
two panels of `w: 48` at `x: 0` and `x: 48` — renders in the **left half of the
viewport** with the right half empty, which looks like a rendering bug but is
just coordinates that never reach column 192.

The panels here therefore use `w: 96` at `x: 0` and `x: 96`: two per row,
filling the full width. Minimums enforced by OpenObserve are `minW: 12` and
`minH: 8` for chart panels, so a `w` below 12 will not survive a drag-resize.

## Two things to know before you trust it

### `control_hub_application_register_total` does not exist

The existing Overview dashboard has an **Application registrations** panel
built on `control_hub_application_register_total`. I grepped the whole codebase
for it and found nothing: no `Counter`, no builder, no string literal. There is
no code path in Control Hub that emits that metric, so that panel is always
empty regardless of the PromQL being correct.

Registration happens in `KubernetesReconciler`, which does not touch a
`MeterRegistry` at all. If you want that metric, it needs a counter added
there. Everything else on the Overview dashboard checks out against the code.

### p95 and p99 are not available without a config change

The `v1/applications p95` panel queries
`http_server_requests_seconds_bucket`, and it will be empty.

I verified this against the project's own Micrometer registry: timers created
without `publishPercentileHistogram()` publish only `_count`, `_sum` and
`_max`. No `_bucket` series is produced. This is why the mean-latency panel
uses `sum / count` instead of a quantile.

For HTTP request timers specifically, Spring Boot needs this in
`application.properties`:

```properties
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

Without it, mean and max are the only true latency figures available
server-side. For precise percentiles, use the k6 client-side numbers, which
are in the load test report and are what the SLO thresholds actually assert on.

Note that `control_hub.operation.duration` and
`control_hub.kubernetes.operation.duration` already call
`publishPercentileHistogram()`, so those two *do* have buckets. The
`@Timed` annotations in `GetApplications` and `KubernetesApplicationDiscovery`
do not.

## Metric names were verified, not guessed

Every `control_hub_*` name in this dashboard was produced by registering the
same meter against `PrometheusMeterRegistry` with the exact name and tags from
the source, then reading the scrape output. That is how the dotted Micrometer
names get confirmed against the underscored Prometheus names, for example:

```
control_hub.reconciliation.worker.retries  ->  control_hub_reconciliation_worker_retries_total
control_hub.reconciliation.queue.size      ->  control_hub_reconciliation_queue_size
```

Standard JVM, HikariCP and `http_server_requests` names are Spring Boot and
Micrometer defaults.

Two panels depend on optional binders. If they come back empty, the metric
never existed rather than the PromQL being wrong:

- `jvm_gc_memory_allocated_bytes_total` and `jvm_gc_pause_seconds_sum` come
  from the GC binder, on by default in recent Boot versions.
- `hikaricp_*` requires a pooled datasource. This project uses HikariCP, so it
  should be there.

## How to read it during a run

The panels are arranged so the interesting comparison is visual rather than
deduced.

**Request rate up, latency flat** — the endpoint is absorbing more load. This
is the healthy case.

**Latency up, reconciliation queue flat** — the bottleneck is inside the
request. Go to the JVM tab: GC pauses and allocation rate rising points at
`enrichWithK8sInfo`, which allocates a DTO per application per request.

**Latency up, reconciliation queue climbing, `enqueues rejected: full`
non-zero** — the reconciler is losing work. `full` means the queue hit its
capacity limit and dropped keys, so the informer cache will go stale and
reads will start returning 500s from `enrichWithK8sInfo`. This is the failure
mode the read path is most exposed to.

**Database pool panel saturated while latency rises** — worth ruling out
early. `GetApplications` calls `applicationRepo.findAll()` on every single
request with no caching or pagination, so connection pressure scales directly
with request rate.

**Heap climbing steadily across a soak run with no GC relief** — retention
problem rather than allocation rate. That would be a genuine finding about
the read path.

## Pairing it with the load tests

This dashboard shows the server's view. The k6 report shows the client's view,
including payload size and per-pod detail that the server cannot see. Useful
together, because they fail differently: a k6 failure with a healthy dashboard
usually means a contract or payload problem, while a dashboard alert with a
green k6 run means something outside the endpoint is degrading.
