# Load tests

k6 load tests for `GET /v1/applications`.

## Requirements

- k6 on your `PATH` (`brew install k6`, or `docker run --rm -i grafana/k6 run - <script>`)
- A running Control Hub that can reach a Kubernetes cluster

Control Hub answers this endpoint from the informer cache, not from the API
server. If no cluster is reachable the reconciler never produces a snapshot and
the endpoint returns `500`. The preflight below catches that and tells you
instead of filling the report with errors.

## Run it

```bash
# 1. Sanity: is the endpoint up and does the payload match the contract?
k6 run -e PROFILE=smoke load-tests/scripts/v1-applications.js

# 2. Steady state: 10 VUs for 60s. This is the run to calibrate against.
k6 run -e PROFILE=baseline load-tests/scripts/v1-applications.js

# 3. Find the breaking point: 10 VUs, then jump to 100.
k6 run -e PROFILE=spike load-tests/scripts/v1-applications.js

# 4. Soak: 20 VUs for 10 minutes. Look for heap that never comes back down.
k6 run -e PROFILE=soak load-tests/scripts/v1-applications.js
```

Against a Control Hub that is not on localhost:

```bash
k6 run -e PROFILE=baseline -e BASE_URL=http://control-hub:8080 \
  load-tests/scripts/v1-applications.js
```

Against a port-forwarded local instance, which is usually the easiest option:

```bash
kubectl port-forward svc/control-hub 8080:8080
k6 run -e PROFILE=baseline load-tests/scripts/v1-applications.js
```

## What each profile does

| Profile | Shape | Asserts |
| --- | --- | --- |
| `smoke` | 3 VUs, 5 requests each | Response contract only, `checks` must be 100% |
| `baseline` | ramp to 10 VUs, hold 60s | p95, p99, error rate, checks |
| `spike` | 10 VUs, jump to 100, hold, drop back | Same, to see where it breaks |
| `soak` | 20 VUs for 10 minutes | Same, plus heap and GC over time |

`smoke` deliberately does not assert latency. A correctness failure and a
performance failure are different problems, and mixing them makes a green run
hard to trust.

## Options

Anything in `lib/config.js` can be overridden with `-e`.

| Option | Default | Meaning |
| --- | --- | --- |
| `PROFILE` | `smoke` | `smoke`, `baseline`, `spike` or `soak` |
| `BASE_URL` | `http://localhost:8080` | Where Control Hub is listening |
| `VUS` | `10` | Steady VUs for `baseline` and `spike` |
| `DURATION` | `60s` | Length of each steady plateau |
| `PEAK_VUS` | `100` | VU ceiling for `spike` |
| `SOAK_VUS` / `SOAK_DURATION` | `20` / `10m` | Soak shape |
| `THINK_TIME` | `0s` | Pause between requests. See below |
| `SLO_P95_MS` / `SLO_P99_MS` | `500` / `1500` | Latency budget |
| `SLO_ERROR_RATE` | `0.01` | Allowed failure rate |
| `DEEP_CHECKS` | on for `smoke`, `baseline` | Per-pod and per-reason validation |
| `MIN_APPLICATIONS` | `0` | Set to `N` to fail if fewer than `N` come back |
| `MAX_PAYLOAD_KB` | unset | Set to cap response size |
| `MONITOR_ENABLED` | `true` | Scrape `/actuator/prometheus` during the run |
| `MONITOR_INTERVAL` | `5s` | How often to scrape |
| `RESULTS_DIR` | `load-tests/results` | Where artefacts are written |

### THINK_TIME

The default is `0s`, which means each VU sends the next request as soon as the
previous one returns. That measures capacity, and it is the right default for
`spike`.

It is not what a real dashboard does. If you want a realistic read rate, model
it:

```bash
# 10 VUs, one request every 2s each, so roughly 5 requests per second.
k6 run -e PROFILE=baseline -e VUS=10 -e THINK_TIME=2s \
  load-tests/scripts/v1-applications.js
```

