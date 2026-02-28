// Per-node test: sends traffic to each node independently.
// Measures per-node throughput and latency to detect hot spots.
//
// Usage:
//   ./k6/run-per-node.sh
//   ./k6/run-per-node.sh 500 5m

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

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

// Per-node metrics
const nodeLatency = {};
const nodeErrors = {};
const nodeRequests = {};

for (let i = 0; i < nodes.length; i++) {
    nodeLatency[i] = new Trend(`node_${i}_latency`, true);
    nodeErrors[i] = new Rate(`node_${i}_errors`);
    nodeRequests[i] = new Counter(`node_${i}_requests`);
}

// Short code pool per node
const nodePools = {};
for (let i = 0; i < nodes.length; i++) {
    nodePools[i] = [];
}

function randomItem(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function randomInt(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

const headers = {'Content-Type': 'application/json'};

// Build scenarios dynamically: one per node
const scenarios = {};

for (let i = 0; i < nodes.length; i++) {
    scenarios[`node_${i}`] = {
        executor: 'constant-arrival-rate',
        rate: parseInt(__ENV.FORGE_RATE_PER_NODE || '100'),
        timeUnit: '1s',
        duration: __ENV.FORGE_DURATION || '2m',
        preAllocatedVUs: 10,
        maxVUs: 50,
        exec: 'hitNode',
        env: {NODE_INDEX: String(i)},
    };
}

export const options = {
    scenarios,
    thresholds: {
        http_req_duration: ['p(95)<500'],
    },
};

export function hitNode() {
    const i = parseInt(__ENV.NODE_INDEX);
    const pool = nodePools[i];

    // 10% create, 90% resolve (fall back to create if pool empty)
    if (pool.length === 0 || Math.random() < 0.1) {
        const url = `${nodes[i]}/api/v1/urls/`;
        const payload = JSON.stringify({
            url: `https://example.com/page/${randomInt(100000, 999999)}`,
        });

        const res = http.post(url, payload, {headers});

        nodeLatency[i].add(res.timings.duration);
        nodeErrors[i].add(res.status !== 200);
        nodeRequests[i].add(1);

        if (res.status === 200) {
            try {
                const body = JSON.parse(res.body);
                if (body.shortCode) {
                    pool.push(body.shortCode);
                }
            } catch (e) { /* ignore */ }
        }

        check(res, {
            [`node ${i}: shorten 200`]: (r) => r.status === 200,
        });
    } else {
        const code = randomItem(pool);
        const url = `${nodes[i]}/api/v1/urls/${code}`;
        const res = http.get(url, {tags: {name: `node_${i}_resolve`}});

        nodeLatency[i].add(res.timings.duration);
        nodeErrors[i].add(res.status !== 200);
        nodeRequests[i].add(1);

        check(res, {
            [`node ${i}: resolve 200`]: (r) => r.status === 200,
        });
    }
}
