// Aether Resilience Demo - Dashboard Controller

const API_BASE = '';
const REFRESH_INTERVAL = 500;

// State
let nodes = [];
let events = [];
let successChart = null;
let throughputChart = null;
let successHistory = [];
let throughputHistory = [];
const MAX_HISTORY = 60; // 30 seconds at 500ms intervals

// D3 Topology
let svg, simulation;
let width, height;

// Initialize on load
document.addEventListener('DOMContentLoaded', () => {
    initTopology();
    initCharts();
    initControls();
    startPolling();
});

// ===============================
// Topology Visualization (D3.js)
// ===============================

function initTopology() {
    const container = document.getElementById('cluster-viz');
    width = container.clientWidth;
    height = container.clientHeight || 300;

    svg = d3.select('#topology-svg')
        .attr('width', width)
        .attr('height', height);

    // Define arrow marker for links
    svg.append('defs').append('marker')
        .attr('id', 'arrowhead')
        .attr('viewBox', '-0 -5 10 10')
        .attr('refX', 25)
        .attr('refY', 0)
        .attr('orient', 'auto')
        .attr('markerWidth', 6)
        .attr('markerHeight', 6)
        .append('path')
        .attr('d', 'M0,-5L10,0L0,5')
        .attr('fill', '#2a2a3a');

    simulation = d3.forceSimulation()
        .force('link', d3.forceLink().id(d => d.id).distance(100))
        .force('charge', d3.forceManyBody().strength(-300))
        .force('center', d3.forceCenter(width / 2, height / 2))
        .force('collision', d3.forceCollide().radius(40));
}

function updateTopology(clusterData) {
    if (!clusterData || !clusterData.nodes) return;

    const nodeData = clusterData.nodes.map(n => ({
        id: n.id,
        isLeader: n.isLeader,
        state: n.state
    }));

    // Create links between all nodes (full mesh)
    const links = [];
    for (let i = 0; i < nodeData.length; i++) {
        for (let j = i + 1; j < nodeData.length; j++) {
            links.push({
                source: nodeData[i].id,
                target: nodeData[j].id
            });
        }
    }

    // Update links
    const link = svg.selectAll('.link')
        .data(links, d => `${d.source.id || d.source}-${d.target.id || d.target}`);

    link.exit().remove();

    link.enter()
        .append('line')
        .attr('class', 'link')
        .merge(link);

    // Update nodes
    const node = svg.selectAll('.node')
        .data(nodeData, d => d.id);

    node.exit()
        .transition()
        .duration(300)
        .style('opacity', 0)
        .remove();

    const nodeEnter = node.enter()
        .append('g')
        .attr('class', 'node')
        .call(d3.drag()
            .on('start', dragStarted)
            .on('drag', dragged)
            .on('end', dragEnded));

    nodeEnter.append('circle')
        .attr('r', 0)
        .transition()
        .duration(500)
        .attr('r', 25);

    nodeEnter.append('text')
        .attr('dy', 4)
        .text(d => d.id.replace('node-', 'N'));

    // Update all nodes
    svg.selectAll('.node')
        .attr('class', d => `node ${d.isLeader ? 'leader' : 'healthy'}`);

    // Update simulation
    simulation.nodes(nodeData);
    simulation.force('link').links(links);
    simulation.alpha(0.3).restart();

    // Remove previous tick handler before adding new one
    simulation.on('tick', null);
    simulation.on('tick', () => {
        svg.selectAll('.link')
            .attr('x1', d => d.source.x)
            .attr('y1', d => d.source.y)
            .attr('x2', d => d.target.x)
            .attr('y2', d => d.target.y);

        svg.selectAll('.node')
            .attr('transform', d => `translate(${d.x},${d.y})`);
    });
}

function dragStarted(event, d) {
    if (!event.active) simulation.alphaTarget(0.3).restart();
    d.fx = d.x;
    d.fy = d.y;
}

function dragged(event, d) {
    d.fx = event.x;
    d.fy = event.y;
}

function dragEnded(event, d) {
    if (!event.active) simulation.alphaTarget(0);
    d.fx = null;
    d.fy = null;
}

// ===============================
// Charts (Chart.js)
// ===============================

