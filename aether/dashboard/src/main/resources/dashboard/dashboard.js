// Aether Dashboard Controller
// Unified dashboard for both AetherNode and Forge

const API_BASE = '/api';
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
let chaosPanelLoaded = false;

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    initCharts();
    initNavigation();
    initPageControls();
    loadChaosPanel();
    startPolling();
});

// ===============================
// Chaos Panel (Dynamic)
// ===============================

async function loadChaosPanel() {
    try {
        const response = await fetch(`${API_BASE}/panel/chaos`);
        if (response.ok) {
            const html = await response.text();
            if (html && html.trim()) {
                const container = document.getElementById('control-panel-container');
                container.innerHTML = html;
                initChaosControls();
                chaosPanelLoaded = true;
            }
        }
    } catch {
        // Chaos panel not available (running on plain Node)
    }
}

function initChaosControls() {
    // Kill node controls
    document.getElementById('btn-kill-node')?.addEventListener('click', () => showNodeModal(false));
    document.getElementById('btn-kill-leader')?.addEventListener('click', async () => {
        const status = await fetchStatus();
        if (status?.cluster?.leaderId && status.cluster.leaderId !== 'none') {
            await apiPost(`/chaos/kill/${status.cluster.leaderId}`);
        }
    });
    document.getElementById('btn-rolling-restart')?.addEventListener('click', () => apiPost('/chaos/rolling-restart'));
    document.getElementById('btn-add-node')?.addEventListener('click', () => apiPost('/chaos/add-node'));

    // Load controls
    document.getElementById('btn-load-1k')?.addEventListener('click', () => setLoad(1000));
    document.getElementById('btn-load-5k')?.addEventListener('click', () => setLoad(5000));
    document.getElementById('btn-load-10k')?.addEventListener('click', () => setLoad(10000));
    document.getElementById('btn-load-25k')?.addEventListener('click', () => setLoad(25000));
    document.getElementById('btn-load-50k')?.addEventListener('click', () => setLoad(50000));
    document.getElementById('btn-load-100k')?.addEventListener('click', () => setLoad(100000));
    document.getElementById('btn-ramp')?.addEventListener('click', rampToNextStep);

    const slider = document.getElementById('load-slider');
    if (slider) {
        slider.addEventListener('input', () => {
            const valueEl = document.getElementById('load-value');
            if (valueEl) valueEl.textContent = formatLoadRate(slider.value);
        });
        slider.addEventListener('change', () => setLoad(parseInt(slider.value)));
    }

    document.getElementById('btn-reset')?.addEventListener('click', async () => {
        await apiPost('/chaos/reset-metrics');
        successHistory = [];
        throughputHistory = [];
    });

    document.getElementById('load-generator-toggle')?.addEventListener('change', e =>
        apiPost('/load/config/enabled', { enabled: e.target.checked }));

    document.getElementById('btn-apply-multiplier')?.addEventListener('click', () => {
        const input = document.getElementById('rate-multiplier');
        if (input) {
            const multiplier = parseFloat(input.value);
            if (!isNaN(multiplier) && multiplier > 0) {
                apiPost('/load/config/multiplier', { multiplier });
            }
        }
    });

    document.getElementById('modal-cancel')?.addEventListener('click', hideNodeModal);
}

function showNodeModal(includeLeader) {
    const nodeList = document.getElementById('node-list');
    if (!nodeList) return;
    nodeList.innerHTML = '';
    nodes.forEach(node => {
        if (!includeLeader && node.isLeader) return;
        const btn = document.createElement('button');
        btn.className = `btn ${node.isLeader ? 'btn-warning' : 'btn-danger'}`;
        btn.textContent = `${node.id}${node.isLeader ? ' (Leader)' : ''}`;
        btn.addEventListener('click', async () => {
            hideNodeModal();
            await apiPost(`/chaos/kill/${node.id}`);
        });
        nodeList.appendChild(btn);
    });
    document.getElementById('node-modal')?.classList.remove('hidden');
}

function hideNodeModal() {
    document.getElementById('node-modal')?.classList.add('hidden');
}

async function setLoad(rate) {
    await apiPost(`/load/set/${rate}`);
    const slider = document.getElementById('load-slider');
    const valueEl = document.getElementById('load-value');
    if (slider) slider.value = rate;
    if (valueEl) valueEl.textContent = formatLoadRate(rate);
}

function formatLoadRate(rate) {
    return rate >= 1000 ? `${(rate / 1000).toFixed(rate % 1000 === 0 ? 0 : 1)}K` : `${rate}`;
}

const LOAD_STEPS = [1000, 5000, 10000, 25000, 50000, 100000];

