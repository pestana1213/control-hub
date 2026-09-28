/*
 * Turns the k6 summary into a report aimed at this endpoint: what the payload
 * looked like, how it behaved, and what Control Hub was doing on the other
 * side while it happened.
 *
 * Summary layouts differ between k6 versions, so every lookup is defensive.
 * A metric that cannot be resolved is reported as "n/a" instead of throwing,
 * and the run itself has already finished by the time this code runs.
 */

// Writing artefacts is best effort. k6 releases that bundle the Node
// compatibility layer expose fs here; newer ones do not, and their supported
// route is the --summary-export flag, so the report degrades to stdout.
const fs = (() => {
  try {
    return require('fs');
  } catch (e) {
    return null;
  }
})();

import {
  BASE_URL,
  DEEP_CHECKS,
  ERROR_STATUSES,
  HEALTH_STATUSES,
  MIN_APPLICATIONS,
  MONITOR_ENABLED,
  PEAK_VUS,
  PROFILE,
  RESULTS_DIR,
  SLO_ERROR_RATE,
  SLO_P95_MS,
  SLO_P99_MS,
  VUS,
  errorMetric,
  healthMetric,
} from './config.js';

/* ------------------------------------------------------------------ *
 * Summary access
 *
 * k6 has shipped two summary layouts: a flat {name: {stat: value}} map and a
 * richer {name: {values: {stat: value}, type}}. index() and values() accept
 * both so this report keeps working across versions.
 * ------------------------------------------------------------------ */

function index(data) {
  if (!data) {
    return {};
  }
  if (data.data && typeof data.data === 'object') {
    return data.data;
  }
  if (data.metrics && typeof data.metrics === 'object') {
    return data.metrics;
  }
  return {};
}

/** Normalises the possible summary value layouts down to a plain map. */
function values(e) {
  if (!e) {
    return null;
  }
  const v = e.values;
  if (Array.isArray(v)) {
    const map = {};
    for (let i = 0; i < v.length; i += 1) {
      const item = v[i];
      if (item && item.field !== undefined) {
        map[item.field] = item.value;
      }
    }
    return Object.keys(map).length > 0 ? map : null;
  }
  if (v && typeof v === 'object') {
    return v;
  }
  return e;
}

function stat(metrics, name, key) {
  const v = values(metrics[name]);
  if (!v) {
    return null;
  }
  const out = v[key];
  return out === undefined ? null : out;
}

/* ------------------------------------------------------------------ *
 * Formatting
 * ------------------------------------------------------------------ */

const kb = (b) => (b === null || b === undefined ? 'n/a' : (b / 1024).toFixed(1) + ' KB');
const ms = (v) => (v === null || v === undefined ? 'n/a' : v.toFixed(1) + ' ms');
const pct = (v) => (v === null || v === undefined ? 'n/a' : (v * 100).toFixed(2) + '%');
const int = (v, fallback) => {
  if (v === null || v === undefined) {
    return fallback === undefined ? 'n/a' : String(fallback);
  }
  return String(Math.round(v));
};

function line(label, value) {
  return '  ' + label.padEnd(32, '.') + ' ' + value;
}

/** Reads a set of counters that differ only by name. */
function countersByName(metrics, names) {
  const rows = [];
  for (let i = 0; i < names.length; i += 1) {
    const count = stat(metrics, names[i], 'count');
    if (count !== null && count > 0) {
      rows.push({ label: names[i], count: count });
    }
  }
  return rows;
}

/* ------------------------------------------------------------------ *
 * Text report
 * ------------------------------------------------------------------ */

