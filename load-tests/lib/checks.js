import { check } from 'k6';

/*
 * Contract checks for GET /v1/applications.
 *
 * The payload is List<ApplicationDTO>:
 *   { name, namespace, description, repositoryUrl,
 *     desiredPods, healthyPods,
 *     pods: [{ name, status, phase, restarts }],
 *     ingresses: [{ name, urls: [] }],
 *     events: [{ type, reason, message, count, ... }],
 *     health: { status, reasons: [{ code, resource, message }],
 *               desiredReplicas, readyReplicas } }
 *
 * GetApplications.enrichWithK8sInfo fills the flat counters straight from the
 * evaluated health, so desiredPods/healthyPods must mirror
 * health.desiredReplicas/health.readyReplicas. That agreement is a real
 * invariant of the read model, so it is asserted below.
 */

export const VALID_APP_STATUS = ['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN'];
export const VALID_POD_STATUS = ['HEALTHY', 'UNHEALTHY', 'UNKNOWN'];
export const VALID_REASON_CODES = [
  'POD_CRASH_LOOP',
  'REPLICA_NOT_READY',
  'IMAGE_PULL_ERROR',
  'ROLLOUT_IN_PROGRESS',
  'OBSERVED_STATE_UNAVAILABLE',
];

const isNonEmptyString = (v) => typeof v === 'string' && v.length > 0;
const isInt = (v) => typeof v === 'number' && isFinite(v);
const label = (app) => (app && app.namespace ? app.namespace + '/' + app.name : JSON.stringify(app));

/*
 * Each rule is a visit function that returns a string describing the first
 * problem it finds, or null when the element is fine. Returning the reason
 * rather than a bare boolean is what lets a failure be reported as something
 * actionable instead of just a name.
 */
const RULES = [
  {
    name: 'every application has a name and namespace',
    deep: false,
    visit: (app) =>
      app && isNonEmptyString(app.name) && isNonEmptyString(app.namespace)
        ? null
        : 'application ' + label(app) + ' is missing a name or namespace',
  },
  {
    name: 'every application exposes a health block',
    deep: false,
    visit: (app) =>
      app && app.health && isNonEmptyString(app.health.status)
        ? null
        : 'application ' + label(app) + ' has no health status',
  },
  {
    name: 'health status is a known value',
    deep: false,
    visit: (app) =>
      app && app.health && VALID_APP_STATUS.indexOf(app.health.status) !== -1
        ? null
        : 'application ' + label(app) + ' reports health "' +
            ((app && app.health && app.health.status) || 'missing') +
            '"',
  },
  {
    name: 'pod, ingress and event collections are arrays',
    deep: false,
    visit: (app) =>
      app && Array.isArray(app.pods) && Array.isArray(app.ingresses) && Array.isArray(app.events)
        ? null
        : 'application ' + label(app) + ' has a null or non-list collection',
  },
  {
    name: 'pod status is a known value',
    deep: true,
    visit: (app) => {
      if (!app || !Array.isArray(app.pods)) {
        return null;
      }
      for (let i = 0; i < app.pods.length; i += 1) {
        const pod = app.pods[i];
        if (!pod || VALID_POD_STATUS.indexOf(pod.status) === -1) {
          return 'pod ' + ((pod && pod.name) || i) + ' in ' + label(app) + ' reports status "' +
            ((pod && pod.status) || 'missing') + '"';
        }
      }
      return null;
    },
  },
  {
    name: 'pod entries are complete',
    deep: true,
    visit: (app) => {
      if (!app || !Array.isArray(app.pods)) {
        return null;
      }
      for (let i = 0; i < app.pods.length; i += 1) {
        const pod = app.pods[i];
        if (!pod || !isNonEmptyString(pod.name) || !isNonEmptyString(pod.phase) || !isInt(pod.restarts)) {
          return 'pod ' + ((pod && pod.name) || i) + ' in ' + label(app) + ' is missing a name, phase or restarts count';
        }
      }
      return null;
    },
  },
  {
    name: 'replica counters agree with health',
    deep: true,
    visit: (app) => {
      if (!app || !app.health) {
        return null;
      }
      if (app.desiredPods !== app.health.desiredReplicas || app.healthyPods !== app.health.readyReplicas) {
        return 'application ' + label(app) + ' reports ' + app.desiredPods + '/' + app.healthyPods +
          ' pods but health says ' + app.health.desiredReplicas + '/' + app.health.readyReplicas;
      }
      return null;
    },
  },
  {
    name: 'health reasons carry known codes',
    deep: true,
    visit: (app) => {
      const reasons = (app && app.health && app.health.reasons) || [];
      for (let i = 0; i < reasons.length; i += 1) {
        if (!reasons[i] || VALID_REASON_CODES.indexOf(reasons[i].code) === -1) {
          return 'application ' + label(app) + ' carries reason code "' +
            ((reasons[i] && reasons[i].code) || 'missing') + '"';
        }
      }
      return null;
    },
  },
  {
    name: 'ingress urls are arrays of strings',
    deep: true,
    visit: (app) => {
      if (!app || !Array.isArray(app.ingresses)) {
        return null;
      }
      for (let i = 0; i < app.ingresses.length; i += 1) {
        const ing = app.ingresses[i];
        if (!ing || !isNonEmptyString(ing.name) || !Array.isArray(ing.urls)) {
          return 'ingress ' + ((ing && ing.name) || i) + ' in ' + label(app) + ' has no name or url list';
        }
      }
      return null;
    },
  },
];

