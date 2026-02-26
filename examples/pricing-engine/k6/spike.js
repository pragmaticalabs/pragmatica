// Spike test: sudden burst of traffic to test recovery behavior.
//
// Usage:
//   ./k6/run-spike.sh

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const latency = new Trend('pricing_latency', true);

const nodes = (() => {
    const envNodes = __ENV.FORGE_NODES;

    if (envNodes) {
        return envNodes.split(',').map(n => n.trim());
    }

    const count = parseInt(__ENV.FORGE_NODE_COUNT || '7');
    const basePort = parseInt(__ENV.FORGE_BASE_PORT || '8070');
    const host = __ENV.FORGE_HOST || 'http://localhost';

    return Array.from({length: count}, (_, i) => `${host}:${basePort + i}`);
})();

const products = [
    'WIDGET-A', 'WIDGET-B', 'WIDGET-C', 'WIDGET-D', 'WIDGET-E',
    'WIDGET-F', 'WIDGET-G', 'WIDGET-H', 'WIDGET-I', 'WIDGET-J',
];
const regions = ['US-CA', 'US-NY', 'US-TX', 'US-OR', 'US-MT', 'GB', 'JP', 'DE', 'DE-FREE'];
const coupons = ['SAVE10', 'SAVE20', 'VIP50', 'WELCOME', '', '', ''];

function randomItem(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function randomInt(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

const headers = {'Content-Type': 'application/json'};

export const options = {
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: 100,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 500,
            stages: [
                {duration: '30s', target: 100},    // Baseline
                {duration: '5s',  target: 3000},   // Spike up
                {duration: '30s', target: 3000},   // Hold spike
                {duration: '5s',  target: 100},    // Drop back
                {duration: '30s', target: 100},    // Recovery observation
                {duration: '5s',  target: 5000},   // Bigger spike
                {duration: '20s', target: 5000},   // Hold
                {duration: '10s', target: 100},    // Cool down
            ],
            exec: 'pricing',
        },
    },

    thresholds: {
        pricing_latency: ['p(95)<2000'],
        errors: ['rate<0.10'],
    },
};

export function pricing() {
    const node = nodes[Math.floor(Math.random() * nodes.length)];
    const url = `${node}/api/v1/pricing/calculate`;

    const payload = JSON.stringify({
        productId: randomItem(products),
        quantity: randomInt(1, 20),
        regionCode: randomItem(regions),
        couponCode: randomItem(coupons),
    });

    const res = http.post(url, payload, {headers});

    latency.add(res.timings.duration);
    errorRate.add(res.status !== 200);

    check(res, {
        'status 200': (r) => r.status === 200,
    });
}
