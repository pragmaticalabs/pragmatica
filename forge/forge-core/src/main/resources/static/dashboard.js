// Aether Forge - Dashboard Controller

const API_BASE = '';
const REFRESH_INTERVAL = 500;
const CONFIG_REFRESH_INTERVAL = 5000;

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
let currentPage = 'overview';

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    initCharts();
    initControls();
    initNavigation();
    startPolling();
});

// ===============================
// Navigation
// ===============================

function initNavigation() {
    document.querySelectorAll('.nav-tab').forEach(tab => {
        tab.addEventListener('click', () => switchPage(tab.dataset.page));
    });
}

function switchPage(page) {
    currentPage = page;

    // Update tab styles
    document.querySelectorAll('.nav-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.page === page);
    });

    // Update page visibility
    document.querySelectorAll('.page').forEach(p => {
        p.classList.toggle('active', p.id === `page-${page}`);
    });

    // Load page-specific data
    if (page === 'config') loadConfigPage();
    if (page === 'alerts') loadAlertsPage();
    if (page === 'cluster') loadClusterPage();
    if (page === 'load-testing') loadLoadTestingPage();
}

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

    // Config page controls
    document.getElementById('btn-refresh-controller')?.addEventListener('click', loadControllerConfig);
    document.getElementById('btn-refresh-ttm')?.addEventListener('click', loadTTMStatus);
    document.getElementById('btn-refresh-thresholds')?.addEventListener('click', loadThresholds);
    document.getElementById('btn-refresh-controller-status')?.addEventListener('click', loadControllerStatus);

    // Alerts page controls
    document.getElementById('btn-refresh-alerts')?.addEventListener('click', loadAlertsPage);
    document.getElementById('btn-clear-alerts')?.addEventListener('click', clearAlerts);

    // Cluster page controls
    document.getElementById('btn-refresh-health')?.addEventListener('click', loadClusterHealth);
    document.getElementById('btn-refresh-metrics')?.addEventListener('click', loadComprehensiveMetrics);
    document.getElementById('btn-refresh-slices')?.addEventListener('click', loadSlicesStatus);
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

