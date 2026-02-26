// Ramp-up test: gradually increase load to find saturation point.
// Useful for determining max throughput before latency degrades.
//
// Usage:
//   ./k6/run-ramp.sh
//   FORGE_NODES=http://host1:8070,http://host2:8071 k6 run k6/ramp-up.js

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
    stages: [
        {duration: '30s', target: 50},     // Warm up
        {duration: '1m',  target: 200},    // Moderate load
        {duration: '1m',  target: 500},    // Heavy load
        {duration: '1m',  target: 1000},   // Stress
        {duration: '1m',  target: 2000},   // Peak
        {duration: '30s', target: 0},      // Cool down
    ],

    thresholds: {
        http_req_duration: ['p(95)<1000'],
        errors: ['rate<0.05'],
    },
};

export default function () {
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
