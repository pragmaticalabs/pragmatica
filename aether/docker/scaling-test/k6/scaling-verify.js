import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Core nodes (1-5): app HTTP ports 8070-8074
// Worker nodes (6-12): app HTTP ports 8075-8079, 8180-8181
const ALL_NODES = [
    'http://localhost:8070',
    'http://localhost:8071',
    'http://localhost:8072',
    'http://localhost:8073',
    'http://localhost:8074',
    'http://localhost:8075',
    'http://localhost:8076',
    'http://localhost:8077',
    'http://localhost:8078',
    'http://localhost:8079',
    'http://localhost:8180',
    'http://localhost:8181',
];

const workerTraffic = new Counter('worker_traffic');

export const options = {
    scenarios: {
        scaling: {
            executor: 'constant-arrival-rate',
            rate: 200,
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 30,
            maxVUs: 60,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
        worker_traffic: ['count>0'],
    },
};

export default function () {
    const node = ALL_NODES[__VU % ALL_NODES.length];
    const res = http.post(`${node}/api/v1/urls/`, JSON.stringify({
        url: `https://example.com/scaling-${__VU}-${__ITER}`,
    }), { headers: { 'Content-Type': 'application/json' } });

    check(res, {
        'status ok': (r) => r.status === 200 || r.status === 201,
    });

    // Track which node served the request
    const servingNode = res.headers['X-Node-Id'];
    if (servingNode && servingNode.match(/node-([6-9]|1[0-2])/)) {
        workerTraffic.add(1);
    }
}
