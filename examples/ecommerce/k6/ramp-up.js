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
const orderLatency = new Trend('order_latency', true);

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

export const options = {
    stages: [
        {duration: '30s', target: 20},     // Warm up
        {duration: '1m',  target: 100},    // Moderate load
        {duration: '1m',  target: 200},    // Heavy load
        {duration: '1m',  target: 500},    // Stress
        {duration: '1m',  target: 1000},   // Peak
        {duration: '30s', target: 0},      // Cool down
    ],

    thresholds: {
        http_req_duration: ['p(95)<1000'],
        errors: ['rate<0.05'],
    },
};

export default function () {
    const node = nodes[Math.floor(Math.random() * nodes.length)];
    const url = `${node}/api/v1/orders/`;
    const payload = buildOrderRequest();

    const res = http.post(url, payload, {headers});

    orderLatency.add(res.timings.duration);
    errorRate.add(res.status !== 200);

    check(res, {
        'order: status 200': (r) => r.status === 200,
    });
}
