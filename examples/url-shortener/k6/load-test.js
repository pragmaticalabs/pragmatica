import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// --- Custom metrics ---
const errorRate = new Rate('errors');
const shortenLatency = new Trend('shorten_latency', true);
const resolveLatency = new Trend('resolve_latency', true);
const analyticsLatency = new Trend('analytics_latency', true);

// --- Configuration ---

// Nodes: override with FORGE_NODES env var (comma-separated), e.g.:
//   FORGE_NODES=http://host1:8070,http://host2:8071 k6 run load-test.js
//
// Default: Forge cluster with 7 nodes on localhost ports 8070-8076
const DEFAULT_NODE_COUNT = 7;
const DEFAULT_BASE_PORT = 8070;
const DEFAULT_HOST = 'http://localhost';

const nodes = (() => {
    const envNodes = __ENV.FORGE_NODES;

    if (envNodes) {
        return envNodes.split(',').map(n => n.trim());
    }

    const count = parseInt(__ENV.FORGE_NODE_COUNT || DEFAULT_NODE_COUNT);
    const basePort = parseInt(__ENV.FORGE_BASE_PORT || DEFAULT_BASE_PORT);
    const host = __ENV.FORGE_HOST || DEFAULT_HOST;

    return Array.from({length: count}, (_, i) => `${host}:${basePort + i}`);
})();

// --- Short code pool ---
// Accumulates codes from successful create responses for resolve requests.
const shortCodePool = [];

function randomItem(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// Pin each VU to a node via round-robin — enables HTTP keep-alive connection reuse
// and prevents ephemeral port exhaustion at high RPS on macOS.
function vuNodeUrl(path) {
    const node = nodes[(__VU - 1) % nodes.length];
    return `${node}${path}`;
}

// --- Request builders ---

function buildShortenRequest() {
    const pageNum = randomInt(100000, 999999);
    return JSON.stringify({
        url: `https://example.com/page/${pageNum}`,
    });
}

const headers = {'Content-Type': 'application/json'};

// --- Scenarios ---

const targetRate = parseInt(__ENV.FORGE_RATE || '500');
const steadyDuration = __ENV.FORGE_DURATION || '2m';
const warmupDuration = __ENV.FORGE_WARMUP || '30s';

export const options = {
    scenarios: {
        // Warmup: ramp from low rate to target over warmupDuration (creates only)
        warmup_shorten: {
            executor: 'ramping-arrival-rate',
            startRate: Math.max(10, Math.floor(targetRate / 10)),
            timeUnit: '1s',
            stages: [
                { duration: warmupDuration, target: targetRate },
            ],
            preAllocatedVUs: 300,
            maxVUs: 3500,
            exec: 'shortenUrl',
        },

        // Steady-state: mixed create (10%) + resolve (90%) after warmup
        steady_mixed: {
            executor: 'constant-arrival-rate',
            rate: targetRate,
            timeUnit: '1s',
            duration: steadyDuration,
            startTime: warmupDuration,
            preAllocatedVUs: 300,
            maxVUs: 3500,
            exec: 'mixedWorkload',
        },

        // Analytics polling: low-rate periodic reads (separate VU pool)
        analytics_poll: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: steadyDuration,
            startTime: warmupDuration,
            preAllocatedVUs: 10,
            maxVUs: 50,
            exec: 'analyticsQuery',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],
        shorten_latency: ['p(95)<500', 'p(99)<1000'],
        resolve_latency: ['p(95)<300', 'p(99)<800'],
        errors: ['rate<0.01'],
    },
};

// --- Scenario executors ---

export function shortenUrl() {
    const url = vuNodeUrl('/api/v1/urls/');
    const payload = buildShortenRequest();
    const res = http.post(url, payload, {headers});

    shortenLatency.add(res.timings.duration);

    const ok = check(res, {
        'shorten: status 200': (r) => r.status === 200,
        'shorten: has shortCode': (r) => {
            try {
                const body = JSON.parse(r.body);
                if (body.shortCode) {
                    shortCodePool.push(body.shortCode);
                    return true;
                }
                return false;
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!ok);
}

export function mixedWorkload() {
    // 10% create, 90% resolve (fall back to create if pool empty)
    if (shortCodePool.length === 0 || Math.random() < 0.1) {
        shortenUrl();
    } else {
        resolveUrl();
    }
}

function resolveUrl() {
    const code = randomItem(shortCodePool);
    const url = vuNodeUrl(`/api/v1/urls/${code}`);
    const res = http.get(url, {tags: {name: 'resolve'}});

    resolveLatency.add(res.timings.duration);

    const ok = check(res, {
        'resolve: status 200': (r) => r.status === 200,
        'resolve: has originalUrl': (r) => {
            try {
                return JSON.parse(r.body).originalUrl !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!ok);
}

export function analyticsQuery() {
    // Resolve a random short code as an analytics-like read
    if (shortCodePool.length === 0) {
        return;
    }

    const code = randomItem(shortCodePool);
    const url = vuNodeUrl(`/api/v1/urls/${code}`);
    const res = http.get(url, {tags: {name: 'analytics'}});

    analyticsLatency.add(res.timings.duration);

    const ok = check(res, {
        'analytics: status 200': (r) => r.status === 200,
    });

    errorRate.add(!ok);
}
