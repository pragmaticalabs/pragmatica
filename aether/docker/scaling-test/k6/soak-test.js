import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// App HTTP ports: core (8070-8074) + worker (8075-8079, 8180-8181)
// Management ports: core (8080-8084) + worker (8185-8191)
const CORE_NODES = [
    { app: 'http://localhost:8070', mgmt: 'http://localhost:8080' },
    { app: 'http://localhost:8071', mgmt: 'http://localhost:8081' },
    { app: 'http://localhost:8072', mgmt: 'http://localhost:8082' },
    { app: 'http://localhost:8073', mgmt: 'http://localhost:8083' },
    { app: 'http://localhost:8074', mgmt: 'http://localhost:8084' },
];

const WORKER_NODES = [
    { app: 'http://localhost:8075', mgmt: 'http://localhost:8185' },
    { app: 'http://localhost:8076', mgmt: 'http://localhost:8186' },
    { app: 'http://localhost:8077', mgmt: 'http://localhost:8187' },
    { app: 'http://localhost:8078', mgmt: 'http://localhost:8188' },
    { app: 'http://localhost:8079', mgmt: 'http://localhost:8189' },
    { app: 'http://localhost:8180', mgmt: 'http://localhost:8190' },
    { app: 'http://localhost:8181', mgmt: 'http://localhost:8191' },
];

const ALL_NODES = CORE_NODES.concat(WORKER_NODES);

// Custom metrics
const errorRate = new Rate('error_rate');
const requestLatency = new Trend('request_latency');
const workerTraffic = new Counter('worker_traffic');
const phaseErrors = {
    baseline: new Counter('phase_baseline_errors'),
    chaosWorkerKill: new Counter('phase_chaos_worker_kill_errors'),
    chaosRollingRestart: new Counter('phase_chaos_rolling_restart_errors'),
    recovery: new Counter('phase_recovery_errors'),
};
const phaseRequests = {
    baseline: new Counter('phase_baseline_requests'),
    chaosWorkerKill: new Counter('phase_chaos_worker_kill_requests'),
    chaosRollingRestart: new Counter('phase_chaos_rolling_restart_requests'),
    recovery: new Counter('phase_recovery_requests'),
};

export const options = {
    stages: [
        // Ramp up over 2 minutes
        { duration: '2m', target: 100 },
        // Hour 1: Steady state baseline (58 minutes at 100 VUs)
        { duration: '58m', target: 100 },
        // Hour 2: Chaos — kill worker (maintained load)
        { duration: '60m', target: 100 },
        // Hour 3: Chaos — rolling restart cores (maintained load)
        { duration: '60m', target: 100 },
        // Hour 4: Recovery verification (steady state)
        { duration: '58m', target: 100 },
        // Ramp down
        { duration: '2m', target: 0 },
    ],
    thresholds: {
        'error_rate': ['rate<0.01'],              // <1% error rate overall
        'request_latency': ['p(99)<500'],          // P99 < 500ms
        'http_req_duration': ['p(95)<200'],        // P95 < 200ms
        'worker_traffic': ['count>0'],             // Workers must serve traffic
    },
};

function classifyPhase(elapsedMinutes) {
    if (elapsedMinutes < 60) return 'baseline';
    if (elapsedMinutes < 120) return 'chaos-worker-kill';
    if (elapsedMinutes < 180) return 'chaos-rolling-restart';
    return 'recovery';
}

function phaseCounterKey(phase) {
    if (phase === 'chaos-worker-kill') return 'chaosWorkerKill';
    if (phase === 'chaos-rolling-restart') return 'chaosRollingRestart';
    return phase;
}

export function setup() {
    console.log('=== Soak Test Starting ===');
    console.log('Duration: 4 hours (240 minutes + 4 min ramp)');
    console.log('Phases: baseline -> chaos-worker-kill -> chaos-rolling-restart -> recovery');
    console.log(`Total nodes: ${ALL_NODES.length} (${CORE_NODES.length} core + ${WORKER_NODES.length} worker)`);
    return { startTime: Date.now() };
}

export default function (data) {
    const elapsedMs = Date.now() - data.startTime;
    const elapsedMinutes = elapsedMs / 1000 / 60;
    const phase = classifyPhase(elapsedMinutes);
    const nodeEntry = ALL_NODES[__VU % ALL_NODES.length];
    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: { phase: phase },
    };

    // Mix of operations: 60% create, 30% retrieve, 10% health
    const roll = Math.random();

    let res;
    let isError;

    if (roll < 0.6) {
        // Create short URL
        res = http.post(`${nodeEntry.app}/api/v1/urls/`, JSON.stringify({
            url: `https://example.com/soak-${__VU}-${__ITER}-${Date.now()}`,
        }), params);
        isError = res.status !== 200 && res.status !== 201;

        check(res, {
            'create: status ok': (r) => r.status === 200 || r.status === 201,
        });
    } else if (roll < 0.9) {
        // Retrieve (GET to base — may return list or 404, both acceptable under load)
        res = http.get(`${nodeEntry.app}/api/v1/urls/`, { tags: { phase: phase } });
        isError = res.status !== 200 && res.status !== 404;

        check(res, {
            'retrieve: status ok': (r) => r.status === 200 || r.status === 404,
        });
    } else {
        // Health check via management port
        res = http.get(`${nodeEntry.mgmt}/api/health`, { tags: { phase: phase } });
        isError = res.status !== 200;

        check(res, {
            'health: status 200': (r) => r.status === 200,
        });
    }

    // Record metrics
    errorRate.add(isError);
    requestLatency.add(res.timings.duration);

    const key = phaseCounterKey(phase);
    phaseRequests[key].add(1);
    if (isError) {
        phaseErrors[key].add(1);
    }

    // Track worker traffic via X-Node-Id header
    const servingNode = res.headers['X-Node-Id'];
    if (servingNode && servingNode.match(/node-([6-9]|1[0-2])/)) {
        workerTraffic.add(1);
    }
}

export function teardown(data) {
    const totalDuration = (Date.now() - data.startTime) / 1000 / 60;
    console.log('=== Soak Test Complete ===');
    console.log(`Total duration: ${totalDuration.toFixed(1)} minutes`);
    console.log('Check per-phase metrics in k6 output (tagged by phase)');
    console.log('Phases: baseline, chaos-worker-kill, chaos-rolling-restart, recovery');
}