## Calibrating the SLO

The default 500 ms p95 is a starting point, not a real budget. Measure first:

```bash
k6 run -e PROFILE=baseline -e SLO_P95_MS=999999 -e SLO_P99_MS=999999 \
  -e SLO_ERROR_RATE=1 load-tests/scripts/v1-applications.js
```

That passes regardless of what the service does, so you get a clean baseline.
Read the p95 and p99 from the report, then set thresholds a little above them:

```bash
k6 run -e PROFILE=baseline -e SLO_P95_MS=<p95 * 1.5> load-tests/scripts/v1-applications.js
```

Tighten from there. Keep in mind this endpoint's cost scales with payload size,
so a budget measured against 4 applications is not a budget for 40.

## Reading the report

The run ends with a plain-text summary instead of the usual k6 table. It is
aimed at this endpoint:

- **Payload** shows the shape of what came back. Body size and pod count are
  the inputs that drive server cost, so a latency change with a payload change
  is not a regression.
- **Health mix** shows the share of `HEALTHY` / `DEGRADED` / `UNHEALTHY` /
  `UNKNOWN`. A drift toward `UNKNOWN` under load means the informer cache is
  stale, not that the cluster got worse.
- **Control Hub under the load** is scraped from `/actuator/prometheus` while
  the test runs. This is the part that explains *why*:
  - `reconciliation queue peak` climbing with flat latency means the
    reconciler is behind while the read path keeps up. The read path is served
    from the cache, so this is often fine.
  - Queue climbing *and* latency climbing points at
    `GetApplications.enrichWithK8sInfo`, which does per-request health
    evaluation over every pod, service, ingress and event.
  - `jvm heap start / peak` is the number to watch on a soak run. A peak that
    does not settle suggests a leak.
  - `server-side calls counted` should roughly match `requests to the
    endpoint`. A shortfall means traffic was lost or retried before reaching
    the handler.

## Keeping results

The run itself ends with a plain-text report on stdout. To keep the data, use
k6's output flags:

```bash
# Raw summary as JSON.
k6 run -e PROFILE=baseline --summary-export results.json \
  load-tests/scripts/v1-applications.js

# Per-request samples as CSV, for a spreadsheet or a chart.
k6 run -e PROFILE=baseline -o csv=results.csv \
  load-tests/scripts/v1-applications.js

# Both.
k6 run -e PROFILE=soak --summary-export summary.json -o json=samples.json \
  load-tests/scripts/v1-applications.js
```

The suite also tries to save a summary and a small CSV into
`load-tests/results/` on its own. That depends on the k6 build exposing the
Node `fs` module, which newer k6 releases do not. If it does not, the report
says so and points you at the flags above. Treat the flags as the reliable
path.

For a time series rather than one aggregate, point k6 at InfluxDB or Grafana
Cloud k6 and use the custom metric names (`applications_duration`,
`payload_bytes`, `pods_returned`, `control_hub_backend_*`).

## Layout

```
load-tests/
  scripts/v1-applications.js   entry point: options, setup preflight, load, report
  lib/config.js                env parsing, profile shapes, thresholds
  lib/checks.js                response contract, failure diagnosis
  lib/backend.js               /actuator/prometheus scraper
  lib/report.js                the end-of-run report
```

## Notes on the endpoint

Two things shape how these tests are written.

`GetApplications.getRegisteredApplications` calls `applicationRepo.findAll()`
on every request. There is no caching and no pagination, so read cost is
proportional to the number of registered applications and grows with the
registry. If latency rises over time while the registry is unchanged, that
query is the first place to look.

`enrichWithK8sInfo` throws `ApplicationException` when the observed state has
no entry for a registered application, which the handler turns into a `500`
for the whole request. A single stale row therefore fails the entire list. The
preflight turns that into an actionable message rather than a wall of errors.
