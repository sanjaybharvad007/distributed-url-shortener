// k6 load test: creates a pool of links through the API, then hammers the
// redirector with a realistic hot/cold mix and asserts latency + error SLOs.
//
//   k6 run -e BASE_URL=http://localhost:8081 -e API_URL=http://localhost:8080 loadtest/k6/redirect.js
//   k6 run -e BASE_URL=... -e RATE=5000 -e DURATION=5m loadtest/k6/redirect.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const API_URL = __ENV.API_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-key';
const RATE = Number(__ENV.RATE || 1000); // redirects per second
const DURATION = __ENV.DURATION || '2m';
const POOL = Number(__ENV.POOL || 500); // distinct links

const redirectLatency = new Trend('redirect_latency', true);
const cacheMissSuspect = new Counter('redirects_over_20ms');
const rateLimited = new Counter('rate_limited');

export const options = {
  scenarios: {
    redirects: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE / 10),
      maxVUs: Math.max(200, RATE / 2),
      exec: 'redirect',
    },
    unknown_codes: {
      executor: 'constant-arrival-rate',
      rate: Math.max(1, Math.floor(RATE / 50)),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 5,
      maxVUs: 20,
      exec: 'unknown',
    },
  },
  thresholds: {
    // SLOs from docs/ARCHITECTURE.md
    'http_req_duration{scenario:redirects}': ['p(99)<50', 'p(95)<20'],
    'http_req_failed{scenario:redirects}': ['rate<0.001'],
    checks: ['rate>0.999'],
  },
};

export function setup() {
  const codes = [];
  const params = { headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY } };
  for (let i = 0; i < POOL; i++) {
    const res = http.post(
      `${API_URL}/api/v1/urls`,
      JSON.stringify({ longUrl: `https://example.com/load/${Date.now()}/${i}` }),
      params,
    );
    if (res.status === 201 || res.status === 200) {
      codes.push(res.json('shortCode'));
    } else if (res.status === 429) {
      sleep(Number(res.headers['Retry-After'] || 1));
      i--; // retry this slot after backing off
    } else {
      throw new Error(`create failed: ${res.status} ${res.body}`);
    }
  }
  return { codes };
}

export function redirect(data) {
  // Zipf-ish: 80% of traffic to 20% of links.
  const hot = Math.random() < 0.8;
  const idx = hot ? randomIntBetween(0, Math.floor(data.codes.length * 0.2)) : randomIntBetween(0, data.codes.length - 1);
  const code = data.codes[idx];
  const res = http.get(`${BASE_URL}/${code}`, {
    redirects: 0,
    headers: { 'X-Forwarded-For': `10.${randomIntBetween(0, 255)}.${randomIntBetween(0, 255)}.${randomIntBetween(1, 254)}` },
    tags: { name: 'redirect' },
  });
  redirectLatency.add(res.timings.duration);
  if (res.timings.duration > 20) cacheMissSuspect.add(1);
  if (res.status === 429) rateLimited.add(1);
  check(res, {
    'status is 302': (r) => r.status === 302,
    'has Location': (r) => !!r.headers['Location'],
  });
}

export function unknown() {
  const res = http.get(`${BASE_URL}/zz${randomIntBetween(100000, 999999)}`, { redirects: 0, tags: { name: 'unknown' } });
  check(res, { 'unknown is 404': (r) => r.status === 404 });
}

export function handleSummary(data) {
  const p = (m, q) => (data.metrics[m] ? data.metrics[m].values[q].toFixed(2) : 'n/a');
  const line = [
    `redirects: ${data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0}`,
    `p50=${p('redirect_latency', 'p(50)')}ms`,
    `p95=${p('redirect_latency', 'p(95)')}ms`,
    `p99=${p('redirect_latency', 'p(99)')}ms`,
    `failed=${p('http_req_failed', 'rate')}`,
  ].join('  ');
  return { stdout: `\n${line}\n`, 'loadtest/k6/summary.json': JSON.stringify(data, null, 2) };
}
