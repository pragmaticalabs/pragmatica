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
const shortenLatency = new Trend('shorten_latency', true);
const resolveLatency = new Trend('resolve_latency', true);

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

const shortCodePool = [];

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

    // 10% create, 90% resolve (fall back to create if pool empty)
    if (shortCodePool.length === 0 || Math.random() < 0.1) {
        const url = `${node}/api/v1/urls/`;
        const payload = JSON.stringify({
            url: `https://example.com/page/${randomInt(100000, 999999)}`,
        });

        const res = http.post(url, payload, {headers});

        shortenLatency.add(res.timings.duration);
        errorRate.add(res.status !== 200);

        if (res.status === 200) {
            try {
                const body = JSON.parse(res.body);
                if (body.shortCode) {
                    shortCodePool.push(body.shortCode);
                }
            } catch (e) { /* ignore */ }
        }

        check(res, {
            'shorten: status 200': (r) => r.status === 200,
        });
    } else {
        const code = randomItem(shortCodePool);
        const url = `${node}/api/v1/urls/${code}`;
        const res = http.get(url, {tags: {name: 'resolve'}});

        resolveLatency.add(res.timings.duration);
        errorRate.add(res.status !== 200);

        check(res, {
            'resolve: status 200': (r) => r.status === 200,
        });
    }
}
