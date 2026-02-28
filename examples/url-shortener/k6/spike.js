// Spike test: sudden burst of traffic to test recovery behavior.
//
// Usage:
//   ./k6/run-spike.sh

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
            exec: 'mixedWorkload',
        },
    },

    thresholds: {
        shorten_latency: ['p(95)<2000'],
        resolve_latency: ['p(95)<2000'],
        errors: ['rate<0.10'],
    },
};

export function mixedWorkload() {
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