/*
 * k6's end-of-test summary only reports the pass rate of `checks`, not which
 * assertions failed, and this suite replaces that summary with its own
 * report. Tracking the first occurrence of each distinct failure here is what
 * keeps a broken contract diagnosable.
 *
 * Module state lives in a single VU's runtime, so each VU logs a given rule at
 * most once and a long run does not drown in duplicates.
 */
const reported = {};

function reportOnce(rule, problem) {
  if (reported[rule]) {
    return;
  }
  reported[rule] = true;
  console.error('CHECK FAILED: ' + rule + ' -> ' + problem);
}

/** Applies the rules to one parsed response body. */
function applyRules(apps, deep) {
  let failures = 0;

  for (let r = 0; r < RULES.length; r += 1) {
    const rule = RULES[r];
    if (rule.deep && !deep) {
      continue;
    }

    let problem = null;
    for (let i = 0; i < apps.length; i += 1) {
      const found = rule.visit(apps[i]);
      if (found !== null) {
        problem = found;
        break;
      }
    }

    const passed = problem === null;
    if (!passed) {
      failures += 1;
      reportOnce(rule.name, problem);
    }

    const assertion = {};
    assertion[rule.name] = function () {
      return passed;
    };
    check(null, assertion);
  }

  return failures;
}

/**
 * Runs the checks for one response.
 * Returns null when the body could not be used, so the caller can skip
 * metric recording. Never throws.
 */
export function checkApplications(res, deep) {
  check(res, {
    'status is 200': (r) => r.status === 200,
    'content type is JSON': (r) =>
      String(r.headers['Content-Type'] || '').indexOf('application/json') === 0,
  });

  if (res.status !== 200) {
    return null;
  }

  let apps;
  try {
    apps = res.json();
  } catch (e) {
    console.error('CHECK FAILED: body parses as JSON -> ' + String(res.body || '').slice(0, 120));
    check(null, { 'body parses as JSON': function () { return false; } });
    return null;
  }

  if (!Array.isArray(apps)) {
    console.error(
      'CHECK FAILED: body is a JSON array -> got ' + (apps === null ? 'null' : typeof apps),
    );
    check(null, { 'body is a JSON array': function () { return false; } });
    return null;
  }

  check(null, { 'body is a JSON array': function () { return true; } });

  applyRules(apps, deep);

  const totals = { pods: 0, ingresses: 0, events: 0, apps: apps.length };
  const statuses = {};

  for (let i = 0; i < apps.length; i += 1) {
    const app = apps[i];
    totals.pods += (app.pods || []).length;
    totals.ingresses += (app.ingresses || []).length;
    totals.events += (app.events || []).length;
    const status = (app.health && app.health.status) || 'MISSING';
    statuses[status] = (statuses[status] || 0) + 1;
  }

  return { apps: apps, totals: totals, statuses: statuses };
}

/**
 * Turns a failed response into something actionable.
 *
 * The endpoint reports problems as RFC 7807 ProblemDetail. The titles and
 * details come from GlobalExceptionHandler, and the messages below are the
 * ones this read path can actually produce, so the hints are keyed off those
 * rather than off an HTTP status alone.
 */
export function explainFailure(res) {
  const lines = [];
  let title = '';
  let detail = '';

  try {
    const body = res.json();
    if (body && typeof body === 'object') {
      title = body.title || '';
      detail = body.detail || '';
      lines.push('HTTP ' + res.status + (title ? ' ' + title : ''));
      if (detail) {
        lines.push('detail: ' + detail);
      }
    } else {
      lines.push('HTTP ' + res.status);
    }
  } catch (e) {
    lines.push('HTTP ' + res.status + ' (non-JSON body)');
  }

  const message = title + ' ' + detail;

  if (message.indexOf('Failed to enrich with kubernetes info') !== -1) {
    lines.push('');
    lines.push('GetApplications.enrichWithK8sInfo threw while building the response.');
    lines.push('It fails when observedState.get(...) returns nothing for an application');
    lines.push('that is registered in the database, so the reconciler has not produced a');
    lines.push('snapshot for it yet. Check, in order:');
    lines.push('  1. Control Hub can reach a cluster (KUBECONFIG is set for local runs).');
    lines.push('  2. /actuator/prometheus -> control_hub_reconciliation_informers_synced == 1');
    lines.push('  3. The reconciler is draining its queue: control_hub_reconciliation_queue_size');
    lines.push('     should be near 0 and falling.');
    lines.push('  4. A registered application still exists in the cluster. Rows are removed');
    lines.push('     when their workload disappears, so a stale row with no matching');
    lines.push('     Deployment produces exactly this error.');
  } else if (message.indexOf('Failed to get registered applications') !== -1) {
    lines.push('');
    lines.push('Reading the application registry failed. This is the database path, not');
    lines.push('Kubernetes: ApplicationRepo.findAll() threw. Check the Postgres connection');
    lines.push('and the Liquibase schema.');
  } else if (message.indexOf('Kubernetes unavailable') !== -1) {
    lines.push('');
    lines.push('The Kubernetes client could not reach the API server.');
    lines.push('Confirm the cluster context and credentials are still valid.');
  } else if (res.status === 404) {
    lines.push('');
    lines.push('No controller is mapped to /v1/applications. Is this Control Hub?');
  } else if (res.status === 401 || res.status === 403) {
    lines.push('');
    lines.push('The endpoint is reachable but refused the request. If a security layer');
    lines.push('was added in front of Control Hub, the test needs the right headers.');
  } else if (lines.length <= 2) {
    lines.push('');
    lines.push('Body: ' + String(res.body || '').slice(0, 500));
  }

  return lines.join('\n');
}
