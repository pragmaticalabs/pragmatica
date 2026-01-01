// Aether Forge - Dashboard Controller (Simplified)

const API_BASE = '';
const REFRESH_INTERVAL = 500;

// State
let nodes = [];
let events = [];
let successChart = null;
let throughputChart = null;
let successHistory = [];
let throughputHistory = [];
const MAX_HISTORY = 60;
let nodeMetricsData = [];
let entryPointMetrics = [];

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    initCharts();
    initControls();
    startPolling();
});

// ===============================
// Charts (Chart.js)
// ===============================

function initCharts() {
    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
            x: { display: false },
            y: {
                min: 0,
                grid: { color: '#252530' },
                ticks: { color: '#888898', font: { size: 9 } }
            }
        },
        animation: { duration: 0 }
    };

    successChart = new Chart(document.getElementById('success-chart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                data: [],
                borderColor: '#22c55e',
                backgroundColor: 'rgba(34, 197, 94, 0.1)',
                fill: true,
                tension: 0.3,
                pointRadius: 0,
                borderWidth: 1.5
            }]
        },
        options: { ...chartOptions, scales: { ...chartOptions.scales, y: { ...chartOptions.scales.y, max: 100, ticks: { ...chartOptions.scales.y.ticks, callback: v => v + '%' } } } }
    });

    throughputChart = new Chart(document.getElementById('throughput-chart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                data: [],
                borderColor: '#06b6d4',
                backgroundColor: 'rgba(6, 182, 212, 0.1)',
                fill: true,
                tension: 0.3,
                pointRadius: 0,
                borderWidth: 1.5
            }]
        },
        options: chartOptions
    });
}

function updateCharts(metrics) {
    successHistory.push(metrics.successRate);
    if (successHistory.length > MAX_HISTORY) successHistory.shift();

    throughputHistory.push(metrics.requestsPerSecond);
    if (throughputHistory.length > MAX_HISTORY) throughputHistory.shift();

    successChart.data.labels = successHistory.map(() => '');
    successChart.data.datasets[0].data = successHistory;

    const rate = metrics.successRate;
    successChart.data.datasets[0].borderColor = rate >= 99 ? '#22c55e' : rate >= 95 ? '#f59e0b' : '#ef4444';
    successChart.update('none');

    throughputChart.data.labels = throughputHistory.map(() => '');
    throughputChart.data.datasets[0].data = throughputHistory;
    throughputChart.update('none');
}

// ===============================
// Controls
// ===============================

function initControls() {
    document.getElementById('btn-kill-node').addEventListener('click', () => showNodeModal(false));
    document.getElementById('btn-kill-leader').addEventListener('click', async () => {
        const status = await fetchStatus();
        if (status?.cluster.leaderId !== 'none') await killNode(status.cluster.leaderId);
    });
    document.getElementById('btn-rolling-restart').addEventListener('click', () => apiPost('/api/rolling-restart'));
    document.getElementById('btn-add-node').addEventListener('click', () => apiPost('/api/add-node'));

    document.getElementById('btn-load-1k').addEventListener('click', () => setLoad(1000));
    document.getElementById('btn-load-5k').addEventListener('click', () => setLoad(5000));
    document.getElementById('btn-load-10k').addEventListener('click', () => setLoad(10000));
    document.getElementById('btn-load-25k').addEventListener('click', () => setLoad(25000));
    document.getElementById('btn-load-50k').addEventListener('click', () => setLoad(50000));
    document.getElementById('btn-load-100k').addEventListener('click', () => setLoad(100000));
    document.getElementById('btn-ramp').addEventListener('click', rampToNextStep);

    const slider = document.getElementById('load-slider');
    slider.addEventListener('input', () => document.getElementById('load-value').textContent = formatLoadRate(slider.value));
    slider.addEventListener('change', () => setLoad(parseInt(slider.value)));

    document.getElementById('btn-reset').addEventListener('click', async () => {
        await apiPost('/api/reset-metrics');
        successHistory = [];
        throughputHistory = [];
    });

    document.getElementById('load-generator-toggle').addEventListener('change', e =>
        apiPost('/api/simulator/config/enabled', { enabled: e.target.checked }));

    document.getElementById('btn-apply-multiplier').addEventListener('click', () => {
        const multiplier = parseFloat(document.getElementById('rate-multiplier').value);
        if (!isNaN(multiplier) && multiplier > 0) apiPost('/api/simulator/config/multiplier', { multiplier });
    });

    document.getElementById('modal-cancel').addEventListener('click', hideNodeModal);
}