async function apiGet(endpoint) {
    try {
        const response = await fetch(`${API_BASE}${endpoint}`);
        return response.ok ? await response.json() : null;
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

let realSliceStatus = null;

async function fetchSliceStatus() {
    try {
        const response = await fetch(`${API_BASE}/api/slices/status`);
        if (response.ok) {
            realSliceStatus = await response.json();
        }
    } catch {
        realSliceStatus = null;
    }
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

    // Fetch real slice status from cluster
    await fetchSliceStatus();
    // Count unique active slices (aggregate state per artifact)
    const activeSliceCount = realSliceStatus?.slices?.filter(s => s.state === 'ACTIVE')?.length || status.sliceCount || 0;
    document.getElementById('slice-count').textContent = activeSliceCount;

    const newEvents = await fetchEvents();
    updateTimeline(newEvents);

    await fetchNodeMetrics();
    updateNodesList(status.cluster.nodes);

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

function updateNodesList(clusterNodes) {
    const container = document.getElementById('nodes-list');
    if (!clusterNodes?.length) {
        container.innerHTML = '<div class="node-item placeholder">No nodes available</div>';
        return;
    }

    // Get real slice data per node from realSliceStatus
    const slicesPerNode = {};
    if (realSliceStatus?.slices) {
        realSliceStatus.slices.forEach(slice => {
            const artifactParts = (slice.artifact || '').split(':');
            const shortName = artifactParts.length >= 2 ? artifactParts[1] : slice.artifact;
            if (slice.instances) {
                slice.instances.forEach(instance => {
                    const nodeId = instance.nodeId || 'unknown';
                    if (!slicesPerNode[nodeId]) slicesPerNode[nodeId] = [];
                    slicesPerNode[nodeId].push({
                        name: shortName,
                        state: instance.state
                    });
                });
            }
        });
    }

    container.innerHTML = [...clusterNodes]
        .sort((a, b) => a.isLeader ? -1 : b.isLeader ? 1 : a.id.localeCompare(b.id))
        .map(node => {
            const metrics = nodeMetricsData.find(m => m.nodeId === node.id);
            const cpu = metrics ? (metrics.cpuUsage * 100).toFixed(0) : '?';
            const heap = metrics ? `${metrics.heapUsedMb}/${metrics.heapMaxMb}` : '?/?';
            const nodeSlices = slicesPerNode[node.id] || [];
            const slices = nodeSlices.map(s => {
                const stateClass = s.state === 'ACTIVE' ? 'active' : s.state === 'LOADING' ? 'loading' : 'inactive';
                return `<span class="slice-tag ${stateClass}" title="${s.state}">${escapeHtml(s.name)}</span>`;
            }).join('');

            return `<div class="node-item ${node.isLeader ? 'leader' : ''}">
                <span class="node-id">${node.id}</span>
                ${node.isLeader ? '<span class="leader-badge">LEADER</span>' : ''}
                <span class="node-stats"><span>CPU ${cpu}%</span><span>Heap ${heap}MB</span></span>
                <span class="node-slices">${slices || '<span class="no-slices">No slices</span>'}</span>
            </div>`;
        }).join('');
}

// ===============================
// Configuration Page
// ===============================

async function loadConfigPage() {
    await Promise.all([
        loadControllerConfig(),
        loadTTMStatus(),
        loadThresholds(),
        loadControllerStatus()
    ]);
}

async function loadControllerConfig() {
    const container = document.getElementById('controller-config');
    const data = await apiGet('/api/cluster/controller/config');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load controller config</div>';
        return;
    }

    container.innerHTML = renderConfigItems(data.config || data);
}

async function loadTTMStatus() {
    const container = document.getElementById('ttm-status');
    const data = await apiGet('/api/cluster/ttm/status');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load TTM status</div>';
        return;
    }

    const items = [
        { key: 'Config Enabled', value: data.configEnabled, isBoolean: true },
        { key: 'Active', value: data.active, isBoolean: true },
        { key: 'State', value: data.state },
        { key: 'Model Path', value: data.modelPath || 'N/A' },
        { key: 'Input Window', value: `${data.inputWindowMinutes} min` },
        { key: 'Eval Interval', value: `${data.evaluationIntervalMs}ms` },
        { key: 'Confidence', value: data.confidenceThreshold },
        { key: 'Has Forecast', value: data.hasForecast, isBoolean: true }
    ];

    if (data.lastForecast) {
        items.push(
            { key: 'Forecast Time', value: new Date(data.lastForecast.timestamp).toLocaleTimeString() },
            { key: 'Forecast Confidence', value: data.lastForecast.confidence?.toFixed(2) },
            { key: 'Recommendation', value: data.lastForecast.recommendation }
        );
    }

    container.innerHTML = items.map(item => renderConfigItem(item.key, item.value, item.isBoolean)).join('');
}

async function loadThresholds() {
    const container = document.getElementById('thresholds-config');
    const data = await apiGet('/api/cluster/thresholds');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load thresholds</div>';
        return;
    }

    if (data.thresholds && Object.keys(data.thresholds).length > 0) {
        container.innerHTML = Object.entries(data.thresholds).map(([metric, config]) => {
            return `<div class="config-item">
                <span class="config-key">${escapeHtml(metric)}</span>
                <span class="config-value">${config.warning}/${config.critical}</span>
            </div>`;
        }).join('');
    } else {
        container.innerHTML = '<div class="placeholder">No thresholds configured</div>';
    }
}

async function loadControllerStatus() {
    const container = document.getElementById('controller-status');
    const data = await apiGet('/api/cluster/controller/status');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load controller status</div>';
        return;
    }

    const items = [
        { key: 'Enabled', value: data.enabled, isBoolean: true },
        { key: 'Evaluation Interval', value: `${data.evaluationIntervalMs}ms` }
    ];

    container.innerHTML = items.map(item => renderConfigItem(item.key, item.value, item.isBoolean)).join('');
}