function render(data) {
  const m = index(data);

  const requests = stat(m, 'applications_requests', 'count');
  const iterations = stat(m, 'iterations', 'count');
  const dropped = stat(m, 'dropped_iterations', 'count');
  const failedRate = stat(m, 'applications_failed', 'rate');
  const errorCount = stat(m, 'applications_http_errors_total', 'count');
  const checksRate = stat(m, 'checks', 'rate');

  const p50 = stat(m, 'applications_duration', 'med');
  const p90 = stat(m, 'applications_duration', 'p(90)');
  const p95 = stat(m, 'applications_duration', 'p(95)');
  const p99 = stat(m, 'applications_duration', 'p(99)');
  const dmax = stat(m, 'applications_duration', 'max');
  const dAvg = stat(m, 'applications_duration', 'avg');

  const appsAvg = stat(m, 'applications_returned', 'avg');
  const appsMin = stat(m, 'applications_returned', 'min');
  const appsMax = stat(m, 'applications_returned', 'max');
  const payloadAvg = stat(m, 'payload_bytes', 'avg');
  const payloadP95 = stat(m, 'payload_bytes', 'p(95)');
  const payloadMax = stat(m, 'payload_bytes', 'max');
  const podsAvg = stat(m, 'pods_returned', 'avg');
  const ingressesAvg = stat(m, 'ingresses_returned', 'avg');
  const eventsAvg = stat(m, 'events_returned', 'avg');
  const eventsMax = stat(m, 'events_returned', 'max');
  const stableRate = stat(m, 'registry_stable', 'rate');

  const health = countersByName(m, HEALTH_STATUSES.map(healthMetric));
  const errors = countersByName(m, ERROR_STATUSES.map(errorMetric));

  const out = [];
  out.push('');
  out.push('=== v1/applications - profile: ' + PROFILE + ' ===');
  out.push('target: ' + BASE_URL);
  out.push('');

  if (requests === 0) {
    out.push('*** No request reached the endpoint. ***');
    out.push('Nothing below was measured. The preflight in setup() aborts the run when');
    out.push('the target is unhealthy or cannot answer, so look for the error message');
    out.push('above this report; it explains what to fix.');
    out.push('');
  }

  out.push('Traffic');
  out.push(line('requests to the endpoint', int(requests, 0)));
  out.push(line('iterations', int(iterations, 0)));
  out.push(line('dropped iterations', int(dropped, 0)));
  out.push(line('failed requests', pct(failedRate) + ' (' + int(errorCount, 0) + ')'));
  out.push(line('checks passed', pct(checksRate)));
  out.push('');

  out.push('Latency (endpoint under test)');
  out.push(line('avg / median', ms(dAvg) + ' / ' + ms(p50)));
  out.push(line('p90 / p95', ms(p90) + ' / ' + ms(p95)));
  out.push(line('p99 / max', ms(p99) + ' / ' + ms(dmax)));
  out.push('');

  out.push('Payload (per response)');
  out.push(line('applications', int(appsAvg) + ' avg, ' + int(appsMin) + ' to ' + int(appsMax)));
  out.push(line('pods / ingresses', int(podsAvg) + ' / ' + int(ingressesAvg)));
  out.push(line('events', int(eventsAvg) + ' avg, peak ' + int(eventsMax)));
  out.push(line('body size avg', kb(payloadAvg)));
  out.push(line('body size p95 / max', kb(payloadP95) + ' / ' + kb(payloadMax)));
  if (stableRate !== null) {
    out.push(line('count stable per VU', pct(stableRate)));
  }
  if (health.length > 0) {
    const total = health.reduce((sum, r) => sum + r.count, 0) || 1;
    health.sort((a, b) => b.count - a.count);
    out.push(
      line(
        'health mix',
        health
          .map((r) => r.label.replace('applications_health_', '').replace('_total', '') +
            ' ' + ((r.count / total) * 100).toFixed(0) + '%')
          .join(', '),
      ),
    );
  }
  if (errors.length > 0) {
    errors.sort((a, b) => b.count - a.count);
    out.push(
      line(
        'non-200 responses',
        errors.map((r) => r.label.replace('applications_errors_', '').replace('_total', '') + ' x' + r.count).join(', '),
      ),
    );
  }
  out.push('');

  if (MONITOR_ENABLED) {
    const serverCalls = stat(m, 'control_hub_backend_server_requests_total', 'count');
    const queueMin = stat(m, 'control_hub_backend_reconciliation_queue_size', 'min');
    const queueMax = stat(m, 'control_hub_backend_reconciliation_queue_size', 'max');
    const syncedMin = stat(m, 'control_hub_backend_informers_synced', 'min');
    const rejected = stat(m, 'control_hub_backend_queue_enqueue_rejected', 'count');
    const readFailures = stat(m, 'control_hub_backend_failure_total', 'count');
    const heapMin = stat(m, 'control_hub_backend_jvm_heap_used_bytes', 'min');
    const heapMax = stat(m, 'control_hub_backend_jvm_heap_used_bytes', 'max');
    const threadsMin = stat(m, 'control_hub_backend_jvm_threads_live', 'min');
    const threadsMax = stat(m, 'control_hub_backend_jvm_threads_live', 'max');
    const gcMax = stat(m, 'control_hub_backend_jvm_gc_pause_seconds', 'max');
    const scrapeRate = stat(m, 'control_hub_backend_scrape_failed', 'rate');

    out.push('Control Hub under the load');
    out.push(line('server-side calls counted', int(serverCalls)));
    out.push(line('actuator scrape failures', pct(scrapeRate)));
    out.push(
      line(
        'informers synced',
        syncedMin === null ? 'n/a' : syncedMin === 1 ? 'yes' : 'NO, never fully synced',
      ),
    );
    out.push(line('reconciliation queue low / peak', int(queueMin) + ' / ' + int(queueMax)));
    out.push(line('enqueues rejected (full)', int(rejected, 0)));
    out.push(line('read-path failures reported', int(readFailures, 0)));
    out.push(line('jvm heap start / peak', kb(heapMin) + ' / ' + kb(heapMax)));
    out.push(line('jvm threads start / peak', int(threadsMin) + ' / ' + int(threadsMax)));
    out.push(line('gc pause peak', gcMax === null ? 'n/a' : (gcMax * 1000).toFixed(0) + ' ms'));
    out.push('');
  }

  out.push('Thresholds applied');
  if (PROFILE === 'smoke') {
    out.push(line('checks', 'rate == 1, functional run'));
    out.push(line('latency', 'not asserted'));
  } else {
    out.push(line('duration p95', '< ' + SLO_P95_MS + ' ms'));
    out.push(line('duration p99', '< ' + SLO_P99_MS + ' ms'));
    out.push(line('failed requests', 'rate < ' + SLO_ERROR_RATE));
    out.push(line('checks', 'rate > 0.99'));
  }
  if (MIN_APPLICATIONS >= 1) {
    out.push(line('applications returned', 'p95 >= ' + MIN_APPLICATIONS));
  }
  out.push(line('deep checks', DEEP_CHECKS ? 'on' : 'off'));
  if (PROFILE === 'spike') {
    out.push(line('resting / peak VUs', VUS + ' / ' + PEAK_VUS));
  }
  out.push('');

  out.push('How to read this');
  out.push('  Latency is client-side and covers the whole Control Hub request.');
  out.push('  A growing queue with flat latency means the reconciler is behind');
  out.push('  while the read path keeps up; that is usually fine for this endpoint.');
  out.push('  A growing queue together with rising latency, or heap that keeps');
  out.push('  climbing across a soak run, points at the per-request health');
  out.push('  evaluation in GetApplications.enrichWithK8sInfo.');
  out.push('  Fewer server-side calls than client requests means traffic was lost');
  out.push('  or retried before reaching the handler.');
  out.push('  A health mix that drifts towards UNKNOWN under load usually means the');
  out.push('  informer cache is stale rather than that the cluster got worse.');
  out.push('');

  return out.join('\n');
}