function showNodeModal(includeLeader) {
    const nodeList = document.getElementById('node-list');
    nodeList.innerHTML = '';
    nodes.forEach(node => {
        if (!includeLeader && node.isLeader) return;
        const btn = document.createElement('button');
        btn.className = `btn ${node.isLeader ? 'btn-warning' : 'btn-danger'}`;
        btn.textContent = `${node.id}${node.isLeader ? ' (Leader)' : ''}`;
        btn.addEventListener('click', async () => { hideNodeModal(); await killNode(node.id); });
        nodeList.appendChild(btn);
    });
    document.getElementById('node-modal').classList.remove('hidden');
}

function hideNodeModal() {
    document.getElementById('node-modal').classList.add('hidden');
}

async function killNode(nodeId) { await apiPost(`/api/kill/${nodeId}`); }

async function setLoad(rate) {
    await apiPost(`/api/load/set/${rate}`);
    document.getElementById('load-slider').value = rate;
    document.getElementById('load-value').textContent = formatLoadRate(rate);
}

function formatLoadRate(rate) {
    return rate >= 1000 ? `${(rate / 1000).toFixed(rate % 1000 === 0 ? 0 : 1)}K` : `${rate}`;
}

const LOAD_STEPS = [1000, 5000, 10000, 25000, 50000, 100000];

async function rampToNextStep() {
    const status = await fetchStatus();
    if (!status) return;
    const nextStep = LOAD_STEPS.find(step => step > status.load.currentRate) || LOAD_STEPS[LOAD_STEPS.length - 1];
    if (nextStep > status.load.currentRate) await apiPost('/api/load/ramp', { targetRate: nextStep, durationMs: 10000 });
}

// ===============================
// API & Polling
// ===============================

async function fetchStatus() {
    try {
        const response = await fetch(`${API_BASE}/api/status`);
        return response.ok ? await response.json() : null;
    } catch { return null; }
}

async function fetchEvents() {
    try {
        const response = await fetch(`${API_BASE}/api/events`);
        return response.ok ? await response.json() : [];
    } catch { return []; }
}

async function apiPost(endpoint, body = {}) {
    try {
        const response = await fetch(`${API_BASE}${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        return await response.json();
    } catch { return null; }
}

async function fetchNodeMetrics() {
    try {
        const response = await fetch(`${API_BASE}/api/node-metrics`);
        if (response.ok) nodeMetricsData = await response.json();
    } catch {}
}

async function fetchEntryPointMetrics() {
    try {
        const response = await fetch(`${API_BASE}/api/simulator/metrics`);
        if (response.ok) {
            const data = await response.json();
            entryPointMetrics = data.entryPoints || [];
            updateEntryPointsTable();
        }
    } catch {}
}

function updateEntryPointsTable() {
    const tbody = document.getElementById('entrypoints-body');
    if (!entryPointMetrics?.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="placeholder">No entry points</td></tr>';
        return;
    }

    tbody.innerHTML = [...entryPointMetrics].sort((a, b) => a.name.localeCompare(b.name)).map(ep => {
        const successClass = ep.successRate >= 99 ? 'success' : ep.successRate >= 95 ? 'warning' : 'danger';
        return `<tr>
            <td class="ep-name">${escapeHtml(ep.name)}</td>
            <td><input type="number" value="${ep.rate}" min="0" max="100000" step="100" data-entrypoint="${escapeHtml(ep.name)}" class="rate-input" onchange="updateEntryPointRate(this)"></td>
            <td>${formatNumber(ep.totalCalls)}</td>
            <td class="ep-success ${successClass}">${ep.successRate.toFixed(1)}%</td>
            <td>${ep.avgLatencyMs.toFixed(1)}ms</td>
            <td>${ep.p99LatencyMs.toFixed(0)}ms</td>
        </tr>`;
    }).join('');
}

async function updateEntryPointRate(input) {
    const rate = parseInt(input.value) || 0;
    try {
        await fetch(`${API_BASE}/api/simulator/rate/${input.dataset.entrypoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rate })
        });
    } catch {
        input.classList.add('error');
        setTimeout(() => input.classList.remove('error'), 1000);
    }
}