function renderConfigItems(obj, prefix = '') {
    let html = '';
    for (const [key, value] of Object.entries(obj)) {
        const displayKey = prefix ? `${prefix}.${key}` : key;
        if (typeof value === 'object' && value !== null) {
            html += renderConfigItems(value, displayKey);
        } else {
            html += renderConfigItem(displayKey, value, typeof value === 'boolean');
        }
    }
    return html;
}

function renderConfigItem(key, value, isBoolean = false) {
    let valueClass = 'config-value';
    if (isBoolean) {
        valueClass += value ? ' enabled' : ' disabled';
    }
    return `<div class="config-item">
        <span class="config-key">${escapeHtml(key)}</span>
        <span class="${valueClass}">${value}</span>
    </div>`;
}

// ===============================
// Alerts Page
// ===============================

async function loadAlertsPage() {
    await Promise.all([
        loadActiveAlerts(),
        loadAlertHistory()
    ]);
}

async function loadActiveAlerts() {
    const container = document.getElementById('active-alerts');
    const data = await apiGet('/api/cluster/alerts/active');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load active alerts</div>';
        return;
    }

    const alerts = data.alerts || [];
    if (alerts.length === 0) {
        container.innerHTML = '<div class="no-alerts">No active alerts</div>';
        return;
    }

    container.innerHTML = alerts.map(alert => renderAlertItem(alert)).join('');
}

async function loadAlertHistory() {
    const container = document.getElementById('alert-history');
    const data = await apiGet('/api/cluster/alerts/history');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load alert history</div>';
        return;
    }

    const alerts = data.alerts || [];
    if (alerts.length === 0) {
        container.innerHTML = '<div class="placeholder">No alert history</div>';
        return;
    }

    container.innerHTML = alerts.slice(0, 50).map(alert => renderAlertItem(alert)).join('');
}

function renderAlertItem(alert) {
    const severityClass = (alert.severity || 'WARNING').toLowerCase();
    return `<div class="alert-item ${severityClass}">
        <span class="alert-severity ${alert.severity || 'WARNING'}">${alert.severity || 'WARNING'}</span>
        <span class="alert-metric">${escapeHtml(alert.metric || alert.type || 'Unknown')}</span>
        <span class="alert-message">${escapeHtml(alert.message || '')}</span>
        <span class="alert-time">${alert.timestamp ? formatEventTime(alert.timestamp) : ''}</span>
    </div>`;
}

async function clearAlerts() {
    await apiPost('/api/cluster/alerts/clear');
    await loadAlertsPage();
}

// ===============================
// Cluster Page
// ===============================

async function loadClusterPage() {
    await Promise.all([
        loadClusterHealth(),
        loadComprehensiveMetrics(),
        loadSlicesStatus()
    ]);
}

async function loadClusterHealth() {
    const container = document.getElementById('cluster-health');
    const data = await apiGet('/api/cluster/health');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load cluster health</div>';
        return;
    }

    const status = data.status || 'unknown';
    const indicatorClass = status === 'healthy' ? '' : status === 'degraded' ? 'degraded' : 'unhealthy';
    const textClass = status === 'healthy' ? 'healthy' : status === 'degraded' ? 'degraded' : 'unhealthy';

    let html = `<div class="health-status">
        <div class="health-indicator ${indicatorClass}"></div>
        <span class="health-text ${textClass}">${status.toUpperCase()}</span>
    </div>`;

    const items = [
        { key: 'Quorum', value: data.quorum, isBoolean: true },
        { key: 'Node Count', value: data.nodeCount },
        { key: 'Slice Count', value: data.sliceCount }
    ];

    html += items.map(item => renderConfigItem(item.key, item.value, item.isBoolean)).join('');
    container.innerHTML = html;
}