function initCharts() {
    // Success Rate Chart
    const successCtx = document.getElementById('success-chart').getContext('2d');
    successChart = new Chart(successCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: 'Success Rate',
                data: [],
                borderColor: '#22c55e',
                backgroundColor: 'rgba(34, 197, 94, 0.1)',
                fill: true,
                tension: 0.4,
                pointRadius: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: {
                    display: false
                },
                y: {
                    min: 0,
                    max: 100,
                    grid: {
                        color: '#2a2a3a'
                    },
                    ticks: {
                        color: '#a0a0b0',
                        callback: v => v + '%'
                    }
                }
            },
            animation: {
                duration: 0
            }
        }
    });

    // Throughput Chart
    const throughputCtx = document.getElementById('throughput-chart').getContext('2d');
    throughputChart = new Chart(throughputCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: 'Throughput',
                data: [],
                borderColor: '#06b6d4',
                backgroundColor: 'rgba(6, 182, 212, 0.1)',
                fill: true,
                tension: 0.4,
                pointRadius: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: {
                    display: false
                },
                y: {
                    min: 0,
                    grid: {
                        color: '#2a2a3a'
                    },
                    ticks: {
                        color: '#a0a0b0'
                    }
                }
            },
            animation: {
                duration: 0
            }
        }
    });
}

function updateCharts(metrics) {
    const now = new Date().toLocaleTimeString();

    // Update success history
    successHistory.push(metrics.successRate);
    if (successHistory.length > MAX_HISTORY) {
        successHistory.shift();
    }

    // Update throughput history
    throughputHistory.push(metrics.requestsPerSecond);
    if (throughputHistory.length > MAX_HISTORY) {
        throughputHistory.shift();
    }

    // Update success chart
    successChart.data.labels = successHistory.map(() => '');
    successChart.data.datasets[0].data = successHistory;

    // Color based on success rate
    const currentRate = metrics.successRate;
    if (currentRate >= 99) {
        successChart.data.datasets[0].borderColor = '#22c55e';
        successChart.data.datasets[0].backgroundColor = 'rgba(34, 197, 94, 0.1)';
    } else if (currentRate >= 95) {
        successChart.data.datasets[0].borderColor = '#f59e0b';
        successChart.data.datasets[0].backgroundColor = 'rgba(245, 158, 11, 0.1)';
    } else {
        successChart.data.datasets[0].borderColor = '#ef4444';
        successChart.data.datasets[0].backgroundColor = 'rgba(239, 68, 68, 0.1)';
    }

    successChart.update('none');

    // Update throughput chart
    throughputChart.data.labels = throughputHistory.map(() => '');
    throughputChart.data.datasets[0].data = throughputHistory;
    throughputChart.update('none');
}

// ===============================
// Controls
// ===============================

function initControls() {
    // Kill Node button
    document.getElementById('btn-kill-node').addEventListener('click', () => {
        showNodeModal(false);
    });

    // Kill Leader button
    document.getElementById('btn-kill-leader').addEventListener('click', async () => {
        const status = await fetchStatus();
        if (status && status.cluster.leaderId !== 'none') {
            await killNode(status.cluster.leaderId);
        }
    });

    // Rolling Restart button
    document.getElementById('btn-rolling-restart').addEventListener('click', async () => {
        await apiPost('/api/rolling-restart');
    });

    // Add Node button
    document.getElementById('btn-add-node').addEventListener('click', async () => {
        await apiPost('/api/add-node');
    });

    // Load control buttons
    document.getElementById('btn-load-1k').addEventListener('click', () => setLoad(1000));
    document.getElementById('btn-load-5k').addEventListener('click', () => setLoad(5000));
    document.getElementById('btn-load-10k').addEventListener('click', () => setLoad(10000));
    document.getElementById('btn-ramp').addEventListener('click', () => rampLoad(5000, 30000));

    // Load slider
    const slider = document.getElementById('load-slider');
    const loadValue = document.getElementById('load-value');
    slider.addEventListener('input', () => {
        loadValue.textContent = `${slider.value} req/sec`;
    });
    slider.addEventListener('change', () => {
        setLoad(parseInt(slider.value));
    });

    // Reset button
    document.getElementById('btn-reset').addEventListener('click', async () => {
        await apiPost('/api/reset-metrics');
        successHistory = [];
        throughputHistory = [];
    });

    // Modal cancel
    document.getElementById('modal-cancel').addEventListener('click', hideNodeModal);
}

function showNodeModal(includeLeader) {
    const modal = document.getElementById('node-modal');
    const nodeList = document.getElementById('node-list');
    nodeList.innerHTML = '';

    nodes.forEach(node => {
        if (!includeLeader && node.isLeader) return;

        const btn = document.createElement('button');
        btn.className = `btn ${node.isLeader ? 'btn-warning' : 'btn-danger'}`;
        btn.textContent = `${node.id}${node.isLeader ? ' (Leader)' : ''}`;
        btn.addEventListener('click', async () => {
            hideNodeModal();
            await killNode(node.id);
        });
        nodeList.appendChild(btn);
    });

    modal.classList.remove('hidden');
}

