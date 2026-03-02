// Per-node test: sends traffic to each node independently.
// Measures per-node throughput and latency to detect hot spots.
//
// Usage:
//   ./k6/run-per-node.sh
//   ./k6/run-per-node.sh 200 5m

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

function randomItem(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function randomInt(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

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

// Build scenarios dynamically: one per node
const scenarios = {};

for (let i = 0; i < nodes.length; i++) {
    scenarios[`node_${i}`] = {
        executor: 'constant-arrival-rate',
        rate: parseInt(__ENV.FORGE_RATE_PER_NODE || '50'),
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
        http_req_duration: ['p(95)<1000'],
    },
};

export function hitNode() {
    const i = parseInt(__ENV.NODE_INDEX);
    const url = `${nodes[i]}/api/v1/orders/`;
    const payload = buildOrderRequest();

    const res = http.post(url, payload, {headers});

    nodeLatency[i].add(res.timings.duration);
    nodeErrors[i].add(res.status !== 200);
    nodeRequests[i].add(1);

    check(res, {
        [`node ${i}: order 200`]: (r) => r.status === 200,
    });
}