async function loadComprehensiveMetrics() {
    const container = document.getElementById('comprehensive-metrics');
    const data = await apiGet('/api/cluster/metrics/comprehensive');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load comprehensive metrics</div>';
        return;
    }

    let html = '';

    // CPU & Memory
    html += '<div class="metrics-section"><div class="metrics-section-title">System</div><div class="metrics-grid">';
    html += renderMetricItem('CPU Usage', formatPercent(data.cpuUsage));
    html += renderMetricItem('Heap Usage', formatPercent(data.heapUsage));
    html += renderMetricItem('Heap Used', `${data.heapUsedMb || 0}MB`);
    html += renderMetricItem('Heap Max', `${data.heapMaxMb || 0}MB`);
    html += '</div></div>';

    // GC
    if (data.gc) {
        html += '<div class="metrics-section"><div class="metrics-section-title">GC</div><div class="metrics-grid">';
        html += renderMetricItem('Collections', data.gc.collectionCount);
        html += renderMetricItem('Total Pause', `${data.gc.totalPauseMs}ms`);
        html += '</div></div>';
    }

    // Event Loop
    if (data.eventLoop) {
        html += '<div class="metrics-section"><div class="metrics-section-title">Event Loop</div><div class="metrics-grid">';
        html += renderMetricItem('Lag', `${data.eventLoop.lagMs}ms`);
        html += renderMetricItem('Pending Tasks', data.eventLoop.pendingTasks);
        html += '</div></div>';
    }

    // Invocations
    html += '<div class="metrics-section"><div class="metrics-section-title">Invocations</div><div class="metrics-grid">';
    html += renderMetricItem('Total', formatNumber(data.totalInvocations || 0));
    html += renderMetricItem('Avg Latency', `${(data.avgLatencyMs || 0).toFixed(1)}ms`);
    html += renderMetricItem('Error Rate', formatPercent(data.errorRate));
    html += '</div></div>';

    container.innerHTML = html;
}

function renderMetricItem(name, value) {
    return `<div class="metric-item">
        <span class="metric-name">${escapeHtml(name)}</span>
        <span class="metric-val">${escapeHtml(String(value))}</span>
    </div>`;
}

function formatPercent(value) {
    if (value === undefined || value === null) return 'N/A';
    return `${(value * 100).toFixed(1)}%`;
}

async function loadSlicesStatus() {
    const container = document.getElementById('slices-status');
    const data = await apiGet('/api/slices/status');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load slices status</div>';
        return;
    }

    const slices = data.slices || [];
    if (slices.length === 0) {
        container.innerHTML = '<div class="placeholder">No slices deployed</div>';
        return;
    }

    container.innerHTML = slices.map(slice => {
        const stateClass = slice.state === 'ACTIVE' ? '' :
                          slice.state === 'LOADING' || slice.state === 'ACTIVATING' ? 'loading' : 'failed';

        const instances = (slice.instances || []).map(inst => {
            const healthClass = inst.state === 'ACTIVE' ? 'healthy' : '';
            return `<span class="instance-badge ${healthClass}">${escapeHtml(inst.nodeId)}: ${inst.state}</span>`;
        }).join('');

        return `<div class="slice-item ${stateClass}">
            <div class="slice-header">
                <span class="slice-artifact">${escapeHtml(slice.artifact)}</span>
                <span class="slice-state ${slice.state}">${slice.state}</span>
            </div>
            <div class="slice-instances">${instances || '<span class="no-slices">No instances</span>'}</div>
        </div>`;
    }).join('');
}

// ===============================
// Load Testing Page
// ===============================

let loadTestingPollInterval = null;

function loadLoadTestingPage() {
    initLoadTestingControls();
    startLoadTestingPolling();
    refreshLoadConfig();
    refreshLoadStatus();
}

function initLoadTestingControls() {
    // Upload config button
    const uploadBtn = document.getElementById('btn-upload-config');
    if (uploadBtn && !uploadBtn._initialized) {
        uploadBtn.addEventListener('click', uploadLoadConfig);
        uploadBtn._initialized = true;
    }

    // Control buttons
    const startBtn = document.getElementById('btn-load-start');
    const pauseBtn = document.getElementById('btn-load-pause');
    const resumeBtn = document.getElementById('btn-load-resume');
    const stopBtn = document.getElementById('btn-load-stop');

    if (startBtn && !startBtn._initialized) {
        startBtn.addEventListener('click', () => loadAction('start'));
        startBtn._initialized = true;
    }
    if (pauseBtn && !pauseBtn._initialized) {
        pauseBtn.addEventListener('click', () => loadAction('pause'));
        pauseBtn._initialized = true;
    }
    if (resumeBtn && !resumeBtn._initialized) {
        resumeBtn.addEventListener('click', () => loadAction('resume'));
        resumeBtn._initialized = true;
    }
    if (stopBtn && !stopBtn._initialized) {
        stopBtn.addEventListener('click', () => loadAction('stop'));
        stopBtn._initialized = true;
    }
}