async function rampToNextStep() {
    const status = await fetchStatus();
    if (!status?.load) return;
    const nextStep = LOAD_STEPS.find(step => step > status.load.currentRate) || LOAD_STEPS[LOAD_STEPS.length - 1];
    if (nextStep > status.load.currentRate) {
        await apiPost('/load/ramp', { targetRate: nextStep, durationMs: 10000 });
    }
}

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
    document.querySelectorAll('.nav-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.page === page);
    });
    document.querySelectorAll('.page').forEach(p => {
        p.classList.toggle('active', p.id === `page-${page}`);
    });
    if (page === 'config') loadConfigPage();
    if (page === 'alerts') loadAlertsPage();
    if (page === 'cluster') loadClusterPage();
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
        options: {
            ...chartOptions,
            scales: {
                ...chartOptions.scales,
                y: {
                    ...chartOptions.scales.y,
                    max: 100,
                    ticks: { ...chartOptions.scales.y.ticks, callback: v => v + '%' }
                }
            }
        }
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
    if (!metrics) return;

    successHistory.push(metrics.successRate || 100);
    if (successHistory.length > MAX_HISTORY) successHistory.shift();

    throughputHistory.push(metrics.requestsPerSecond || 0);
    if (throughputHistory.length > MAX_HISTORY) throughputHistory.shift();

    successChart.data.labels = successHistory.map(() => '');
    successChart.data.datasets[0].data = successHistory;
    const rate = metrics.successRate || 100;
    successChart.data.datasets[0].borderColor = rate >= 99 ? '#22c55e' : rate >= 95 ? '#f59e0b' : '#ef4444';
    successChart.update('none');

    throughputChart.data.labels = throughputHistory.map(() => '');
    throughputChart.data.datasets[0].data = throughputHistory;
    throughputChart.update('none');
}

// ===============================
// Page Controls
// ===============================