/* ------------------------------------------------------------------ *
 * CSV of the headline numbers
 * ------------------------------------------------------------------ */

const CSV_ROWS = [
  ['applications_duration', 'p(95)'],
  ['applications_duration', 'p(99)'],
  ['applications_duration', 'med'],
  ['applications_duration', 'max'],
  ['applications_failed', 'rate'],
  ['applications_requests', 'count'],
  ['checks', 'rate'],
  ['iterations', 'count'],
  ['applications_returned', 'avg'],
  ['pods_returned', 'avg'],
  ['ingresses_returned', 'avg'],
  ['events_returned', 'avg'],
  ['payload_bytes', 'p(95)'],
  ['registry_stable', 'rate'],
  ['applications_http_errors_total', 'count'],
  ['control_hub_backend_server_requests_total', 'count'],
  ['control_hub_backend_reconciliation_queue_size', 'max'],
  ['control_hub_backend_failure_total', 'count'],
  ['control_hub_backend_jvm_heap_used_bytes', 'max'],
];

function renderCsv(data) {
  const m = index(data);
  const rows = ['profile,target,metric,stat,value'];
  for (const row of CSV_ROWS) {
    const value = stat(m, row[0], row[1]);
    rows.push([PROFILE, '"' + BASE_URL + '"', row[0], row[1], value === null ? '' : value].join(','));
  }
  return rows.join('\n') + '\n';
}

/* ------------------------------------------------------------------ *
 * Entry point
 * ------------------------------------------------------------------ */

export function handleSummary(data) {
  let text = render(data);

  const written = [];
  if (fs) {
    try {
      if (fs.existsSync(RESULTS_DIR) === false) {
        fs.mkdirSync(RESULTS_DIR, { recursive: true });
      }
      const stamp = new Date().toISOString().replace(/[:.]/g, '-');
      const base = RESULTS_DIR + '/v1-applications-' + PROFILE + '-' + stamp;
      fs.writeFileSync(base + '.json', JSON.stringify(data, null, 2));
      fs.writeFileSync(base + '.csv', renderCsv(data));
      written.push(base + '.json', base + '.csv');
    } catch (e) {
      text += '\nCould not write artefacts: ' + String(e) + '\n';
    }
  }

  if (written.length > 0) {
    text += '\nArtefacts written:\n  ' + written.join('\n  ') + '\n';
  } else {
    text +=
      '\nThis k6 build cannot write files from the test, so nothing was saved.\n' +
      'Re-run with --summary-export to keep the raw summary as JSON, and\n' +
      '-o csv= to keep the per-request samples.\n';
  }

  return { stdout: text + '\n' };
}