function startLoadTestingPolling() {
    if (loadTestingPollInterval) {
        clearInterval(loadTestingPollInterval);
    }
    loadTestingPollInterval = setInterval(() => {
        if (currentPage === 'load-testing') {
            refreshLoadStatus();
        }
    }, 1000);
}

async function uploadLoadConfig() {
    const textarea = document.getElementById('load-config-text');
    const statusSpan = document.getElementById('load-config-status');
    const configInfo = document.getElementById('load-config-info');

    if (!textarea.value.trim()) {
        statusSpan.innerHTML = '<span class="error">Please enter a configuration</span>';
        return;
    }

    try {
        const response = await fetch('/api/load/config', {
            method: 'POST',
            body: textarea.value
        });
        const data = await response.json();

        if (data.error) {
            statusSpan.innerHTML = `<span class="error">${escapeHtml(data.error)}</span>`;
        } else {
            statusSpan.innerHTML = `<span class="success">Loaded ${data.targetCount} targets (${data.totalRps} req/s)</span>`;
            configInfo.innerHTML = `<span class="success">${data.targetCount} targets configured, ${data.totalRps} total req/s</span>`;
        }
    } catch (e) {
        statusSpan.innerHTML = `<span class="error">Upload failed: ${e.message}</span>`;
    }
}

async function loadAction(action) {
    try {
        const response = await fetch(`/api/load/${action}`, { method: 'POST' });
        const data = await response.json();

        const stateSpan = document.getElementById('load-runner-state');
        if (data.state) {
            stateSpan.textContent = data.state;
            stateSpan.className = 'state-value ' + data.state.toLowerCase();
        }
        if (data.error) {
            const statusSpan = document.getElementById('load-config-status');
            statusSpan.innerHTML = `<span class="error">${escapeHtml(data.error)}</span>`;
        }
    } catch (e) {
        console.error('Load action failed:', e);
    }
}

async function refreshLoadConfig() {
    try {
        const response = await fetch('/api/load/config');
        const data = await response.json();

        const configInfo = document.getElementById('load-config-info');
        if (data.targetCount > 0) {
            configInfo.innerHTML = `<span class="success">${data.targetCount} targets configured, ${data.totalRps} total req/s</span>`;
        } else {
            configInfo.innerHTML = '<span>No configuration loaded</span>';
        }
    } catch (e) {
        console.error('Failed to load config:', e);
    }
}

async function refreshLoadStatus() {
    try {
        const response = await fetch('/api/load/status');
        const data = await response.json();

        // Update state
        const stateSpan = document.getElementById('load-runner-state');
        if (stateSpan) {
            stateSpan.textContent = data.state;
            stateSpan.className = 'state-value ' + data.state.toLowerCase();
        }

        // Update targets table
        const tbody = document.getElementById('load-targets-body');
        if (tbody) {
            if (!data.targets || data.targets.length === 0) {
                tbody.innerHTML = '<tr><td colspan="8" class="placeholder">No targets running</td></tr>';
            } else {
                tbody.innerHTML = data.targets.map(t => `
                    <tr>
                        <td>${escapeHtml(t.name)}</td>
                        <td>${t.actualRate} / ${t.targetRate}</td>
                        <td>${t.requests.toLocaleString()}</td>
                        <td class="success">${t.success.toLocaleString()}</td>
                        <td class="error">${t.failures.toLocaleString()}</td>
                        <td>${t.successRate.toFixed(1)}%</td>
                        <td>${t.avgLatencyMs.toFixed(1)}ms</td>
                        <td>${t.remaining || '-'}</td>
                    </tr>
                `).join('');
            }
        }
    } catch (e) {
        console.error('Failed to refresh load status:', e);
    }
}
