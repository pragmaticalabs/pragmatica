import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// --- Custom metrics ---
const errorRate = new Rate('errors');
const orderLatency = new Trend('order_latency', true);
const inventoryCheckLatency = new Trend('inventory_check_latency', true);

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

// --- Test data ---

const products = [
    'LAPTOP-PRO', 'MOUSE-WIRELESS', 'KEYBOARD-MECH', 'MONITOR-4K',
    'HEADSET-BT', 'WEBCAM-HD', 'USB-HUB', 'CHARGER-65W',
];

const addresses = [
    {street: '742 Evergreen Terrace', city: 'Los Angeles', state: 'CA', postalCode: '90210'},
    {street: '350 Fifth Avenue', city: 'New York', state: 'NY', postalCode: '10118'},
    {street: '1600 Pennsylvania Ave', city: 'Houston', state: 'TX', postalCode: '77001'},
    {street: '200 Ocean Drive', city: 'Miami', state: 'FL', postalCode: '33139'},
    {street: '400 Broad St', city: 'Seattle', state: 'WA', postalCode: '98109'},
    {street: '1000 SW Broadway', city: 'Portland', state: 'OR', postalCode: '97205'},
];

const paymentCards = [
    {cardNumber: '4111111111111111', cardholderName: 'John Smith'},
    {cardNumber: '5500000000000004', cardholderName: 'Jane Doe'},
];

const shippingOptions = ['STANDARD', 'EXPRESS', 'OVERNIGHT'];

const discountCodes = ['SAVE10', 'SAVE20', 'WELCOME', 'BLACKFRIDAY', '', '', '', ''];

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
    const itemCount = randomInt(1, 4);
    const selectedProducts = [];
    const available = [...products];

    for (let i = 0; i < itemCount && available.length > 0; i++) {
        const idx = Math.floor(Math.random() * available.length);
        selectedProducts.push({
            productId: available[idx],
            quantity: randomInt(1, 5),
        });
        available.splice(idx, 1);
    }

    const address = randomItem(addresses);
    const card = randomItem(paymentCards);

    return JSON.stringify({
        customerId: `customer-${randomInt(100000, 999999)}`,
        items: selectedProducts,
        shippingAddress: {
            street: address.street,
            city: address.city,
            state: address.state,
            postalCode: address.postalCode,
            country: 'US',
        },
        paymentMethod: {
            cardNumber: card.cardNumber,
            expiryMonth: '12',
            expiryYear: '2027',
            cvv: '123',
            cardholderName: card.cardholderName,
        },
        shippingOption: randomItem(shippingOptions),
        discountCode: randomItem(discountCodes),
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
            preAllocatedVUs: 300,
            maxVUs: 3500,
            exec: 'placeOrder',
        },

        // Steady-state: constant order rate after warmup
        steady_orders: {
            executor: 'constant-arrival-rate',
            rate: targetRate,
            timeUnit: '1s',
            duration: steadyDuration,
            startTime: warmupDuration,
            preAllocatedVUs: 300,
            maxVUs: 3500,
            exec: 'placeOrder',
        },

        // Inventory polling: low-rate periodic checks (separate VU pool)
        inventory_poll: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: steadyDuration,
            startTime: warmupDuration,
            preAllocatedVUs: 10,
            maxVUs: 50,
            exec: 'checkInventory',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
        order_latency: ['p(95)<1000', 'p(99)<2000'],
        errors: ['rate<0.01'],
    },
};

// --- Scenario executors ---

export function placeOrder() {
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

export function checkInventory() {
    const url = vuNodeUrl('/api/v1/inventory/check');
    const payload = JSON.stringify({
        items: [{productId: 'LAPTOP-PRO', quantity: 1}],
    });
    const res = http.post(url, payload, {headers, tags: {name: 'inventory_check'}});

    inventoryCheckLatency.add(res.timings.duration);

    const ok = check(res, {
        'inventory: status 200': (r) => r.status === 200,
    });

    errorRate.add(!ok);
}
