/*
 * Configuration for the v1/applications load tests.
 *
 * Everything is overridable with `-e KEY=value`, e.g.
 *   k6 run -e PROFILE=baseline -e BASE_URL=http://control-hub:8080 -e VUS=25
 *
 * __ENV is read as a global on purpose. Older k6 releases also export it from
 * the k6 module, newer ones do not, and the global works on every version.
 */

/* ------------------------------------------------------------------ *
 * Env helpers
 * ------------------------------------------------------------------ */

function raw(name, fallback) {
  const v = __ENV[name];
  if (v === undefined || v === null || v === '') {
    return fallback;
  }
  return v;
}

function num(name, fallback) {
  const parsed = parseFloat(raw(name, ''));
  return isFinite(parsed) ? parsed : fallback;
}

function int(name, fallback) {
  return Math.floor(num(name, fallback));
}

function bool(name, fallback) {
  const v = raw(name, null);
  if (v === null) {
    return fallback;
  }
  return ['1', 'true', 'yes', 'on'].indexOf(String(v).toLowerCase()) !== -1;
}

/** Accepts 45, "45", "45s", "500ms", "2m", "1h" and returns seconds. */
export function parseDuration(value, fallback) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  if (typeof value === 'number') {
    return isFinite(value) ? value : fallback;
  }
  const m = String(value).trim().match(/^([0-9]*\.?[0-9]+)\s*(ms|s|m|h)?$/i);
  if (!m) {
    return fallback;
  }
  const amount = parseFloat(m[1]);
  switch ((m[2] || 's').toLowerCase()) {
    case 'ms':
      return amount / 1000;
    case 'm':
      return amount * 60;
    case 'h':
      return amount * 3600;
    default:
      return amount;
  }
}

/* ------------------------------------------------------------------ *
 * Target
 * ------------------------------------------------------------------ */

export const BASE_URL = String(raw('BASE_URL', 'http://localhost:8080')).replace(/\/+$/, '');
export const APPLICATIONS_PATH = String(raw('APPLICATIONS_PATH', '/v1/applications'));
export const APPLICATIONS_URL = BASE_URL + APPLICATIONS_PATH;
export const HEALTH_URL = BASE_URL + '/actuator/health';
export const PROMETHEUS_URL = BASE_URL + '/actuator/prometheus';

export const RESULTS_DIR = String(raw('RESULTS_DIR', 'load-tests/results'));

/* ------------------------------------------------------------------ *
 * Profiles
 * ------------------------------------------------------------------ */

export const PROFILE = String(raw('PROFILE', 'smoke')).toLowerCase();

/** Steady-state VUs for baseline/spike, and the resting level of the spike. */
export const VUS = int('VUS', 10);
/** Length of each steady plateau, in seconds. */
export const DURATION = parseDuration(raw('DURATION', '60s'), 60);
/** VU ceiling for the spike profile. */
export const PEAK_VUS = int('PEAK_VUS', 100);
export const SOAK_VUS = int('SOAK_VUS', 20);
export const SOAK_DURATION = parseDuration(raw('SOAK_DURATION', '10m'), 600);
export const SMOKE_VUS = int('SMOKE_VUS', 3);
export const SMOKE_ITERATIONS = int('SMOKE_ITERATIONS', 5);

/** Pause between iterations. 0 = saturate the endpoint (capacity test). */
export const THINK_TIME = parseDuration(raw('THINK_TIME', '0s'), 0);

/** How often the backend sampler scrapes /actuator/prometheus. */
export const MONITOR_ENABLED = bool('MONITOR_ENABLED', true);
export const MONITOR_INTERVAL = parseDuration(raw('MONITOR_INTERVAL', '5s'), 5);

/** Full per-payload validation (pod statuses, replica counter agreement). */
export const DEEP_CHECKS = bool('DEEP_CHECKS', PROFILE === 'smoke' || PROFILE === 'baseline');

/** When >= 1, the run fails if fewer applications than this come back. */
export const MIN_APPLICATIONS = int('MIN_APPLICATIONS', 0);

/* ------------------------------------------------------------------ *
 * Service level objectives (calibrate against a baseline run)
 * ------------------------------------------------------------------ */

export const SLO_P95_MS = int('SLO_P95_MS', 500);
export const SLO_P99_MS = int('SLO_P99_MS', 1500);
export const SLO_ERROR_RATE = num('SLO_ERROR_RATE', 0.01);
/** Optional payload ceiling in KB; unset means "do not assert on size". */
export const MAX_PAYLOAD_KB = int('MAX_PAYLOAD_KB', 0);

const RAMP_DOWN_SEC = parseDuration(raw('RAMP_DOWN', '5s'), 5);
const RAMP_DOWN = RAMP_DOWN_SEC + 's';

