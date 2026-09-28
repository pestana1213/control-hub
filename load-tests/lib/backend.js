import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

import { MONITOR_INTERVAL, PROMETHEUS_URL } from './config.js';

/*
 * A single low-weight VU scrapes /actuator/prometheus while the load runs, so
 * the report can answer "did latency rise because Control Hub got slower, or
 * because the reconciler fell behind?".
 *
 * Scrapes are tagged endpoint=backend-metrics, which keeps them out of the
 * http_req_duration / http_req_failed thresholds for the endpoint under test.
 */

export const backendQueueSize = new Gauge('control_hub_backend_reconciliation_queue_size');
export const backendQueueEnqueueFull = new Counter('control_hub_backend_queue_enqueue_rejected');
export const backendInformersSynced = new Gauge('control_hub_backend_informers_synced');
export const backendFailures = new Counter('control_hub_backend_failure_total', 'method');
export const backendExceptions = new Counter('control_hub_backend_exceptions_total', 'type');
export const backendServerRequests = new Counter('control_hub_backend_server_requests_total', 'uri');
export const backendHeapUsed = new Gauge('control_hub_backend_jvm_heap_used_bytes');
export const backendGcPause = new Trend('control_hub_backend_jvm_gc_pause_seconds', true);
export const backendThreadsLive = new Gauge('control_hub_backend_jvm_threads_live');
export const backendScrapeFailed = new Rate('control_hub_backend_scrape_failed');
export const backendScrapeDuration = new Trend('control_hub_backend_scrape_duration', true);

/** Micrometer names as they appear in the Prometheus text format. */
const PROM_METRICS = {
  queueSize: 'control_hub_reconciliation_queue_size',
  enqueueRejected: 'control_hub_reconciliation_queue_enqueue_total',
  informersSynced: 'control_hub_reconciliation_informers_synced',
  failures: 'control_hub_failure_total',
  exceptions: 'control_hub_exceptions_total',
  serverRequests: 'http_server_requests_seconds_count',
  heapUsed: 'jvm_memory_used_bytes',
  gcPause: 'jvm_gc_pause_seconds_max',
  threadsLive: 'jvm_threads_live',
};

const labelRegexCache = {};

/**
 * Sums every sample of `metric` whose label block satisfies `labelFilter`.
 * Returns null when the metric is absent, so callers can skip silently
 * instead of recording a misleading zero.
 */
export function sumSamples(text, metric, labelFilter) {
  if (labelRegexCache[metric] === undefined) {
    labelRegexCache[metric] = new RegExp('^' + metric + '(\\{([^}]*)\\})?[ \\t]+([0-9eE+.-]+)[ \\t]*$');
  }
  const re = labelRegexCache[metric];
  const lines = text.split('\n');
  let total = null;

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.charAt(0) === '#' || line.indexOf(metric) !== 0) {
      continue;
    }
    const m = re.exec(line);
    if (m === null) {
      continue;
    }
    const labels = m[2] || '';
    if (labelFilter && !labelFilter(labels)) {
      continue;
    }
    const value = parseFloat(m[3]);
    if (!isFinite(value)) {
      continue;
    }
    total = (total === null ? 0 : total) + value;
  }

  return total;
}

const hasLabel = (labels, key, value) =>
  labels.indexOf(key + '="' + value + '"') !== -1;

/*
 * Prometheus counters are cumulative, so each scrape has to be turned into a
 * delta before it is handed to a k6 Counter. The monitor runs as a single VU,
 * so this map keeps the previous value across iterations of that one runtime.
 * A counter that resets (Control Hub restarted mid-run) is treated as a jump
 * from zero rather than being reported as a negative delta.
 */
const lastSeen = {};

function recordDelta(key, cumulative, metric, labels) {
  if (cumulative === null) {
    return null;
  }
  const previous = lastSeen[key];
  lastSeen[key] = cumulative;

  if (previous === undefined) {
    // First scrape establishes the baseline; nothing to count yet.
    return cumulative;
  }
  const delta = cumulative - previous;
  if (delta < 0) {
    return 0;
  }
  metric.add(delta, labels);
  return delta;
}

/** Max across samples of `metric`; used for the GC pause high-water mark. */
export function maxSamples(text, metric, labelFilter) {
  const lines = text.split('\n');
  let best = null;

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.charAt(0) === '#' || line.indexOf(metric) !== 0) {
      continue;
    }
    const m = new RegExp('^' + metric + '(\\{([^}]*)\\})?[ \\t]+([0-9eE+.-]+)[ \\t]*$').exec(line);
    if (m === null) {
      continue;
    }
    if (labelFilter && !labelFilter(m[2] || '')) {
      continue;
    }
    const value = parseFloat(m[3]);
    if (isFinite(value) && (best === null || value > best)) {
      best = value;
    }
  }

  return best;
}

/** One scrape. Split out so setup() can reuse it. */
export function scrapeBackend() {
  const res = http.get(PROMETHEUS_URL, {
    tags: { endpoint: 'backend-metrics', name: 'GET /actuator/prometheus' },
    timeout: '10s',
  });

  backendScrapeDuration.add(res.timings.duration);

  if (res.status !== 200 || !res.body) {
    backendScrapeFailed.add(true);
    return null;
  }

  backendScrapeFailed.add(false);
  const text = res.body;

  const queue = sumSamples(text, PROM_METRICS.queueSize);
  if (queue !== null) {
    backendQueueSize.add(queue);
  }

  const rejected = sumSamples(text, PROM_METRICS.enqueueRejected, (l) => hasLabel(l, 'result', 'full'));
  recordDelta('enqueueRejected', rejected, backendQueueEnqueueFull);

  const synced = sumSamples(text, PROM_METRICS.informersSynced);
  if (synced !== null) {
    backendInformersSynced.add(synced);
  }

  const registryFailures = sumSamples(text, PROM_METRICS.failures, (l) =>
    hasLabel(l, 'method', 'getRegisteredApplications') || hasLabel(l, 'method', 'enrichWithK8sInfo'),
  );
  recordDelta('registryFailures', registryFailures, backendFailures, { method: 'applications_read' });

  const appExceptions = sumSamples(text, PROM_METRICS.exceptions, (l) =>
    hasLabel(l, 'type', 'application'),
  );
  recordDelta('appExceptions', appExceptions, backendExceptions, { type: 'application' });

  const serverCalls = sumSamples(text, PROM_METRICS.serverRequests, (l) =>
    hasLabel(l, 'uri', '/v1/applications'),
  );
  recordDelta('serverCalls', serverCalls, backendServerRequests, { uri: '/v1/applications' });

  const heap = sumSamples(text, PROM_METRICS.heapUsed, (l) => hasLabel(l, 'area', 'heap'));
  if (heap !== null) {
    backendHeapUsed.add(heap);
  }

  const gc = maxSamples(text, PROM_METRICS.gcPause);
  if (gc !== null) {
    backendGcPause.add(gc);
  }

  const threads = sumSamples(text, PROM_METRICS.threadsLive);
  if (threads !== null) {
    backendThreadsLive.add(threads);
  }

  return res;
}

export function monitorBackend() {
  scrapeBackend();
  sleep(MONITOR_INTERVAL);
}
