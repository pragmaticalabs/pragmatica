import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// --- Custom metrics ---
const errorRate = new Rate('errors');
const orderLatency = new Trend('order_latency', true);

// --- Configuration ---

// Nodes: override with FORGE_NODES env var (comma-separated), e.g.:
//   FORGE_NODES=http://host1:8070,http://host2:8071 k6 run load-test.js
//
// Default: Forge cluster with 3 nodes on localhost ports 8070-8072
const DEFAULT_NODE_COUNT = 3;
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

// --- Test data ---

const products = [
    'LAPTOP-PRO', 'MOUSE-WIRELESS', 'KEYBOARD-MECH', 'MONITOR-4K',
    'HEADSET-BT', 'WEBCAM-HD', 'USB-HUB', 'CHARGER-65W',
];

const amounts = ['9.99', '29.99', '49.99', '99.99', '149.99', '249.99', '499.99'];

// --- Helpers ---

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

function buildOrderRequest() {
    return JSON.stringify({
        customerId: `customer-${randomInt(100000, 999999)}`,
        productId: randomItem(products),
        quantity: randomInt(1, 5),
        amount: randomItem(amounts),
    });
}

const headers = {'Content-Type': 'application/json'};

// --- Scenarios ---

const targetRate = parseInt(__ENV.FORGE_RATE || '200');
const steadyDuration = __ENV.FORGE_DURATION || '2m';
const warmupDuration = __ENV.FORGE_WARMUP || '30s';

export const options = {
    scenarios: {
        // Warmup: ramp from low rate to target over warmupDuration
        warmup_orders: {
            executor: 'ramping-arrival-rate',
            startRate: 20,
            timeUnit: '1s',
            stages: [
                { duration: warmupDuration, target: targetRate },
            ],
            preAllocatedVUs: 100,
            maxVUs: 1000,
            exec: 'processOrder',
        },

        // Steady-state: constant order rate after warmup
        steady_orders: {
            executor: 'constant-arrival-rate',
            rate: targetRate,
            timeUnit: '1s',
            duration: steadyDuration,
            startTime: warmupDuration,
            preAllocatedVUs: 100,
            maxVUs: 1000,
            exec: 'processOrder',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
        order_latency: ['p(95)<1000', 'p(99)<2000'],
        errors: ['rate<0.01'],
    },
};

// --- Scenario executors ---

export function processOrder() {
    const url = vuNodeUrl('/api/v1/orders/');
    const payload = buildOrderRequest();
    const res = http.post(url, payload, {headers});

    orderLatency.add(res.timings.duration);

    const ok = check(res, {
        'order: status 200': (r) => r.status === 200,
        'order: has orderId': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.orderId !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!ok);
}