/* ------------------------------------------------------------------ *
 * Metric vocabularies
 *
 * These breakdowns use distinct metric names rather than tags on purpose.
 * k6's end-of-test summary aggregates tagged series into one entry, so a
 * status tag would be unreadable in the report. Raw exports still carry
 * everything, and these names are stable enough to chart directly.
 * ------------------------------------------------------------------ */

/** Health statuses the read model can report, plus the malformed case. */
export const HEALTH_STATUSES = ['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN', 'MISSING'];

/** Response codes worth separating, plus a catch-all. */
export const ERROR_STATUSES = ['400', '404', '429', '500', '503', 'other'];

export const healthMetric = (status) =>
  'applications_health_' + String(status).toLowerCase() + '_total';

export const errorMetric = (status) =>
  'applications_errors_' + String(status) + '_total';

/** Maps an HTTP status code onto one of the buckets above. */
export function errorBucket(status) {
  const asString = String(status);
  return ERROR_STATUSES.indexOf(asString) === -1 ? 'other' : asString;
}

const s = (seconds) => Math.round(seconds) + 's';
const stage = (seconds, target) => ({ duration: s(seconds), target });

const sumStages = (stages) =>
  stages.reduce((total, st) => total + parseDuration(st.duration, 0), 0);

const ramping = (stages) => ({
  executor: 'ramping-vus',
  startVUs: 0,
  stages: stages,
  gracefulRampDown: RAMP_DOWN,
});

/* ------------------------------------------------------------------ *
 * Scenario construction
 * ------------------------------------------------------------------ */

function buildProfile() {
  if (PROFILE === 'smoke') {
    return {
      app: {
        executor: 'per-vu-iterations',
        vus: SMOKE_VUS,
        iterations: SMOKE_ITERATIONS,
        maxDuration: '1m',
        gracefulStop: '10s',
        exec: 'applications',
      },
      appSeconds: 60,
      strict: true,
    };
  }

  if (PROFILE === 'baseline') {
    const stages = [stage(30, VUS), stage(DURATION, VUS), stage(15, 0)];
    return { app: ramping(stages), appSeconds: sumStages(stages), strict: false };
  }

  if (PROFILE === 'spike') {
    // rest -> hold -> jump to peak -> hold -> settle back to rest
    const stages = [
      stage(30, VUS),
      stage(DURATION, VUS),
      stage(10, PEAK_VUS),
      stage(DURATION, PEAK_VUS),
      stage(30, VUS),
      stage(15, 0),
    ];
    return { app: ramping(stages), appSeconds: sumStages(stages), strict: false };
  }

  if (PROFILE === 'soak') {
    const stages = [stage(30, SOAK_VUS), stage(SOAK_DURATION, SOAK_VUS), stage(15, 0)];
    return { app: ramping(stages), appSeconds: sumStages(stages), strict: false };
  }

  throw new Error(
    'Unknown PROFILE "' + PROFILE + '". Use one of: smoke, baseline, spike, soak.',
  );
}

export function buildScenarios() {
  const profile = buildProfile();
  const scenarios = { applications: profile.app };

  if (MONITOR_ENABLED) {
    // Runs alongside the load. Overshoots the load scenario so the final
    // scrape still lands after the last request drains.
    const cover = profile.appSeconds + RAMP_DOWN_SEC + MONITOR_INTERVAL * 2;
    scenarios.backend_monitor = {
      executor: 'constant-vus',
      vus: 1,
      duration: s(cover),
      exec: 'monitorBackend',
      tags: { endpoint: 'backend-metrics' },
    };
  }

  return scenarios;
}

export function isStrict() {
  return buildProfile().strict;
}

/* ------------------------------------------------------------------ *
 * Thresholds
 * ------------------------------------------------------------------ */

export function buildThresholds() {
  /*
   * The thresholds run against Control Hub's own metrics for this endpoint
   * rather than the built-in http_req_* series. Both would work, but the
   * built-ins are aggregated across every request in the process, including
   * the actuator scrapes, and the report has to display exactly what was
   * asserted.
   */
  const thresholds = {
    applications_requests: ['count>=1'],
    checks: [isStrict() ? 'rate==1' : 'rate>0.99'],
  };

  if (!isStrict()) {
    thresholds.applications_duration = ['p(95)<' + SLO_P95_MS, 'p(99)<' + SLO_P99_MS];
    thresholds.applications_failed = ['rate<' + SLO_ERROR_RATE];
  }

  if (MIN_APPLICATIONS >= 1) {
    thresholds.applications_returned = ['p(95)>=' + MIN_APPLICATIONS];
  }

  if (MAX_PAYLOAD_KB > 0) {
    thresholds.payload_bytes = ['p(95)<' + MAX_PAYLOAD_KB * 1024];
  }

  return thresholds;
}