function formatNumber(num) {
    return num >= 1000000 ? (num / 1000000).toFixed(1) + 'M' : num >= 1000 ? (num / 1000).toFixed(1) + 'K' : num.toString();
}

function startPolling() {
    poll();
    setInterval(poll, REFRESH_INTERVAL);
}

async function poll() {
    const status = await fetchStatus();
    if (!status) return;

    nodes = status.cluster.nodes;
    updateMetricsDisplay(status.metrics);
    updateCharts(status.metrics);
    updateLoadDisplay(status.load);

    document.getElementById('uptime').textContent = formatUptime(status.uptimeSeconds);
    document.getElementById('node-count').textContent = status.cluster.nodeCount;
    document.getElementById('slice-count').textContent = status.sliceCount || 0;

    const newEvents = await fetchEvents();
    updateTimeline(newEvents);

    await fetchNodeMetrics();
    updateNodesList(status.cluster.nodes, status.sliceCount);

    await fetchEntryPointMetrics();
}

function updateMetricsDisplay(metrics) {
    document.getElementById('requests-per-sec').textContent = Math.round(metrics.requestsPerSecond).toLocaleString();

    const successEl = document.getElementById('success-rate');
    successEl.textContent = `${metrics.successRate.toFixed(1)}%`;

    const card = successEl.closest('.metric-card');
    const color = metrics.successRate >= 99 ? '#22c55e' : metrics.successRate >= 95 ? '#f59e0b' : '#ef4444';
    card.style.borderColor = color;
    successEl.style.color = color;

    document.getElementById('avg-latency').textContent = `${metrics.avgLatencyMs.toFixed(1)}ms`;
}

function updateLoadDisplay(load) {
    const slider = document.getElementById('load-slider');
    if (document.activeElement !== slider) {
        slider.value = load.currentRate;
        document.getElementById('load-value').textContent = formatLoadRate(load.currentRate);
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function updateTimeline(newEvents) {
    const timeline = document.getElementById('timeline');
    if (newEvents.length < events.length) {
        timeline.innerHTML = '';
        events = [];
    }

    if (newEvents.length > events.length) {
        newEvents.slice(events.length).forEach(event => {
            const div = document.createElement('div');
            div.className = 'timeline-event';
            div.innerHTML = `
                <span class="event-time">${formatEventTime(event.timestamp)}</span>
                <span class="event-type ${escapeHtml(event.type)}">${event.type}</span>
                <span class="event-message">${escapeHtml(event.message)}</span>
            `;
            timeline.insertBefore(div, timeline.firstChild);
        });
        while (timeline.children.length > 15) timeline.removeChild(timeline.lastChild);
    }
    events = newEvents;
}

function formatUptime(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function formatEventTime(isoString) {
    return new Date(isoString).toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function updateNodesList(clusterNodes, sliceCount) {
    const container = document.getElementById('nodes-list');
    if (!clusterNodes?.length) {
        container.innerHTML = '<div class="node-item placeholder">No nodes available</div>';
        return;
    }

    const sliceNames = sliceCount > 0 ? ['inventory', 'pricing', 'place-order', 'get-status', 'cancel'] : [];

    container.innerHTML = [...clusterNodes]
        .sort((a, b) => a.isLeader ? -1 : b.isLeader ? 1 : a.id.localeCompare(b.id))
        .map(node => {
            const metrics = nodeMetricsData.find(m => m.nodeId === node.id);
            const cpu = metrics ? (metrics.cpuUsage * 100).toFixed(0) : '?';
            const heap = metrics ? `${metrics.heapUsedMb}/${metrics.heapMaxMb}` : '?/?';
            const slices = sliceNames.map(s => `<span class="slice-tag">${s}</span>`).join('');

            return `<div class="node-item ${node.isLeader ? 'leader' : ''}">
                <span class="node-id">${node.id}</span>
                ${node.isLeader ? '<span class="leader-badge">LEADER</span>' : ''}
                <span class="node-stats"><span>CPU ${cpu}%</span><span>Heap ${heap}MB</span></span>
                <span class="node-slices">${slices}</span>
            </div>`;
        }).join('');
}
