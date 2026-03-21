import http from 'k6/http';
import { check } from 'k6';

const NODES = [
    'http://localhost:8070',
    'http://localhost:8071',
    'http://localhost:8072',
    'http://localhost:8073',
    'http://localhost:8074',
];

export const options = {
    scenarios: {
        steady: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: 20,
            maxVUs: 50,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
    },
};

export default function () {
    const node = NODES[__VU % NODES.length];
    const res = http.post(`${node}/api/v1/urls/`, JSON.stringify({
        url: `https://example.com/test-${__ITER}`,
    }), { headers: { 'Content-Type': 'application/json' } });

    check(res, {
        'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
    });
}