function hideNodeModal() {
    document.getElementById('node-modal').classList.add('hidden');
}

async function killNode(nodeId) {
    await apiPost(`/api/kill/${nodeId}`);
}

async function setLoad(rate) {
    await apiPost(`/api/load/set/${rate}`);
    document.getElementById('load-slider').value = rate;
    document.getElementById('load-value').textContent = `${rate} req/sec`;
}

async function rampLoad(targetRate, durationMs) {
    await apiPost('/api/load/ramp', { targetRate, durationMs });
}

// ===============================
// API & Polling
// ===============================

async function fetchStatus() {
    try {
        const response = await fetch(`${API_BASE}/api/status`);
        if (!response.ok) throw new Error('Status fetch failed');
        return await response.json();
    } catch (e) {
        console.error('Error fetching status:', e);
        return null;
    }
}

async function fetchEvents() {
    try {
        const response = await fetch(`${API_BASE}/api/events`);
        if (!response.ok) throw new Error('Events fetch failed');
        return await response.json();
    } catch (e) {
        console.error('Error fetching events:', e);
        return [];
    }
}

async function apiPost(endpoint, body = {}) {
    try {
        const response = await fetch(`${API_BASE}${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        return await response.json();
    } catch (e) {
        console.error('API error:', e);
        return null;
    }
}

function startPolling() {
    poll();
    setInterval(poll, REFRESH_INTERVAL);
}

async function poll() {
    const status = await fetchStatus();
    if (!status) return;

    // Update nodes state
    nodes = status.cluster.nodes;

    // Update topology
    updateTopology(status.cluster);

    // Update metrics display
    updateMetricsDisplay(status.metrics);

    // Update charts
    updateCharts(status.metrics);

    // Update load display
    updateLoadDisplay(status.load);

    // Update header stats
    document.getElementById('uptime').textContent = formatUptime(status.uptimeSeconds);
    document.getElementById('node-count').textContent = status.cluster.nodeCount;

    // Fetch and update events
    const newEvents = await fetchEvents();
    updateTimeline(newEvents);
}

function updateMetricsDisplay(metrics) {
    document.getElementById('requests-per-sec').textContent =
        Math.round(metrics.requestsPerSecond).toLocaleString();

    const successRateEl = document.getElementById('success-rate');
    successRateEl.textContent = `${metrics.successRate.toFixed(1)}%`;

    // Color code success rate
    const card = successRateEl.closest('.metric-card');
    if (metrics.successRate >= 99) {
        card.style.borderColor = '#22c55e';
        successRateEl.style.color = '#22c55e';
    } else if (metrics.successRate >= 95) {
        card.style.borderColor = '#f59e0b';
        successRateEl.style.color = '#f59e0b';
    } else {
        card.style.borderColor = '#ef4444';
        successRateEl.style.color = '#ef4444';
    }

    document.getElementById('avg-latency').textContent =
        `${metrics.avgLatencyMs.toFixed(1)}ms`;
}

function updateLoadDisplay(load) {
    const slider = document.getElementById('load-slider');
    const loadValue = document.getElementById('load-value');

    if (document.activeElement !== slider) {
        slider.value = load.currentRate;
        loadValue.textContent = `${load.currentRate} req/sec`;
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function updateTimeline(newEvents) {
    const timeline = document.getElementById('timeline');

    // Handle reset case
    if (newEvents.length < events.length) {
        timeline.innerHTML = '';
        events = [];
    }

    // Check for new events
    if (newEvents.length > events.length) {
        const added = newEvents.slice(events.length);
        added.forEach(event => {
            const div = document.createElement('div');
            div.className = 'timeline-event';

            const timeSpan = document.createElement('span');
            timeSpan.className = 'event-time';
            timeSpan.textContent = formatEventTime(event.timestamp);

            const typeSpan = document.createElement('span');
            typeSpan.className = 'event-type ' + escapeHtml(event.type);
            typeSpan.textContent = event.type;

            const msgSpan = document.createElement('span');
            msgSpan.className = 'event-message';
            msgSpan.textContent = event.message;

            div.appendChild(timeSpan);
            div.appendChild(typeSpan);
            div.appendChild(msgSpan);
            timeline.insertBefore(div, timeline.firstChild);
        });

        // Keep only last 20 events in DOM
        while (timeline.children.length > 20) {
            timeline.removeChild(timeline.lastChild);
        }
    }

    events = newEvents;
}

function formatUptime(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function formatEventTime(isoString) {
    const date = new Date(isoString);
    return date.toLocaleTimeString();
}
