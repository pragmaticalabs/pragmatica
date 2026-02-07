// Aether Forge - Dashboard Controller (HTMX + Chart.js)

// ===============================
// State
// ===============================
let successChart = null;
let throughputChart = null;
let successHistory = [];
let throughputHistory = [];
const MAX_HISTORY = 60;
let rollingRestartActive = false;

// ===============================
// Tab Management
// ===============================
function switchTab(btn) {
    document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
    btn.classList.add('active');
}

// Re-initialize charts when Overview tab loads
document.body.addEventListener('htmx:afterSwap', function(event) {
    if (event.detail.target.id === 'tab-content') {
        var canvas = document.getElementById('success-chart');
        if (canvas) {
            initCharts();
            startChartPolling();
        }
    }
});

// ===============================
// Charts (Chart.js) - 500ms poll
// ===============================
let chartPollInterval = null;

function initCharts() {
    var chartOptions = {
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
                fill: true, tension: 0.3, pointRadius: 0, borderWidth: 1.5
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
                fill: true, tension: 0.3, pointRadius: 0, borderWidth: 1.5
            }]
        },
        options: chartOptions
    });
}

function startChartPolling() {
    if (chartPollInterval) clearInterval(chartPollInterval);
    chartPollInterval = setInterval(pollCharts, 500);
    pollCharts();
}

async function pollCharts() {
    if (!document.getElementById('success-chart')) {
        clearInterval(chartPollInterval);
        chartPollInterval = null;
        return;
    }
    try {
        var response = await fetch('/api/status');
        if (!response.ok) return;
        var status = await response.json();
        var m = status.metrics;

        // Update metric cards
        var rps = document.getElementById('requests-per-sec');
        if (rps) rps.textContent = Math.round(m.requestsPerSecond).toLocaleString();

        var sr = document.getElementById('success-rate');
        if (sr) {
            sr.textContent = m.successRate.toFixed(1) + '%';
            var card = sr.closest('.metric-card');
            var color = m.successRate >= 99 ? '#22c55e' : m.successRate >= 95 ? '#f59e0b' : '#ef4444';
            if (card) card.style.borderColor = color;
            sr.style.color = color;
        }

        var lat = document.getElementById('avg-latency');
        if (lat) lat.textContent = m.avgLatencyMs.toFixed(1) + 'ms';

        // Update charts
        successHistory.push(m.successRate);
        if (successHistory.length > MAX_HISTORY) successHistory.shift();
        throughputHistory.push(m.requestsPerSecond);
        if (throughputHistory.length > MAX_HISTORY) throughputHistory.shift();

        if (successChart) {
            successChart.data.labels = successHistory.map(() => '');
            successChart.data.datasets[0].data = successHistory;
            var rate = m.successRate;
            successChart.data.datasets[0].borderColor = rate >= 99 ? '#22c55e' : rate >= 95 ? '#f59e0b' : '#ef4444';
            successChart.update('none');
        }
        if (throughputChart) {
            throughputChart.data.labels = throughputHistory.map(() => '');
            throughputChart.data.datasets[0].data = throughputHistory;
            throughputChart.update('none');
        }
    } catch (e) { /* ignore */ }
}

// ===============================
// Node Kill Modal
// ===============================
async function showNodeModal(includeLeader) {
    try {
        var response = await fetch('/api/status');
        if (!response.ok) return;
        var status = await response.json();
        var nodeList = document.getElementById('node-list');
        nodeList.innerHTML = '';
        status.cluster.nodes.forEach(function(node) {
            if (!includeLeader && node.isLeader) return;
            var btn = document.createElement('button');
            btn.className = 'btn ' + (node.isLeader ? 'btn-warning' : 'btn-danger');
            btn.textContent = node.id + (node.isLeader ? ' (Leader)' : '');
            btn.addEventListener('click', async function() {
                hideNodeModal();
                await fetch('/api/chaos/kill/' + node.id, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
            });
            nodeList.appendChild(btn);
        });
        document.getElementById('node-modal').classList.remove('hidden');
    } catch (e) { /* ignore */ }
}

function hideNodeModal() {
    document.getElementById('node-modal').classList.add('hidden');
}

async function killLeader() {
    try {
        var response = await fetch('/api/status');
        if (!response.ok) return;
        var status = await response.json();
        if (status.cluster.leaderId !== 'none') {
            await fetch('/api/chaos/kill/' + status.cluster.leaderId, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
        }
    } catch (e) { /* ignore */ }
}

// ===============================
// Rolling Restart
// ===============================
async function toggleRollingRestart() {
    var endpoint = rollingRestartActive ? '/api/chaos/stop-rolling-restart' : '/api/chaos/start-rolling-restart';
    await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    var data = await (await fetch('/api/chaos/rolling-restart-status')).json();
    rollingRestartActive = data.active;
    var btn = document.getElementById('btn-rolling-restart');
    if (btn) {
        btn.textContent = data.active ? 'Stop Rolling Restart' : 'Rolling Restart';
        btn.classList.toggle('btn-active', data.active);
    }
}

// ===============================
// Load Testing Controls
// ===============================
async function uploadLoadConfig() {
    var textarea = document.getElementById('load-config-text');
    var statusSpan = document.getElementById('load-config-status');
    if (!textarea || !textarea.value.trim()) {
        if (statusSpan) statusSpan.innerHTML = '<span class="error">Please enter a configuration</span>';
        return;
    }
    try {
        var response = await fetch('/api/load/config', { method: 'POST', body: textarea.value });
        var data = await response.json();
        if (data.error) {
            statusSpan.innerHTML = '<span class="error">' + escapeHtml(data.error) + '</span>';
        } else {
            statusSpan.innerHTML = '<span class="success">Loaded ' + data.targetCount + ' targets (' + data.totalRps + ' req/s)</span>';
            var info = document.getElementById('load-config-info');
            if (info) info.innerHTML = '<span class="success">' + data.targetCount + ' targets configured</span>';
        }
    } catch (e) {
        statusSpan.innerHTML = '<span class="error">Upload failed</span>';
    }
}

async function loadAction(action) {
    try {
        var response = await fetch('/api/load/' + action, { method: 'POST' });
        var data = await response.json();
        var stateSpan = document.getElementById('load-runner-state');
        if (stateSpan && data.state) {
            stateSpan.textContent = data.state;
            stateSpan.className = 'state-value ' + data.state.toLowerCase();
        }
    } catch (e) { /* ignore */ }
}

async function setTotalRate(rate) {
    await fetch('/api/load/rate/' + rate, { method: 'POST' });
}

async function resetMetrics() {
    await fetch('/api/chaos/reset-metrics', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    successHistory = [];
    throughputHistory = [];
}

function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
