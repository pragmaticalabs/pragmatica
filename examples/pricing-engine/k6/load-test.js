import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// --- Custom metrics ---
const errorRate = new Rate('errors');
const pricingLatency = new Trend('pricing_latency', true);
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

// --- Test data (matches load-config.toml) ---

const products = [
    'WIDGET-A', 'WIDGET-B', 'WIDGET-C', 'WIDGET-D', 'WIDGET-E',
    'WIDGET-F', 'WIDGET-G', 'WIDGET-H', 'WIDGET-I', 'WIDGET-J',
];

const regions = [
    'US-CA', 'US-NY', 'US-TX', 'US-OR', 'US-MT', 'GB', 'JP', 'DE', 'DE-FREE',
];

// Empty strings = no coupon (matches load-config.toml pattern)
const coupons = ['SAVE10', 'SAVE20', 'VIP50', 'WELCOME', '', '', ''];

// Invalid products for error-path testing
const invalidProducts = ['NONEXISTENT', 'WIDGET-Z', ''];

function randomItem(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function nodeUrl(path) {
    const node = nodes[Math.floor(Math.random() * nodes.length)];
    return `${node}${path}`;
}

// --- Request builders ---

function buildPriceRequest() {
    return JSON.stringify({
        productId: randomItem(products),
        quantity: randomInt(1, 20),
        regionCode: randomItem(regions),
        couponCode: randomItem(coupons),
    });
}

function buildInvalidRequest() {
    return JSON.stringify({
        productId: randomItem(invalidProducts),
        quantity: randomInt(1, 5),
        regionCode: randomItem(regions),
        couponCode: '',
    });
}

const headers = {'Content-Type': 'application/json'};

// --- Scenarios ---

export const options = {
    scenarios: {
        // Steady-state: constant request rate across all nodes
        steady_pricing: {
            executor: 'constant-arrival-rate',
            rate: parseInt(__ENV.FORGE_RATE || '500'),
            timeUnit: '1s',
            duration: __ENV.FORGE_DURATION || '2m',
            preAllocatedVUs: 200,
            maxVUs: 1000,
            exec: 'pricingCalculation',
        },

        // Analytics polling: low-rate periodic reads (separate VU pool)
        analytics_poll: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: __ENV.FORGE_DURATION || '2m',
            preAllocatedVUs: 10,
            maxVUs: 50,
            exec: 'analyticsQuery',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],
        pricing_latency: ['p(95)<300', 'p(99)<800'],
        errors: ['rate<0.01'],
    },
};

// --- Scenario executors ---

export function pricingCalculation() {
    const url = nodeUrl('/api/v1/pricing/calculate');
    const payload = buildPriceRequest();
    const res = http.post(url, payload, {headers});

    pricingLatency.add(res.timings.duration);

    const ok = check(res, {
        'pricing: status 200': (r) => r.status === 200,
        'pricing: has totalPrice': (r) => {
            try {
                return JSON.parse(r.body).totalPrice !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!ok);
}

export function analyticsQuery() {
    const url = nodeUrl('/api/v1/pricing/analytics/high-value');
    const res = http.get(url);

    analyticsLatency.add(res.timings.duration);

    const ok = check(res, {
        'analytics: status 200': (r) => r.status === 200,
    });

    errorRate.add(!ok);
}

// --- Standalone scenarios (run with -e SCENARIO=xxx) ---

// Error path: test with invalid products
export function errorPath() {
    const url = nodeUrl('/api/v1/pricing/calculate');
    const res = http.post(url, buildInvalidRequest(), {headers});

    check(res, {
        'error: status 400 or 404': (r) => r.status === 400 || r.status === 404,
    });
}
