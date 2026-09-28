import http from 'k6/http';
import { sleep } from 'k6';

import { Counter, Rate, Trend } from 'k6/metrics';

import {
  APPLICATIONS_URL,
  DEEP_CHECKS,
  ERROR_STATUSES,
  HEALTH_STATUSES,
  HEALTH_URL,
  MIN_APPLICATIONS,
  PROFILE,
  THINK_TIME,
  buildScenarios,
  buildThresholds,
  errorBucket,
  errorMetric,
  healthMetric,
} from '../lib/config.js';
import { checkApplications, explainFailure } from '../lib/checks.js';
import { monitorBackend, scrapeBackend } from '../lib/backend.js';
import { handleSummary } from '../lib/report.js';

/*
 * Load tests for GET /v1/applications.
 *
 * The endpoint is a read-only aggregate: one findAll() on Postgres plus, per
 * application, a health evaluation over the pods, services, ingresses and
 * events held in the informer cache. Response size therefore drives cost, so
 * the suite records the payload shape alongside latency instead of assuming
 * every response looks the same.
 *
 *   k6 run -e PROFILE=smoke    load-tests/scripts/v1-applications.js
 *   k6 run -e PROFILE=baseline load-tests/scripts/v1-applications.js
 *   k6 run -e PROFILE=spike    load-tests/scripts/v1-applications.js
 *   k6 run -e PROFILE=soak     load-tests/scripts/v1-applications.js
 */

export const options = {
  scenarios: buildScenarios(),
  thresholds: buildThresholds(),
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: { test: 'v1-applications', profile: PROFILE },
};

/* ------------------------------------------------------------------ *
 * Metrics
 * ------------------------------------------------------------------ */

/*
 * Endpoint-scoped metrics. Control Hub's built-in http_req_* series are
 * aggregated over every request in the process, which includes the actuator
 * scrapes this suite also makes, so latency and error rate are recorded here
 * too, and that is what the thresholds assert on.
 *
 * Payload shape is recorded as trends rather than gauges: a gauge keeps only
 * the last value, which hides the distribution of pods and events across
 * responses and leaves the summary with nothing to average.
 */
const applicationsDuration = new Trend('applications_duration', true);
const applicationsFailed = new Rate('applications_failed');
const applicationsRequests = new Counter('applications_requests');
const applicationsReturned = new Trend('applications_returned');
const podsReturned = new Trend('pods_returned');
const eventsReturned = new Trend('events_returned');
const ingressesReturned = new Trend('ingresses_returned');
const payloadBytes = new Trend('payload_bytes', true);
const registryStable = new Rate('registry_stable');

/** Non-200 responses in total, plus a breakdown by response code. */
const httpErrors = new Counter('applications_http_errors_total');

const healthCounters = {};
for (const status of HEALTH_STATUSES) {
  healthCounters[status] = new Counter(healthMetric(status));
}
const errorCounters = {};
for (const status of ERROR_STATUSES) {
  errorCounters[status] = new Counter(errorMetric(status));
}

/*
 * Per-VU: the application count seen on the previous iteration. Module state
 * lives in the VU's own runtime, so this is a per-VU comparison and never
 * mixes two VUs together.
 */
let previousCount = null;

export function setup() {
  const health = http.get(HEALTH_URL, { tags: { endpoint: 'preflight' }, timeout: '10s' });

  if (health.status !== 200) {
    throw new Error(
      'Preflight failed: GET /actuator/health returned ' +
        health.status +
        '. Control Hub is not serving on BASE_URL. ' +
        'Set -e BASE_URL to the right host and port.',
    );
  }

  try {
    const body = health.json();
    if (body && body.status && body.status !== 'UP') {
      throw new Error(
        'Preflight failed: Control Hub reports health "' +
          body.status +
          '". Fix the reported components before generating load.',
      );
    }
  } catch (e) {
    // An unreadable health body is not worth failing on; the endpoint preflight
    // below is the authoritative gate.
  }

  // Confirm the endpoint can actually serve a payload before the clock starts,
  // so a misconfigured kubeconfig does not produce a wall of meaningless 500s.
  const res = http.get(APPLICATIONS_URL, { tags: { endpoint: 'preflight' }, timeout: '30s' });

  if (res.status !== 200) {
    throw new Error(
      'Preflight failed for GET ' +
        APPLICATIONS_URL +
        '\n\n' +
        explainFailure(res) +
        '\n\nLoad generation aborted; fix the target first, then re-run.',
    );
  }

  let apps;
  try {
    apps = res.json();
  } catch (e) {
    throw new Error('Preflight failed: GET ' + APPLICATIONS_URL + ' did not return valid JSON.');
  }

  if (!Array.isArray(apps)) {
    throw new Error(
      'Preflight failed: GET ' + APPLICATIONS_URL + ' did not return a JSON array.',
    );
  }

  if (apps.length === 0) {
    console.warn(
      'WARNING: the application registry is empty. Results describe an empty ' +
        'read, not your real workload. Register applications by labelling ' +
        'Deployments with app.kubernetes.io/name.',
    );
  } else if (MIN_APPLICATIONS >= 1 && apps.length < MIN_APPLICATIONS) {
    throw new Error(
      'Preflight failed: expected at least ' +
        MIN_APPLICATIONS +
        ' applications, found ' +
        apps.length +
        '.',
    );
  }

  const pods = apps.reduce((total, app) => total + ((app.pods || []).length), 0);
  const events = apps.reduce((total, app) => total + ((app.events || []).length), 0);

  console.log(
    'Preflight OK: ' +
      apps.length +
      ' applications, ' +
      pods +
      ' pods, ' +
      events +
      ' events, ' +
      res.body.length +
      ' bytes for the first response.',
  );

  return { applications: apps.length, payloadBytes: res.body.length };
}

export function applications() {
  const res = http.get(APPLICATIONS_URL, { tags: { endpoint: 'applications' } });

  applicationsRequests.add(1);
  applicationsDuration.add(res.timings.duration);
  applicationsFailed.add(res.status !== 200);

  const result = checkApplications(res, DEEP_CHECKS);

  if (res.status !== 200) {
    httpErrors.add(1);
    errorCounters[errorBucket(res.status)].add(1);
    return;
  }

  payloadBytes.add(res.body.length);

  if (result === null) {
    return;
  }

  applicationsReturned.add(result.totals.apps);
  podsReturned.add(result.totals.pods);
  eventsReturned.add(result.totals.events);
  ingressesReturned.add(result.totals.ingresses);

  for (const status in result.statuses) {
    const name = HEALTH_STATUSES.indexOf(status) === -1 ? 'MISSING' : status;
    healthCounters[name].add(result.statuses[status]);
  }

  // A count that drifts mid-run usually means the reconciler is lagging behind
  // the load, which is worth knowing even when every request succeeds.
  if (previousCount !== null && previousCount !== result.totals.apps) {
    registryStable.add(false);
    console.warn(
      'Application count changed from ' +
        previousCount +
        ' to ' +
        result.totals.apps +
        ' during the run.',
    );
  } else if (previousCount !== null) {
    registryStable.add(true);
  }
  previousCount = result.totals.apps;

  if (THINK_TIME > 0) {
    sleep(THINK_TIME);
  }
}

// The load scenarios run applications() as the default entry point, which is
// what k6 expects; the monitor scenario names its own entry point.
export default applications;

export { monitorBackend };

export { handleSummary };
