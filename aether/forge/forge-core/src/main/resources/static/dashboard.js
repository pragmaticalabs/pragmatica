// Aether Forge - Dashboard Controller (Single-poll + Chart.js)

// ===============================
// State
// ===============================
var successChart = null;
var throughputChart = null;
var successHistory = [];
var throughputHistory = [];
var MAX_HISTORY = 60;
var rollingRestartActive = false;
var pollInterval = null;

// ===============================
// Tab Management
// ===============================
function switchTab(btn) {
    document.querySelectorAll('.nav-tab').forEach(function(t) { t.classList.remove('active'); });
    btn.classList.add('active');
}

// Re-initialize charts when Overview tab loads via HTMX
document.body.addEventListener('htmx:afterSwap', function(event) {
    if (event.detail.target.id === 'tab-content') {
        var canvas = document.getElementById('success-chart');
        if (canvas) {
            initCharts();
        }
        startPolling();
    }
});

// ===============================
// Single Poll Loop (500ms)
// ===============================
function startPolling() {
    if (pollInterval) clearInterval(pollInterval);
    pollInterval = setInterval(poll, 500);
    poll();
}

async function poll() {
    try {
        var response = await fetch('/api/status');
        if (!response.ok) return;
        var s = await response.json();
        updateUptime(s.uptimeSeconds);
        updatePerformance(s.metrics);
        updateCharts(s.metrics);
        updateNodes(s.cluster, s.nodeMetrics, s.slices, s.targetClusterSize);
        updateSlices(s.slices);
        updateMetrics(s.metrics, s.nodeMetrics);
        updateLoadTargets(s.loadTargets);
    } catch (e) { /* ignore */ }
}

// ===============================
// Uptime
// ===============================
function updateUptime(secs) {
    var el = document.getElementById('nav-uptime');
    if (el) {
        var m = Math.floor(secs / 60);
        var ss = secs % 60;
        el.textContent = m + ':' + (ss < 10 ? '0' : '') + ss;
    }
}

// ===============================
// Performance Cards
// ===============================
function updatePerformance(m) {
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
}

// ===============================
// Charts (Chart.js)
// ===============================
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
        options: { ...chartOptions, scales: { ...chartOptions.scales, y: { ...chartOptions.scales.y, max: 100, ticks: { ...chartOptions.scales.y.ticks, callback: function(v) { return v + '%'; } } } } }
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

function updateCharts(m) {
    successHistory.push(m.successRate);
    if (successHistory.length > MAX_HISTORY) successHistory.shift();
    throughputHistory.push(m.requestsPerSecond);
    if (throughputHistory.length > MAX_HISTORY) throughputHistory.shift();

    if (successChart) {
        successChart.data.labels = successHistory.map(function() { return ''; });
        successChart.data.datasets[0].data = successHistory;
        successChart.data.datasets[0].borderColor = m.successRate >= 99 ? '#22c55e' : m.successRate >= 95 ? '#f59e0b' : '#ef4444';
        successChart.update('none');
    }
    if (throughputChart) {
        throughputChart.data.labels = throughputHistory.map(function() { return ''; });
        throughputChart.data.datasets[0].data = throughputHistory;
        throughputChart.update('none');
    }
}

// ===============================
// Cluster Nodes
// ===============================
function updateNodes(cluster, nodeMetrics, slices, targetSize) {
    var header = document.getElementById('nodes-header');
    if (header) {
        header.innerHTML = 'Cluster Nodes<span class="panel-badge">Target Size: ' + targetSize + '</span>';
    }
    var list = document.getElementById('nodes-list');
    if (!list) return;
    if (!cluster.nodes.length) {
        list.innerHTML = '<div class="node-item placeholder">No nodes available</div>';
        return;
    }
    var sorted = cluster.nodes.slice().sort(function(a, b) {
        return a.isLeader ? -1 : b.isLeader ? 1 : a.id.localeCompare(b.id);
    });
    var html = '';
    for (var i = 0; i < sorted.length; i++) {
        var node = sorted[i];
        var nm = findNodeMetrics(nodeMetrics, node.id);
        var cpu = nm ? Math.round(nm.cpuUsage * 100) : '?';
        var heap = nm ? (nm.heapUsedMb + '/' + nm.heapMaxMb) : '?/?';
        var leaderCls = node.isLeader ? ' leader' : '';
        html += '<div class="node-item' + leaderCls + '">';
        html += '<span class="node-id">' + escapeHtml(node.id) + '</span>';
        if (node.isLeader) html += '<span class="leader-badge">LEADER</span>';
        html += '<span class="node-stats"><span>CPU ' + cpu + '%</span><span>Heap ' + heap + 'MB</span></span>';
        html += '<span class="node-slices">' + renderNodeSlices(node.id, slices) + '</span>';
        html += '</div>';
    }
    list.innerHTML = html;
}

function findNodeMetrics(nodeMetrics, nodeId) {
    if (!nodeMetrics) return null;
    for (var i = 0; i < nodeMetrics.length; i++) {
        if (nodeMetrics[i].nodeId === nodeId) return nodeMetrics[i];
    }
    return null;
}