function initPageControls() {
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

// ===============================
// API & Polling
// ===============================

async function fetchStatus() {
    try {
        const response = await fetch(`${API_BASE}/status`);
        return response.ok ? await response.json() : null;
    } catch { return null; }
}

async function fetchEvents() {
    try {
        const response = await fetch(`${API_BASE}/events`);
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
        return response.ok ? await response.json() : null;
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
        const response = await fetch(`${API_BASE}/node-metrics`);
        if (response.ok) nodeMetricsData = await response.json();
    } catch {}
}

async function fetchInvocationMetrics() {
    try {
        const response = await fetch(`${API_BASE}/invocation-metrics`);
        if (response.ok) {
            const data = await response.json();
            entryPointMetrics = data.methods || [];
            updateEntryPointsTable();
        }
    } catch {}
}

let sliceStatus = null;

async function fetchSliceStatus() {
    try {
        const response = await fetch(`${API_BASE}/slices/status`);
        if (response.ok) {
            sliceStatus = await response.json();
        }
    } catch {
        sliceStatus = null;
    }
}

function updateEntryPointsTable() {
    const tbody = document.getElementById('entrypoints-body');
    if (!tbody) return;

    if (!entryPointMetrics?.length) {
        tbody.innerHTML = '<tr><td colspan="5" class="placeholder">No entry points</td></tr>';
        return;
    }

    tbody.innerHTML = [...entryPointMetrics]
        .sort((a, b) => (a.method || '').localeCompare(b.method || ''))
        .map(ep => {
            const name = ep.method || ep.name || 'Unknown';
            const total = ep.totalCalls || ep.invocations || 0;
            const successRate = ep.successRate ?? (total > 0 ? ((total - (ep.failures || 0)) / total * 100) : 100);
            const avgLatency = ep.avgLatencyMs ?? ep.avgDurationMs ?? 0;
            const p99Latency = ep.p99LatencyMs ?? ep.p99DurationMs ?? 0;
            const successClass = successRate >= 99 ? 'success' : successRate >= 95 ? 'warning' : 'danger';

            return `<tr>
                <td class="ep-name">${escapeHtml(name)}</td>
                <td>${formatNumber(total)}</td>
                <td class="ep-success ${successClass}">${successRate.toFixed(1)}%</td>
                <td>${avgLatency.toFixed(1)}ms</td>
                <td>${p99Latency.toFixed(0)}ms</td>
            </tr>`;
        }).join('');
}

function formatNumber(num) {
    if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
    if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
    return num.toString();
}

function startPolling() {
    poll();
    setInterval(poll, REFRESH_INTERVAL);
}

async function poll() {
    const status = await fetchStatus();
    if (!status) return;

    nodes = status.cluster?.nodes || [];
    updateMetricsDisplay(status.metrics);
    updateCharts(status.metrics);
    updateLoadDisplay(status.load);

    document.getElementById('uptime').textContent = formatUptime(status.uptimeSeconds || 0);
    document.getElementById('node-count').textContent = status.cluster?.nodeCount || 0;

    await fetchSliceStatus();
    const activeSliceCount = sliceStatus?.slices?.filter(s => s.state === 'ACTIVE')?.length || status.sliceCount || 0;
    document.getElementById('slice-count').textContent = activeSliceCount;

    const newEvents = await fetchEvents();
    if (newEvents.length > 0) updateTimeline(newEvents);

    await fetchNodeMetrics();
    updateNodesList(status.cluster?.nodes || []);

    await fetchInvocationMetrics();
}

function updateMetricsDisplay(metrics) {
    if (!metrics) return;

    document.getElementById('requests-per-sec').textContent =
        Math.round(metrics.requestsPerSecond || 0).toLocaleString();

    const successEl = document.getElementById('success-rate');
    const rate = metrics.successRate ?? 100;
    successEl.textContent = `${rate.toFixed(1)}%`;

    const card = successEl.closest('.metric-card');
    const color = rate >= 99 ? '#22c55e' : rate >= 95 ? '#f59e0b' : '#ef4444';
    if (card) card.style.borderColor = color;
    successEl.style.color = color;

    document.getElementById('avg-latency').textContent =
        `${(metrics.avgLatencyMs || 0).toFixed(1)}ms`;
}

function updateLoadDisplay(load) {
    if (!load || !chaosPanelLoaded) return;

    const slider = document.getElementById('load-slider');
    if (slider && document.activeElement !== slider) {
        slider.value = load.currentRate || 0;
        const valueEl = document.getElementById('load-value');
        if (valueEl) valueEl.textContent = formatLoadRate(load.currentRate || 0);
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function updateTimeline(newEvents) {
    const timeline = document.getElementById('timeline');
    if (!timeline) return;

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
                <span class="event-type ${escapeHtml(event.type || '')}">${event.type || ''}</span>
                <span class="event-message">${escapeHtml(event.message || '')}</span>
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
    if (!isoString) return '--:--';
    return new Date(isoString).toLocaleTimeString('en-US', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });
}

function updateNodesList(clusterNodes) {
    const container = document.getElementById('nodes-list');
    if (!container) return;

    if (!clusterNodes?.length) {
        container.innerHTML = '<div class="node-item placeholder">No nodes available</div>';
        return;
    }

    const slicesPerNode = {};
    if (sliceStatus?.slices) {
        sliceStatus.slices.forEach(slice => {
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
        .sort((a, b) => a.isLeader ? -1 : b.isLeader ? 1 : (a.id || '').localeCompare(b.id || ''))
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
    if (!container) return;

    const data = await apiGet('/controller/config');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load controller config</div>';
        return;
    }
    container.innerHTML = renderConfigItems(data.config || data);
}

async function loadTTMStatus() {
    const container = document.getElementById('ttm-status');
    if (!container) return;

    const data = await apiGet('/ttm/status');
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
    if (!container) return;

    const data = await apiGet('/thresholds');
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
    if (!container) return;

    const data = await apiGet('/controller/status');
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
    if (isBoolean) valueClass += value ? ' enabled' : ' disabled';
    return `<div class="config-item">
        <span class="config-key">${escapeHtml(String(key))}</span>
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
    if (!container) return;

    const data = await apiGet('/alerts/active');
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
    if (!container) return;

    const data = await apiGet('/alerts/history');
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
    await apiPost('/alerts/clear');
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
    if (!container) return;

    const data = await apiGet('/health');
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
    if (!container) return;

    const data = await apiGet('/metrics/comprehensive');
    if (!data) {
        container.innerHTML = '<div class="placeholder">Unable to load comprehensive metrics</div>';
        return;
    }

    let html = '';

    html += '<div class="metrics-section"><div class="metrics-section-title">System</div><div class="metrics-grid">';
    html += renderMetricItem('CPU Usage', formatPercent(data.cpuUsage));
    html += renderMetricItem('Heap Usage', formatPercent(data.heapUsage));
    html += renderMetricItem('Heap Used', `${data.heapUsedMb || 0}MB`);
    html += renderMetricItem('Heap Max', `${data.heapMaxMb || 0}MB`);
    html += '</div></div>';

    if (data.gc) {
        html += '<div class="metrics-section"><div class="metrics-section-title">GC</div><div class="metrics-grid">';
        html += renderMetricItem('Collections', data.gc.collectionCount);
        html += renderMetricItem('Total Pause', `${data.gc.totalPauseMs}ms`);
        html += '</div></div>';
    }

    if (data.eventLoop) {
        html += '<div class="metrics-section"><div class="metrics-section-title">Event Loop</div><div class="metrics-grid">';
        html += renderMetricItem('Lag', `${data.eventLoop.lagMs}ms`);
        html += renderMetricItem('Pending Tasks', data.eventLoop.pendingTasks);
        html += '</div></div>';
    }

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
    if (!container) return;

    const data = await apiGet('/slices/status');
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
