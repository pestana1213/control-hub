# Reconciliation metrics

Metrics are registered with Micrometer and exposed through the existing `/actuator/prometheus` endpoint. `/actuator/metrics/{name}` uses the Micrometer names below.

| Micrometer name | Type | Meaning |
| --- | --- | --- |
| `control_hub.reconciliation.queue.size` | Gauge | Keys waiting in the queue; excludes work in progress and delayed retries. |
| `control_hub.reconciliation.queue.enqueue` | Counter | Enqueue attempts by `result`: `accepted`, `duplicate`, or `full`. Includes retries. |
| `control_hub.reconciliation.worker.duration` | Timer | Duration and count of worker reconciliation attempts by `result`: `success` or `failure`. Includes database transaction completion. |
| `control_hub.reconciliation.worker.retries` | Counter | Delayed retries scheduled, excluding duplicate timers for the same key. |
| `control_hub.reconciliation.worker.retries.pending` | Gauge | Keys with a delayed retry pending. |
| `control_hub.reconciliation.worker.running` | Gauge | Lifecycle started (1) or stopped (0); may be 1 while waiting for cache sync. |
| `control_hub.reconciliation.informers.synced` | Gauge | All four informer caches have synced (1), or at least one has not (0). |

Tags have fixed values; application names and namespaces are not used as metric tags.
The worker timer excludes queue wait, retry delay, and the scheduled registration job's direct calls to the reconciler. A success means reconciliation returned successfully, including when no matching resources exist. Interruptions thrown from reconciliation count as failed attempts.

Example PromQL:

```promql
# Failed worker attempts per second
rate(control_hub_reconciliation_worker_duration_seconds_count{result="failure"}[5m])

# Mean successful reconciliation duration, in seconds
rate(control_hub_reconciliation_worker_duration_seconds_sum{result="success"}[5m])
/
rate(control_hub_reconciliation_worker_duration_seconds_count{result="success"}[5m])

# Enqueue attempts rejected because the queue was full
increase(control_hub_reconciliation_queue_enqueue_total{result="full"}[5m])
```