function renderNodeSlices(nodeId, slices) {
    if (!slices || !slices.length) return '<span class="no-slices">No slices</span>';
    var html = '';
    var found = false;
    for (var i = 0; i < slices.length; i++) {
        var slice = slices[i];
        for (var j = 0; j < slice.instances.length; j++) {
            if (slice.instances[j].nodeId === nodeId) {
                var parts = slice.artifact.split(':');
                var name = parts.length >= 2 ? parts[1] : slice.artifact;
                var cls = slice.instances[j].state === 'ACTIVE' ? 'active'
                        : slice.instances[j].state === 'LOADING' ? 'loading' : 'inactive';
                html += '<span class="slice-tag ' + cls + '" title="' + escapeHtml(slice.instances[j].state) + '">' + escapeHtml(name) + '</span>';
                found = true;
            }
        }
    }
    return found ? html : '<span class="no-slices">No slices</span>';
}

// ===============================
// Slices Status
// ===============================
function updateSlices(slices) {
    var header = document.getElementById('slices-header');
    if (header) {
        var active = 0;
        if (slices) {
            for (var i = 0; i < slices.length; i++) {
                if (slices[i].state === 'ACTIVE') active++;
            }
        }
        header.innerHTML = 'Slices Status<span class="panel-badge">' + active + ' active</span>';
    }
    var container = document.getElementById('slices-content');
    if (!container) return;
    if (!slices || !slices.length) {
        container.innerHTML = '<div class="placeholder">No slices deployed</div>';
        return;
    }
    var html = '';
    for (var i = 0; i < slices.length; i++) {
        var s = slices[i];
        var stCls = s.state === 'ACTIVE' ? '' : (s.state === 'LOADING' || s.state === 'ACTIVATING') ? ' loading' : ' failed';
        html += '<div class="slice-item' + stCls + '">';
        html += '<div class="slice-header">';
        html += '<span class="slice-artifact">' + escapeHtml(s.artifact) + '</span>';
        html += '<span class="slice-state ' + s.state + '">' + s.state + '</span>';
        html += '</div><div class="slice-instances">';
        if (!s.instances.length) {
            html += '<span class="no-slices">No instances</span>';
        } else {
            for (var j = 0; j < s.instances.length; j++) {
                var inst = s.instances[j];
                var hCls = inst.state === 'ACTIVE' ? ' healthy' : '';
                html += '<span class="instance-badge' + hCls + '">' + escapeHtml(inst.nodeId) + ': ' + inst.state + '</span>';
            }
        }
        html += '</div></div>';
    }
    container.innerHTML = html;
}

// ===============================
// Metrics Panel (from extended status)
// ===============================
function updateMetrics(m, nodeMetrics) {
    var container = document.getElementById('metrics-content');
    if (!container) return;
    // Aggregate node-level metrics for the mini cards
    var totalCpu = 0, totalHeap = 0, maxHeap = 0, count = nodeMetrics ? nodeMetrics.length : 0;
    if (nodeMetrics) {
        for (var i = 0; i < nodeMetrics.length; i++) {
            totalCpu += nodeMetrics[i].cpuUsage;
            totalHeap += nodeMetrics[i].heapUsedMb;
            maxHeap += nodeMetrics[i].heapMaxMb;
        }
    }
    var avgCpu = count > 0 ? (totalCpu / count * 100).toFixed(1) + '%' : '?';
    var heapStr = count > 0 ? totalHeap + '/' + maxHeap + 'MB' : '?';
    var html = '<div class="metrics-grid">';
    html += miniMetric(avgCpu, 'Avg CPU', 'cpu');
    html += miniMetric(heapStr, 'Total Heap', 'heap');
    html += miniMetric(Math.round(m.requestsPerSecond).toLocaleString(), 'Req/s', 'invocations');
    html += miniMetric(m.successRate.toFixed(1) + '%', 'Success', 'latency');
    html += miniMetric(m.avgLatencyMs.toFixed(1) + 'ms', 'Avg Latency', 'latency');
    html += miniMetric((m.totalSuccess + m.totalFailures).toLocaleString(), 'Total Reqs', 'invocations');
    html += miniMetric(m.totalSuccess.toLocaleString(), 'Success', 'invocations');
    html += miniMetric(m.totalFailures.toLocaleString(), 'Failures', 'error');
    html += '</div>';
    container.innerHTML = html;
}

function miniMetric(value, label, cls) {
    return '<div class="mini-metric ' + cls + '"><span class="mini-metric-value">' + value + '</span><span class="mini-metric-label">' + label + '</span></div>';
}

// ===============================
// Load Targets (Per-Target Metrics)
// ===============================
function updateLoadTargets(targets) {
    var container = document.getElementById('load-metrics-container');
    if (!container) return;
    if (!targets || !targets.length) {
        container.innerHTML = '<div class="placeholder">No targets running</div>';
        return;
    }
    var html = '<table class="load-metrics-table-inner"><thead><tr>';
    html += '<th>Target</th><th>Rate (actual/target)</th><th>Requests</th>';
    html += '<th>Success</th><th>Failures</th><th>Success %</th><th>Avg Latency</th><th>Remaining</th>';
    html += '</tr></thead><tbody>';
    for (var i = 0; i < targets.length; i++) {
        var t = targets[i];
        html += '<tr>';
        html += '<td>' + escapeHtml(t.name) + '</td>';
        html += '<td>' + t.actualRate + ' / ' + t.targetRate + '</td>';
        html += '<td>' + t.requests + '</td>';
        html += '<td class="success">' + t.success + '</td>';
        html += '<td class="error">' + t.failures + '</td>';
        html += '<td>' + t.successRate.toFixed(1) + '%</td>';
        html += '<td>' + t.avgLatencyMs.toFixed(1) + 'ms</td>';
        html += '<td>' + (t.remaining || '-') + '</td>';
        html += '</tr>';
    }
    html += '</tbody></table>';
    container.innerHTML = html;
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
