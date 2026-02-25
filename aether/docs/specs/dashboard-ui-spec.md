# Aether Dashboard UI Rework -- Design Specification

**Version:** 1.0
**Date:** 2026-02-23
**Status:** Draft
**Author:** Pragmatica Labs

---

## Table of Contents

1. [Overview and Goals](#1-overview-and-goals)
2. [Technology Stack](#2-technology-stack)
3. [File Structure](#3-file-structure)
4. [Component Hierarchy and Data Flow](#4-component-hierarchy-and-data-flow)
5. [WebSocket Message Format and Subscription Model](#5-websocket-message-format-and-subscription-model)
6. [Layer 1: Overview Page](#6-layer-1-overview-page)
7. [Layer 2: Operations Pages](#7-layer-2-operations-pages)
8. [Layer 3: Dev Tools (Forge Mode)](#8-layer-3-dev-tools-forge-mode)
9. [Topology Visualization](#9-topology-visualization)
10. [Drill-Down Flows](#10-drill-down-flows)
11. [Color System and Design Tokens](#11-color-system-and-design-tokens)
12. [Forge Mode Detection and Panel Injection](#12-forge-mode-detection-and-panel-injection)
13. [Accessibility](#13-accessibility)
14. [Testing Approach](#14-testing-approach)
15. [Migration Plan](#15-migration-plan)
16. [Implementation Phases](#16-implementation-phases)
17. [References](#17-references)

---

## 1. Overview and Goals

### 1.1 Purpose

Replace the existing Aether dashboard with a three-layer UI that serves CTOs, ops engineers, and developers. The current dashboard (843 lines vanilla JS, 914 lines CSS) was built for demo purposes and has these limitations:

- Polls REST at 500ms intervals instead of using the existing WebSocket infrastructure
- No topology visualization (the single most compelling visual for a distributed runtime)
- No drill-down capability (entry points table is flat, no trace detail)
- No support for rolling updates, blueprint management, or scaling controls
- Monolithic JS file that will not scale to more interactive features
- Loads Chart.js (~254KB) from CDN -- violates the no-external-dependency principle

### 1.2 Goals

| ID | Goal | Metric |
|----|------|--------|
| G-01 | "Wow" first impression | Topology view renders within 500ms of page load |
| G-02 | WebSocket-first data | Zero polling on the Overview page; REST only for on-demand data |
| G-03 | Zero build step | All libraries vendored as minified files in the JAR |
| G-04 | Sub-100KB total JS | Alpine.js + uPlot + topology < 100KB minified |
| G-05 | 100-node clusters | Topology renders smoothly at 60fps with 100 nodes |
| G-06 | Self-contained | No CDN dependencies, no npm, no bundler |

### 1.3 Non-Goals

- Mobile-first design (nice-to-have but not required)
- Multi-cluster federation view
- User authentication UI (auth is handled at the infrastructure level)
- Custom chart types beyond time-series lines and sparklines

### 1.4 Target Personas

| Persona | Primary Layer | Key Need |
|---------|--------------|----------|
| CTO / Evaluator | Layer 1 (Overview) | "Is this system alive? Is it impressive?" |
| Ops Engineer | Layer 2 (Operations) | "What is happening right now? Where are the problems?" |
| Developer | Layer 3 (Dev Tools) | "Can I break this? Can I load test it?" |

---

## 2. Technology Stack

### 2.1 Libraries

| Library | Version | Minified Size | Gzipped | Role |
|---------|---------|--------------|---------|------|
| Alpine.js | 3.x | ~22KB | ~7KB | Reactivity, components, routing |
| uPlot | 1.6.x | ~48KB | ~16KB | Time-series charts (replaces Chart.js) |
| d3-force | 3.x | ~18KB | ~6KB | Topology node layout simulation |
| **Total** | | **~88KB** | **~29KB** | |

**Comparison:** Current Chart.js alone is ~254KB minified. The entire new stack is 65% smaller while adding topology visualization and component reactivity.

### 2.2 Rationale

**Why Alpine.js over vanilla JS:**
- The current `dashboard.js` (843 lines) manually manages DOM updates with `innerHTML`. Adding drill-downs, modals, and interactive components will triple this code. Alpine.js provides reactive `x-data`, `x-show`, `x-for`, and `x-on` directives that eliminate manual DOM manipulation while staying in HTML -- no build step, no virtual DOM, no JSX.
- Alpine.js components are declared in HTML attributes, so the HTML remains the single source of truth for layout. This is critical because the dashboard is served as classpath-embedded static resources, not a compiled SPA.

**Why uPlot over Chart.js:**
- uPlot is 5x smaller and purpose-built for time-series data (which is all we chart).
- Canvas-based rendering is significantly faster for streaming data updates.
- Chart.js's animation system has to be disabled (`animation: { duration: 0 }`) in the current code -- uPlot has no such overhead.

**Why d3-force over full D3:**
- We only need the force-directed layout algorithm for topology. The standalone `d3-force` module is ~18KB vs full D3 at ~270KB.
- Rendering is done on a `<canvas>` element directly, not SVG. This avoids DOM thrashing when nodes move and scales to 100+ nodes.

### 2.3 Vendoring Strategy

All libraries are downloaded as minified `.min.js` files and placed directly in the dashboard module:

```
dashboard/src/main/resources/dashboard/vendor/
  alpine.min.js       (~22KB)
  uplot.min.js        (~48KB)
  uplot.min.css       (~2KB)
  d3-force.min.js     (~18KB)
```

No CDN `<script>` tags. No `node_modules`. No `package.json`. The JAR contains everything.

---

## 3. File Structure

```
aether/dashboard/src/main/resources/dashboard/
|
|-- index.html                    # Shell: nav, Alpine.js x-data root, <canvas> for topology
|
|-- vendor/                       # Vendored third-party libraries
|   |-- alpine.min.js
|   |-- uplot.min.js
|   |-- uplot.min.css
|   |-- d3-force.min.js
|
|-- css/
|   |-- tokens.css                # Design tokens (CSS custom properties)
|   |-- layout.css                # Grid, container, responsive breakpoints
|   |-- components.css            # Panel, card, badge, button, modal, table
|   |-- topology.css              # Topology canvas overlay (tooltips, legend)
|   |-- charts.css                # uPlot overrides and sparkline containers
|   |-- forge.css                 # Forge-only controls (load/chaos panels)
|
|-- js/
|   |-- app.js                    # Alpine.js root store, WebSocket manager, page routing
|   |-- stores/
|   |   |-- cluster.js            # Cluster state: nodes, leader, quorum, topology
|   |   |-- metrics.js            # Metrics state: RPS, latency, success rate, history
|   |   |-- events.js             # Event feed state: ring buffer, filtering
|   |   |-- alerts.js             # Alert state: active, history, thresholds
|   |   |-- deployments.js        # Slice/blueprint/rolling-update state
|   |   |-- forge.js              # Forge-specific state: load config, chaos, scenarios
|   |
|   |-- components/
|   |   |-- topology.js           # Canvas-based topology renderer (d3-force layout)
|   |   |-- sparkline.js          # Tiny inline chart using uPlot
|   |   |-- timeseries.js         # Full time-series chart using uPlot
|   |   |-- health-banner.js      # Health status indicator component
|   |   |-- event-feed.js         # Scrolling event timeline with virtual scroll
|   |   |-- invocation-table.js   # Entry points table with sort/filter/drill-down
|   |   |-- trace-detail.js       # Trace waterfall visualization
|   |   |-- node-detail.js        # Node detail panel (expanded view)
|   |   |-- rolling-update.js     # Rolling update progress visualization
|   |
|   |-- lib/
|       |-- ws.js                 # WebSocket connection manager with reconnect
|       |-- rest.js               # REST client helper (for on-demand fetches)
|       |-- format.js             # Number/time/duration formatters
|       |-- virtual-scroll.js     # Virtual scrolling for long lists
```

### 3.1 File Size Budget

| Category | Target | Notes |
|----------|--------|-------|
| Vendor JS | ~88KB | Alpine + uPlot + d3-force |
| Vendor CSS | ~2KB | uPlot stylesheet |
| App JS (all) | ~25KB | Our code: stores + components + lib |
| App CSS (all) | ~8KB | Tokens + layout + components |
| HTML | ~5KB | index.html shell |
| **Total** | **~128KB** | Before gzip. Gzipped: ~45KB |

---

## 4. Component Hierarchy and Data Flow

### 4.1 Architecture Overview

```
+------------------------------------------------------------------+
|  index.html  (Alpine.js x-data root)                             |
|                                                                    |
|  +-- WebSocket Manager (ws.js) ---------> /ws/status (1s push)   |
|  |                                ---------> /ws/events (1s delta) |
|  |                                ---------> /ws/dashboard (1s)    |
|  |                                                                 |
|  +-- Alpine Store: cluster ----+                                   |
|  +-- Alpine Store: metrics ----|--- reactive binding ---> DOM      |
|  +-- Alpine Store: events  ----|                                   |
|  +-- Alpine Store: alerts  ----+                                   |
|  +-- Alpine Store: deployments-+                                   |
|  +-- Alpine Store: forge   ----+  (conditional, Forge only)        |
|                                                                    |
|  Pages (shown/hidden via x-show):                                  |
|    [Overview] [Metrics] [Invocations] [Alerts] [Deployments]       |
|    [Config]   [Forge]                                              |
+------------------------------------------------------------------+
```

### 4.2 Data Flow

```
Backend (Java)                     Frontend (Browser)
--------------                     ------------------

StatusWebSocketPublisher           WebSocket Manager
  |-- builds JSON every 1s  --->     |-- parses JSON
  |                                  |-- dispatches to Alpine stores
  |                                  |
EventWebSocketPublisher              cluster.update(data.cluster)
  |-- delta events every 1s --->     metrics.update(data.metrics)
  |                                  events.append(data.events)
  |
DashboardMetricsPublisher            |-- triggers Alpine reactivity
  |-- metrics every 1s       --->    |-- DOM auto-updates via x-bind
                                     |-- Canvas redraws via $watch

REST (on-demand)                   User interaction
  GET /api/traces/{id}     <---      drill-down click
  GET /api/metrics/history <---      time range change
  GET /api/controller/config <---    config page visit
  POST /api/thresholds     <---      threshold edit
  POST /api/scale          <---      manual scale action
```

### 4.3 Alpine Store Design

Each store is a plain JavaScript object registered via `Alpine.store()`:

```javascript
// stores/cluster.js
Alpine.store('cluster', {
    nodes: [],
    leaderId: '',
    quorum: true,
    nodeCount: 0,
    health: 'healthy',      // 'healthy' | 'degraded' | 'unhealthy'
    uptimeSeconds: 0,

    update(data) {
        // data comes from /ws/status push
        this.nodes = data.cluster?.nodes || [];
        this.leaderId = data.cluster?.leaderId || '';
        this.nodeCount = data.cluster?.nodeCount || 0;
        this.uptimeSeconds = data.uptimeSeconds || 0;
        // Derive health from quorum + node count
        this.quorum = data.cluster?.nodeCount >= 2;
        this.health = this.quorum ? 'healthy' : 'degraded';
    }
});
```

Stores are updated exclusively by the WebSocket manager. Components read stores reactively. This one-way data flow prevents state synchronization issues.

### 4.4 Page Routing

Alpine.js does not include a router. Pages are managed with a simple `currentPage` variable:

```html
<div x-data="{ currentPage: 'overview' }">
    <nav>
        <button @click="currentPage = 'overview'"
                :class="{ active: currentPage === 'overview' }">Overview</button>
        <!-- ... -->
    </nav>
    <div x-show="currentPage === 'overview'"><!-- Overview content --></div>
    <div x-show="currentPage === 'metrics'"><!-- Metrics content --></div>
    <!-- ... -->
</div>
```

This is intentionally simple. No URL hash routing. No history API. The dashboard is a single-page tool, not a multi-page app.

---

## 5. WebSocket Message Format and Subscription Model

### 5.1 Connection Strategy

```
Page Load
  |
  +-- Connect to /ws/status   (cluster state, node metrics, slices)
  |     |-- receives full snapshot every 1000ms
  |     |-- on open: stop any polling fallback
  |     |-- on close: start polling /api/status at 2000ms, retry WS with backoff
  |
  +-- Connect to /ws/events   (cluster events, delta mode)
  |     |-- receives only NEW events since last broadcast
  |     |-- on open: fetch /api/events for initial history
  |
  +-- Connect to /ws/dashboard (detailed metrics, invocation data)
        |-- receives METRICS_UPDATE every 1000ms
        |-- on open: receives INITIAL_STATE with full snapshot
        |-- supports bidirectional: client can send GET_HISTORY, SET_THRESHOLD
```

### 5.2 WebSocket Manager (`ws.js`)

```javascript
class WebSocketManager {
    constructor() {
        this.connections = {};
        this.retryDelays = {};
    }

    connect(path, onMessage) {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(`${protocol}//${location.host}${path}`);

        ws.onopen = () => {
            this.retryDelays[path] = 1000;
            this.connections[path] = ws;
        };

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                onMessage(data);
            } catch (e) { /* ignore parse errors */ }
        };

        ws.onclose = () => {
            delete this.connections[path];
            const delay = Math.min(
                (this.retryDelays[path] || 1000) * 2,
                10000
            );
            this.retryDelays[path] = delay;
            setTimeout(() => this.connect(path, onMessage), delay);
        };

        return ws;
    }

    send(path, message) {
        const ws = this.connections[path];
        if (ws?.readyState === WebSocket.OPEN) {
            ws.send(typeof message === 'string' ? message : JSON.stringify(message));
        }
    }
}
```

### 5.3 Existing Backend Message Formats

**`/ws/status` -- Full snapshot (every 1000ms):**

```json
{
    "uptimeSeconds": 123,
    "nodeMetrics": [
        { "nodeId": "node-1", "isLeader": true, "cpuUsage": 0.45,
          "heapUsedMb": 256, "heapMaxMb": 512 }
    ],
    "slices": [
        { "artifact": "org.example:my-slice:1.0.0", "state": "ACTIVE",
          "instances": [{ "nodeId": "node-1", "state": "ACTIVE" }] }
    ],
    "cluster": {
        "nodes": [{ "id": "node-1", "isLeader": true }],
        "leaderId": "node-1",
        "nodeCount": 3
    }
}
```

**`/ws/events` -- Delta events (only when new events exist):**

```json
[
    {
        "timestamp": "2026-01-15T10:30:00Z",
        "type": "NODE_JOINED",
        "severity": "INFO",
        "summary": "Node node-2 joined cluster (now 3 nodes)",
        "details": { "nodeId": "node-2", "clusterSize": "3" }
    }
]
```

**`/ws/dashboard` -- Metrics update (every 1000ms):**

```json
{
    "type": "METRICS_UPDATE",
    "timestamp": 1704067200000,
    "data": {
        "load": {
            "node-1": { "cpu.usage": 0.45, "heap.used": 268435456 }
        },
        "invocations": [
            { "artifact": "org.example:my-slice:1.0.0", "method": "processOrder",
              "count": 1000, "successCount": 990, "failureCount": 10,
              "avgDurationMs": 50.0, "errorRate": 0.01, "slowCalls": 5 }
        ],
        "deployments": [ ... ],
        "aggregates": {
            "rps": 1500.0, "successRate": 0.995,
            "errorRate": 0.005, "avgLatencyMs": 12.3
        }
    }
}
```

### 5.4 Polling Fallback

When WebSocket connections fail, the dashboard falls back to REST polling:

| Endpoint | Interval | Replaces |
|----------|----------|----------|
| GET /api/status | 2000ms | /ws/status |
| GET /api/events | 2000ms | /ws/events |
| GET /api/invocation-metrics | 3000ms | /ws/dashboard invocations |

The polling interval is deliberately slower than WebSocket (2s vs 1s) to reduce server load during degraded connectivity. A "WS Disconnected" indicator appears in the header when polling is active.

---

## 6. Layer 1: Overview Page

### 6.1 Layout

```
+----------------------------------------------------------------------+
| AETHER                    [ WS OK ]   Uptime: 45:23   Nodes: 5/5    |
+----------------------------------------------------------------------+
| [Overview] [Metrics] [Invocations] [Alerts] [Deployments] [Config]   |
+----------------------------------------------------------------------+
|                                                                      |
|  +-- HEALTH BANNER -----------------------------------------------+ |
|  | [*] HEALTHY   Quorum: 5/5   Leader: node-1   Slices: 3 active  | |
|  +----------------------------------------------------------------+ |
|                                                                      |
|  +-- CLUSTER TOPOLOGY (Canvas, ~60% height) ----------------------+ |
|  |                                                                  | |
|  |        [node-3]                                                  | |
|  |       /        \                                                 | |
|  |  [node-1*]----[node-2]          * = leader (gold ring)          | |
|  |       \        /                 green = healthy                  | |
|  |        [node-4]---[node-5]       yellow = joining                | |
|  |                                  red = failed                    | |
|  |  Hover: CPU 45% | Heap 256/512MB | 2 slices                     | |
|  +------------------------------------------------------------------+ |
|                                                                      |
|  +-- KEY METRICS STRIP -------------------------------------------+ |
|  |  | 4,231 req/s | 0.5ms P50 | 2.1ms P99 | 99.8% success | 5 nodes| |
|  |  | [sparkline] | [spark]   | [spark]   | [sparkline]   |        | |
|  +----------------------------------------------------------------+ |
|                                                                      |
|  +-- LIVE EVENT FEED ----+  +-- ENTRY POINTS (top 5) -----------+ |
|  | 10:30:02 NODE_JOINED  |  | processOrder     4.2K   99.8%    | |
|  |   node-5 joined       |  | getInventory     1.8K   100%     | |
|  | 10:29:58 LEADER_ELECT |  | submitPayment    890    98.5%    | |
|  |   node-1 elected      |  | validateAddr     2.1K   100%     | |
|  | 10:29:55 DEPLOYMENT   |  | healthCheck      12K    100%     | |
|  |   my-slice deployed   |  |          [View All ->]            | |
|  +------------------------+  +-----------------------------------+ |
|                                                                      |
|  +-- FORGE CONTROLS (only if Forge mode) -------------------------+ |
|  |  [visible only in Forge -- see Layer 3]                         | |
|  +----------------------------------------------------------------+ |
+----------------------------------------------------------------------+
```

### 6.2 Health Banner (REQ-OV-01)

The health banner is the first thing the eye hits. It communicates cluster state in under 1 second.

| State | Color | Condition |
|-------|-------|-----------|
| HEALTHY | Green (#22c55e) | Quorum established, all nodes responsive, no critical alerts |
| DEGRADED | Yellow (#f59e0b) | Quorum established but node(s) missing or warning-level alerts |
| UNHEALTHY | Red (#ef4444) | Quorum lost, or critical alerts active |

**Data source:** Derived from `/ws/status` cluster data + `/ws/events` for alert state.

**Implementation:**

```html
<div x-data x-bind:class="'health-banner health-' + $store.cluster.health">
    <span class="health-dot" x-bind:class="'dot-' + $store.cluster.health"></span>
    <span class="health-label" x-text="$store.cluster.health.toUpperCase()"></span>
    <span class="health-detail">
        Quorum: <span x-text="$store.cluster.nodeCount"></span>/<span x-text="$store.cluster.nodeCount"></span>
        | Leader: <span x-text="$store.cluster.leaderId || 'none'"></span>
        | Slices: <span x-text="$store.deployments.activeCount"></span> active
    </span>
</div>
```

### 6.3 Cluster Topology (REQ-OV-02) -- Hero Feature

See [Section 9: Topology Visualization](#9-topology-visualization) for full specification.

### 6.4 Key Metrics Strip (REQ-OV-03)

Five metric cards in a horizontal strip. Each card contains:
1. Big number (current value)
2. Label
3. Sparkline (last 60 data points, ~60 seconds at 1s resolution)

| Card | Value Source | Sparkline Data |
|------|-------------|----------------|
| Requests/sec | `aggregates.rps` | Last 60 RPS values |
| Latency P50 | `metrics.comprehensive.latencyP50` | Last 60 P50 values |
| Latency P99 | `metrics.comprehensive.latencyP99` | Last 60 P99 values |
| Success Rate | `aggregates.successRate` (as %) | Last 60 success rate values |
| Node Count | `cluster.nodeCount` | Last 60 node counts |

**Sparklines** are implemented as tiny uPlot instances (60px height, no axes, no labels). See `js/components/sparkline.js`.

### 6.5 Live Event Feed (REQ-OV-04)

Scrolling timeline showing the most recent 50 cluster events. Events arrive via `/ws/events` delta broadcasting.

```
+------------------------------------------------------------+
| 10:30:02  [NODE_JOINED]    node-5 joined (now 5 nodes)     |
| 10:29:58  [LEADER_ELECTED] node-1 elected as leader        |
| 10:29:55  [DEPLOYMENT_OK]  my-slice deployed on 3 nodes    |
| 10:29:50  [QUORUM_OK]      Quorum established (3/5)        |
+------------------------------------------------------------+
```

- Event type badges are color-coded per severity (INFO=blue, WARNING=yellow, CRITICAL=red)
- New events slide in at the top with a CSS transition (`transform: translateY`)
- Maximum 50 events in the DOM. Older events are removed from the bottom.
- [ASSUMPTION] 50 events is sufficient for the overview page. The full event history is accessible via REST `GET /api/events?since=...`.

### 6.6 Entry Points Summary (REQ-OV-05)

Compact table showing the top 5 entry points by request count. Clicking "View All" navigates to the Invocations page (Layer 2).

| Column | Source |
|--------|--------|
| Name | `invocations[].method` |
| Total | `invocations[].count` |
| Success % | derived from `successCount / count` |
| Avg Latency | `invocations[].avgDurationMs` |

---

## 7. Layer 2: Operations Pages

Layer 2 consists of four separate pages accessible from the navigation bar.

### 7.1 Metrics Page (REQ-OP-01)

```
+----------------------------------------------------------------------+
| Time Range: [5m] [15m] [1h] [2h]                    Per-node: [v]   |
+----------------------------------------------------------------------+
|                                                                      |
|  +-- RPS over time (uPlot, full width) ----------------------------+|
|  |  4000 |    ___/\___                                              ||
|  |  2000 |___/        \_______                                      ||
|  |     0 |______________________________                            ||
|  +------------------------------------------------------------------+|
|                                                                      |
|  +-- Latency P50/P95/P99 ------+  +-- Success Rate ---------------+|
|  |  [uPlot, 3 lines]           |  |  [uPlot, percentage y-axis]   ||
|  +------------------------------+  +-------------------------------+|
|                                                                      |
|  +-- CPU per Node ---------------+  +-- Heap per Node -------------+|
|  |  [uPlot, N lines, legend]    |  |  [uPlot, N lines, legend]    ||
|  +-------------------------------+  +-------------------------------+|
|                                                                      |
|  +-- Node Detail (expandable) -----------------------------------+ |
|  | node-1 (LEADER)  CPU 45%  Heap 256/512MB  2 slices  [expand]  | |
|  | node-2           CPU 52%  Heap 234/512MB  2 slices  [expand]  | |
|  | node-3           CPU 38%  Heap 198/512MB  1 slice   [expand]  | |
|  +----------------------------------------------------------------+ |
+----------------------------------------------------------------------+
```

**Time range selection** sends `GET_HISTORY` via `/ws/dashboard` WebSocket:

```json
{"type": "GET_HISTORY", "timeRange": "15m"}
```

The backend already supports `5m`, `15m`, `1h`, `2h` ranges in `DashboardMetricsPublisher.parseTimeRange()`.

**Per-node toggle:** When enabled, each chart shows one line per node (using distinct colors from the palette). When disabled, charts show cluster-wide aggregates.

**uPlot configuration** for time-series charts:

```javascript
// js/components/timeseries.js
function createTimeSeries(element, opts) {
    const defaultOpts = {
        width: element.clientWidth,
        height: opts.height || 200,
        cursor: { show: true, sync: { key: 'dashboard' } },
        scales: { x: { time: true }, y: { auto: true } },
        axes: [
            { stroke: '#505060', grid: { stroke: '#252530' } },
            { stroke: '#505060', grid: { stroke: '#252530' },
              values: (_, ticks) => ticks.map(v => opts.formatY?.(v) ?? v) }
        ],
        series: [
            {},  // x-axis (time)
            { stroke: '#06b6d4', fill: 'rgba(6, 182, 212, 0.1)',
              width: 1.5, spanGaps: true }
        ]
    };
    return new uPlot({ ...defaultOpts, ...opts }, opts.data, element);
}
```

### 7.2 Invocations Page (REQ-OP-02)

```
+----------------------------------------------------------------------+
| Filter: [artifact____] [method______] [status: All v]  [Search]     |
+----------------------------------------------------------------------+
|                                                                      |
|  +-- Invocation Table (sortable, filterable) ----------------------+|
|  | Method           | Artifact              | Count | Suc% |Avg|P99||
|  |------------------+-----------------------+-------+------+---+---||
|  | processOrder     | org.ex:order-svc:1.0  | 4,231 |99.8% |12 |80||
|  | > click expands to latency distribution chart + slow calls       ||
|  | getInventory     | org.ex:inv-svc:1.0    | 1,832 | 100% | 5 |25||
|  | submitPayment    | org.ex:pay-svc:1.0    |   890 |98.5% |45 |200|
|  +------------------------------------------------------------------+|
|                                                                      |
|  Expanded row (drill-down):                                          |
|  +-- Latency Distribution ---------+  +-- Recent Slow Calls ------+|
|  | [histogram / percentile chart]   |  | reqId: abc-123  500ms FAIL||
|  |                                  |  | reqId: def-456  350ms OK  ||
|  |                                  |  | [View Trace ->]           ||
|  +----------------------------------+  +---------------------------+|
+----------------------------------------------------------------------+
```

See [Section 10: Drill-Down Flows](#10-drill-down-flows) for the full drill-down specification.

**Data source:** `/ws/dashboard` `invocations[]` array for live updates. `GET /api/invocation-metrics?artifact=...&method=...` for filtered views. `GET /api/invocation-metrics/slow` for slow call details.

### 7.3 Alerts Page (REQ-OP-03)

```
+----------------------------------------------------------------------+
| Active Alerts                                        [Clear All]     |
+----------------------------------------------------------------------+
| [CRITICAL] cpu.usage on node-3        CPU at 95%       10:30:02      |
|            [Acknowledge]                                              |
| [WARNING]  heap.usage on node-1       Heap at 75%      10:29:45      |
|            [Acknowledge]                                              |
+----------------------------------------------------------------------+
| Alert History                                                        |
+----------------------------------------------------------------------+
| [RESOLVED] cpu.usage on node-2        Was 92%, now 45% 10:25:00      |
| [CLEARED]  heap.usage on node-3       Manually cleared  10:20:00      |
+----------------------------------------------------------------------+
| Threshold Configuration                                              |
+----------------------------------------------------------------------+
| Metric        | Warning | Critical | Actions                        |
| cpu.usage     | [0.7__] | [0.9___] | [Save] [Delete]                |
| heap.usage    | [0.7__] | [0.85__] | [Save] [Delete]                |
| + Add Threshold: [metric___] [warning] [critical] [Add]             |
+----------------------------------------------------------------------+
```

**Inline threshold editing:** Threshold changes call `POST /api/thresholds` or send `SET_THRESHOLD` via `/ws/dashboard`.

**Data sources:**
- Active alerts: `GET /api/alerts/active` (on page visit) + `/ws/dashboard` alert broadcasts
- Alert history: `GET /api/alerts/history` (on page visit, not real-time)
- Thresholds: `GET /api/thresholds` (on page visit)

### 7.4 Deployments Page (REQ-OP-04)

```
+----------------------------------------------------------------------+
| Blueprints                                                           |
+----------------------------------------------------------------------+
| my-blueprint                                          [DEPLOYED]     |
|   Slices: 3 | Dependencies: 1                                       |
|   +-- Slice Instance Map ----------------------------------------+  |
|   | order-svc:1.0  [node-1: ACTIVE] [node-2: ACTIVE] [node-3: -]|  |
|   | inv-svc:1.0    [node-1: ACTIVE] [node-2: -]      [node-3: ACT]| |
|   | pay-svc:1.0    [node-1: ACTIVE] [node-2: ACTIVE] [node-3: ACT]| |
|   +--------------------------------------------------------------+  |
|                                                                      |
| Rolling Updates                                                      |
+----------------------------------------------------------------------+
| order-svc  1.0.0 -> 2.0.0  [ROUTING: 1:3]  [=====>        ] 25%   |
|   New: 250 req, 0.2% err, 50ms avg                                  |
|   Old: 750 req, 0.1% err, 45ms avg                                  |
|   [Adjust Routing] [Complete] [Rollback]                             |
+----------------------------------------------------------------------+
| Scaling                                                              |
+----------------------------------------------------------------------+
| Slice            | Current | Target | Auto-scaler |                  |
| order-svc:1.0    |    3    |   3    | Stable      | [Scale +] [-]    |
| inv-svc:1.0      |    2    |   2    | ScaleUp rec | [Scale +] [-]    |
+----------------------------------------------------------------------+
```

**Data sources:**
- Blueprints: `GET /api/blueprints` + `GET /api/blueprint/{id}/status`
- Rolling updates: `GET /api/rolling-updates` + `GET /api/rolling-update/{id}/health`
- Scaling: `GET /api/slices/status` + `GET /api/controller/status`
- Scale actions: `POST /api/scale`

### 7.5 Config Page (REQ-OP-05)

Largely preserved from the current dashboard but reorganized:

```
+----------------------------------------------------------------------+
| Controller Configuration              TTM (Predictive Scaling)       |
| +---------------------------------+  +---------------------------------+
| | CPU Scale Up:    [0.8____]      |  | State: RUNNING                  |
| | CPU Scale Down:  [0.2____]      |  | Model: /models/ttm.onnx        |
| | Call Rate Up:    [1000___]      |  | Confidence: 0.92                |
| | Eval Interval:   [1000ms_]     |  | Last Forecast: ScaleUp          |
| |            [Save Config]        |  |            [Refresh]            |
| +---------------------------------+  +---------------------------------+
|                                                                      |
| Dynamic Configuration                 Log Levels                     |
| +---------------------------------+  +---------------------------------+
| | database.port = 5432            |  | o.p.aether.node = DEBUG         |
| | server.port = 8080              |  | o.p.consensus = WARN            |
| |  [+ Add Override]               |  |  [+ Add Override]               |
| +---------------------------------+  +---------------------------------+
|                                                                      |
| Scheduled Tasks                                                      |
| +-----------------------------------------------------------------+  |
| | scheduling.cleanup  my-slice  cleanup  5m  leader-only  node-1  |  |
| +-----------------------------------------------------------------+  |
+----------------------------------------------------------------------+
```

**Data sources:** `GET /api/controller/config`, `GET /api/ttm/status`, `GET /api/config`, `GET /api/logging/levels`, `GET /api/scheduled-tasks`. All fetched on page visit (not WebSocket-streamed).

---

## 8. Layer 3: Dev Tools (Forge Mode)

### 8.1 Forge Detection (REQ-FG-01)

Forge mode is detected by probing the Forge-specific API endpoint:

```javascript
// In app.js during initialization
async function detectForge() {
    try {
        const resp = await fetch('/api/chaos/rolling-restart-status');
        return resp.ok;
    } catch {
        return false;
    }
}
```

[ASSUMPTION] The Forge server exposes `/api/chaos/*` endpoints that do not exist on a standard AetherNode management server. A 404 on this probe means "not Forge."

**Alternative approach (recommended):** Add a `GET /api/forge/status` endpoint to the Forge server that returns `{"forge": true, "features": ["chaos", "load", "scenarios"]}`. This is cleaner than probing for side effects. This requires a minor backend change.

### 8.2 Forge Controls (Overview Page Injection)

When Forge is detected, the following panel appears at the bottom of the Overview page:

```
+----------------------------------------------------------------------+
| FORGE CONTROLS                                                       |
+----------------------------------------------------------------------+
| Chaos                          | Load Generator                      |
| [Kill Node] [Kill Leader]      | Rate: [====|=====>====] 5K req/s   |
| [Rolling Restart] [Add Node]   | [1K] [5K] [10K] [25K] [50K] [100K]|
| [Partition Node]               | [Ramp to Next] [Reset Metrics]      |
|                                | Enabled: [toggle]  Multiplier: [1.0]|
+----------------------------------------------------------------------+
```

### 8.3 Forge Testing Page (REQ-FG-02)

When Forge is detected, a "Testing" tab appears in the navigation bar.

```
+----------------------------------------------------------------------+
| LOAD CONFIGURATION                                                   |
+----------------------------------------------------------------------+
| [Upload TOML]  Status: 3 targets configured (5000 req/s total)      |
| [Start] [Stop] [Pause]                  State: RUNNING              |
+----------------------------------------------------------------------+
| PER-TARGET METRICS                                                   |
+----------------------------------------------------------------------+
| Target       | Rate (act/tgt) | Requests | Suc% | Avg Lat | Remain  |
| processOrder | 2000/2000      | 45,231   | 99.8%| 12ms    | -       |
| getInventory | 1500/1500      | 33,102   | 100% | 5ms     | -       |
| submitPay    | 1500/1500      | 28,450   | 98.5%| 45ms    | 10s     |
+----------------------------------------------------------------------+
| REAL-TIME CORRELATION                                                |
+----------------------------------------------------------------------+
| +-- Load Rate --------------------+  +-- Latency ------------------+|
| |  [uPlot: rate over time]        |  |  [uPlot: latency over time] ||
| +----------------------------------+  +-----------------------------+|
|                                                                      |
| +-- Success Rate ------------------+  +-- Chaos Events Overlay ----+|
| |  [uPlot: success % over time]   |  |  Vertical lines marking     ||
| |  [Vertical red lines = chaos]    |  |  kill/restart/partition      ||
| +----------------------------------+  +-----------------------------+|
+----------------------------------------------------------------------+
| CHAOS CONTROLS                                                       |
+----------------------------------------------------------------------+
| [Kill Node v] [Kill Leader] [Rolling Restart] [Stop Rolling Restart] |
| [Add Node]    [Network Partition v]    [Reset All]                   |
+----------------------------------------------------------------------+
```

**Correlation charts:** These show load and latency side by side. Chaos events (node kills, restarts) are rendered as vertical annotation lines on the charts, so the user can visually correlate "I killed a node at 10:30 and latency spiked for 2 seconds."

**Data source:** Forge extends the `/ws/status` payload with `loadTargets[]` array and chaos event data. The existing Forge dashboard JS already parses this.

### 8.4 Scenario Runner (REQ-FG-03) [FUTURE]

[TBD] If Forge Script is implemented, this section will contain:
- Saved chaos sequences (e.g., "kill leader, wait 10s, add 2 nodes, ramp to 10K")
- Record/replay functionality
- Scenario library with pre-built sequences

This is marked as future work. The UI will reserve space for it but show "Coming soon" until the backend supports it.

---

## 9. Topology Visualization

### 9.1 Overview

The topology view is the hero feature -- the visual centerpiece that communicates "this is a living distributed system" in under 3 seconds. It renders cluster nodes as circles on a `<canvas>` element with force-directed layout from `d3-force`.

### 9.2 Visual Design

```
Node rendering (each node is a circle on canvas):

    Normal node:          Leader node:          Failed node:
    +----------+          +----------+          +----------+
    |  /----\  |          |  /====\  |          |  /----\  |
    | ( node ) |          | ( node ) |          | ( XXXX ) |
    |  \----/  |          |  \====/  |          |  \----/  |
    +----------+          +----------+          +----------+
    Green fill/stroke     Gold ring + fill      Red fill, dashed
    radius: 20px          radius: 24px          radius: 20px

Connection lines:
    Solid gray (#505060) between all nodes that can communicate.
    Dashed red between partitioned nodes (Forge only).
    Line thickness: 1px normal, 2px for leader connections.
```

### 9.3 Node States and Visuals

| State | Fill Color | Stroke Color | Stroke Width | Extra |
|-------|-----------|-------------|-------------|-------|
| Healthy | `#22c55e` (20% opacity) | `#22c55e` | 2px | - |
| Leader | `#f59e0b` (20% opacity) | `#f59e0b` | 3px | Outer glow ring |
| Joining | `#06b6d4` (20% opacity) | `#06b6d4` | 2px | Pulse animation |
| Degraded | `#f59e0b` (20% opacity) | `#f59e0b` | 2px | - |
| Failed/Left | `#ef4444` (20% opacity) | `#ef4444` | 2px, dashed | Fade-out animation |

### 9.4 d3-force Layout Algorithm

```javascript
// js/components/topology.js
class TopologyRenderer {
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.simulation = null;
        this.nodes = [];
        this.links = [];
        this.hoveredNode = null;
    }

    update(clusterNodes) {
        // Reconcile: match existing positions for stable nodes
        const oldPositions = new Map(this.nodes.map(n => [n.id, { x: n.x, y: n.y }]));

        this.nodes = clusterNodes.map(n => {
            const old = oldPositions.get(n.id);
            return {
                id: n.id,
                isLeader: n.isLeader,
                state: n.state || 'healthy',
                // Preserve position if node existed, else random
                x: old?.x ?? this.canvas.width / 2 + (Math.random() - 0.5) * 100,
                y: old?.y ?? this.canvas.height / 2 + (Math.random() - 0.5) * 100,
                // Metrics from nodeMetrics
                cpu: n.cpuUsage,
                heapUsed: n.heapUsedMb,
                heapMax: n.heapMaxMb,
                sliceCount: n.sliceCount || 0
            };
        });

        // Full mesh links (every node connects to every other node)
        this.links = [];
        for (let i = 0; i < this.nodes.length; i++) {
            for (let j = i + 1; j < this.nodes.length; j++) {
                this.links.push({
                    source: this.nodes[i],
                    target: this.nodes[j]
                });
            }
        }

        this.restartSimulation();
    }

    restartSimulation() {
        if (this.simulation) this.simulation.stop();

        const cx = this.canvas.width / 2;
        const cy = this.canvas.height / 2;

        this.simulation = d3.forceSimulation(this.nodes)
            .force('charge', d3.forceManyBody().strength(-300))
            .force('center', d3.forceCenter(cx, cy))
            .force('collision', d3.forceCollide().radius(35))
            .force('link', d3.forceLink(this.links).distance(120))
            .alphaDecay(0.05)   // settle quickly
            .on('tick', () => this.render());
    }

    render() {
        const ctx = this.ctx;
        const w = this.canvas.width;
        const h = this.canvas.height;
        ctx.clearRect(0, 0, w, h);

        // Draw links
        for (const link of this.links) {
            ctx.beginPath();
            ctx.moveTo(link.source.x, link.source.y);
            ctx.lineTo(link.target.x, link.target.y);
            ctx.strokeStyle = '#505060';
            ctx.lineWidth = link.source.isLeader || link.target.isLeader ? 2 : 1;
            ctx.stroke();
        }

        // Draw nodes
        for (const node of this.nodes) {
            this.drawNode(ctx, node);
        }

        // Draw tooltip for hovered node
        if (this.hoveredNode) {
            this.drawTooltip(ctx, this.hoveredNode);
        }
    }

    drawNode(ctx, node) {
        const r = node.isLeader ? 24 : 20;
        const colors = {
            healthy:  { fill: 'rgba(34, 197, 94, 0.2)',  stroke: '#22c55e' },
            leader:   { fill: 'rgba(245, 158, 11, 0.2)', stroke: '#f59e0b' },
            joining:  { fill: 'rgba(6, 182, 212, 0.2)',  stroke: '#06b6d4' },
            degraded: { fill: 'rgba(245, 158, 11, 0.2)', stroke: '#f59e0b' },
            failed:   { fill: 'rgba(239, 68, 68, 0.2)',  stroke: '#ef4444' }
        };
        const state = node.isLeader ? 'leader' : (node.state || 'healthy');
        const c = colors[state] || colors.healthy;

        // Leader glow
        if (node.isLeader) {
            ctx.beginPath();
            ctx.arc(node.x, node.y, r + 4, 0, Math.PI * 2);
            ctx.strokeStyle = 'rgba(245, 158, 11, 0.3)';
            ctx.lineWidth = 3;
            ctx.stroke();
        }

        // Node circle
        ctx.beginPath();
        ctx.arc(node.x, node.y, r, 0, Math.PI * 2);
        ctx.fillStyle = c.fill;
        ctx.fill();
        ctx.strokeStyle = c.stroke;
        ctx.lineWidth = node.isLeader ? 3 : 2;
        ctx.stroke();

        // Node label
        ctx.fillStyle = '#e0e0e8';
        ctx.font = '10px SF Mono, monospace';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(node.id, node.x, node.y);
    }
}
```

### 9.5 Animations

| Event | Animation | Duration |
|-------|-----------|----------|
| Node joins | New node appears at random position, simulation settles | ~1s |
| Node leaves | Node turns red, shrinks to 0 radius, removed from simulation | 500ms |
| Leader change | Old leader loses gold ring, new leader gains gold ring with pulse | 300ms |
| Topology stabilizes | All nodes drift to equilibrium positions | 1-2s |

**Animation for node departure:**
When a node disappears from the cluster data, it is kept in the render list for 500ms with a `departing` flag. During this 500ms, its radius shrinks and opacity fades. After 500ms, it is removed from the node array entirely.

### 9.6 Interaction

- **Hover:** Shows a tooltip floating next to the hovered node with: node ID, CPU %, heap MB/max, slice count, leader/follower state.
- **Click:** Navigates to the Metrics page with the clicked node pre-selected for per-node view.
- **Drag (Forge only):** Nodes can be dragged to rearrange the layout. The d3-force simulation is restarted on drag with `simulation.alpha(0.3).restart()`.
- **Mouse detection:** Use canvas coordinate math: `distance(mouseX - node.x, mouseY - node.y) < node.radius`.

### 9.7 Scaling to 100 Nodes

| Node Count | Layout Strategy |
|------------|----------------|
| 1-10 | Force-directed with large spacing (distance: 120px) |
| 11-30 | Force-directed with medium spacing (distance: 80px), smaller nodes (r=15) |
| 31-100 | Force-directed with tight spacing (distance: 50px), smallest nodes (r=10), abbreviated labels |

**Performance safeguards:**
- Canvas rendering (not SVG DOM nodes) -- O(1) for the browser's layout engine
- `requestAnimationFrame` for render loop
- Simulation alpha decays to 0 after ~2s of settling, then no more tick computation
- Only re-render on new data or user interaction (not continuously)

---

## 10. Drill-Down Flows

### 10.1 Invocation Explorer -> Trace Detail

```
Invocation Table
  |
  +-- Click on row (e.g., "processOrder")
  |     |
  |     +-- Expands inline to show:
  |           |
  |           +-- Latency distribution chart (uPlot histogram-like)
  |           |     Data: bucket P50/P95/P99 from invocation-metrics
  |           |
  |           +-- Recent slow calls list
  |                 Data: GET /api/invocation-metrics/slow
  |                 |
  |                 +-- Click on slow call requestId
  |                       |
  |                       +-- Opens Trace Detail modal
  |                             Data: GET /api/traces/{requestId}
  |                             |
  |                             +-- Waterfall visualization:
  |                                   processOrder     [==========] 50ms
  |                                     validateInv    [====]       20ms
  |                                       dbQuery      [==]         10ms
  |                                     submitPayment  [======]     30ms
```

### 10.2 Trace Detail Modal (REQ-DD-01)

```
+----------------------------------------------------------------------+
| Trace: abc-123                                            [X Close]  |
+----------------------------------------------------------------------+
| Method           | Depth | Duration | Status | Timeline              |
|------------------+-------+----------+--------+-----------------------|
| processOrder     |   0   |   50ms   |  OK    | [==================] |
|   validateInv    |   1   |   20ms   |  OK    |   [========]         |
|     dbQuery      |   2   |   10ms   |  OK    |   [====]             |
|   submitPayment  |   1   |   30ms   |  FAIL  |          [==========]|
|     reason: TimeoutException                                         |
+----------------------------------------------------------------------+
```

**Data source:** `GET /api/traces/{requestId}` returns the trace tree nodes. The waterfall is rendered as a horizontal bar chart where each bar's x-offset represents the start time relative to the root span, and width represents duration.

### 10.3 Node Click -> Node Detail

From the topology view or the node list, clicking a node opens a detail panel:

```
+----------------------------------------------------------------------+
| Node: node-1 (LEADER)                                     [X Close] |
+----------------------------------------------------------------------+
| CPU: 45%  [=========          ] | Heap: 256/512MB [=====       ]    |
+----------------------------------------------------------------------+
| Deployed Slices:                                                     |
|   order-svc:1.0.0   ACTIVE   since 10:25:00                        |
|   inv-svc:1.0.0     ACTIVE   since 10:25:01                        |
+----------------------------------------------------------------------+
| Per-Node Metrics (last 15m):                                         |
| [uPlot: CPU + Heap over time for this node only]                    |
+----------------------------------------------------------------------+
```

**Data source:** Node metrics from `/ws/status` `nodeMetrics[]`. Historical data via `GET /api/metrics/history?range=15m`, filtered to the selected node.

---

## 11. Color System and Design Tokens

### 11.1 CSS Custom Properties

The existing color system (`style.css` lines 3-17) is preserved and extended:

```css
/* css/tokens.css */
:root {
    /* Backgrounds */
    --bg-primary:     #0a0a0f;
    --bg-secondary:   #12121a;
    --bg-card:        #1a1a24;
    --bg-elevated:    #22222e;    /* NEW: for modals, dropdowns */
    --bg-hover:       rgba(255, 255, 255, 0.02);  /* NEW: table row hover */

    /* Text */
    --text-primary:   #e0e0e8;
    --text-secondary: #888898;
    --text-muted:     #505060;
    --text-inverse:   #0a0a0f;   /* NEW: for colored badges */

    /* Accent Colors */
    --accent-blue:    #3b82f6;
    --accent-green:   #22c55e;
    --accent-yellow:  #f59e0b;
    --accent-red:     #ef4444;
    --accent-cyan:    #06b6d4;
    --accent-purple:  #8b5cf6;   /* NEW: for traces/debug */

    /* Semantic Colors */
    --color-healthy:  var(--accent-green);
    --color-warning:  var(--accent-yellow);
    --color-critical: var(--accent-red);
    --color-info:     var(--accent-blue);
    --color-leader:   var(--accent-yellow);

    /* Borders */
    --border-color:   #252530;
    --border-focus:   var(--accent-cyan);

    /* Typography */
    --font-mono:      'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
    --font-sans:      -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;

    /* Sizing */
    --radius-sm:      3px;
    --radius-md:      4px;
    --radius-lg:      6px;
    --spacing-xs:     4px;
    --spacing-sm:     6px;
    --spacing-md:     10px;
    --spacing-lg:     16px;
    --spacing-xl:     20px;

    /* Chart Colors (for per-node series) */
    --chart-1:        #06b6d4;   /* cyan */
    --chart-2:        #22c55e;   /* green */
    --chart-3:        #f59e0b;   /* yellow */
    --chart-4:        #ef4444;   /* red */
    --chart-5:        #3b82f6;   /* blue */
    --chart-6:        #8b5cf6;   /* purple */
    --chart-7:        #ec4899;   /* pink */
}
```

### 11.2 uPlot Theme Integration

uPlot uses its own styling system. The `uplot.min.css` is overridden minimally:

```css
/* css/charts.css */
.u-wrap { background: var(--bg-secondary) !important; border-radius: var(--radius-md); }
.u-legend { color: var(--text-secondary) !important; font-size: 10px; }
.u-cursor-x, .u-cursor-y { border-color: var(--text-muted) !important; }
```

### 11.3 Contrast Requirements

All text/background combinations meet WCAG 2.1 AA contrast ratio (4.5:1):

| Text Color | Background | Ratio | Pass? |
|-----------|-----------|-------|-------|
| `#e0e0e8` (primary) | `#0a0a0f` (bg-primary) | 14.5:1 | Yes |
| `#888898` (secondary) | `#0a0a0f` (bg-primary) | 5.8:1 | Yes |
| `#888898` (secondary) | `#1a1a24` (bg-card) | 4.5:1 | Yes (borderline) |
| `#505060` (muted) | `#0a0a0f` (bg-primary) | 3.2:1 | No -- labels only |

[ASSUMPTION] Muted text (`#505060`) is used only for non-essential labels and decorative text, not for critical information. This is acceptable under WCAG for decorative elements.

---

## 12. Forge Mode Detection and Panel Injection

### 12.1 Detection Flow

```
Page Load
  |
  +-- app.js: detectForge()
  |     |
  |     +-- GET /api/forge/status (preferred, requires backend addition)
  |     |   |-- 200 { "forge": true } --> enable Forge mode
  |     |   |-- 404 / error -----------> standard mode
  |     |
  |     +-- Fallback: GET /api/chaos/rolling-restart-status
  |         |-- 200 { "active": ... } -> enable Forge mode
  |         |-- 404 / error -----------> standard mode
  |
  +-- Alpine store: forge.enabled = true/false
  |
  +-- Conditional rendering:
        x-show="$store.forge.enabled"  on all Forge-only elements
```

### 12.2 What Changes in Forge Mode

| Element | Standard Mode | Forge Mode |
|---------|--------------|------------|
| Navigation bar | 6 tabs | 7 tabs (+ "Testing") |
| Overview page bottom | Empty | Chaos + Load controls |
| Topology interaction | Hover + Click | + Drag nodes |
| Event feed types | Cluster events only | + Chaos events (KILL_NODE, LOAD_SET, etc.) |

### 12.3 Graceful Degradation

Forge controls are purely additive. If a Forge endpoint returns an error mid-session (e.g., cluster restarted without Forge), the controls show inline error messages but do not crash the dashboard. All Forge API calls use the existing `apiPost()` pattern with `try/catch`.

### 12.4 Existing Forge Detection (Current Dashboard)

The current `dashboard.js` uses `loadChaosPanel()` which fetches `GET /api/panel/chaos` and injects raw HTML:

```javascript
async function loadChaosPanel() {
    const response = await fetch(`${API_BASE}/panel/chaos`);
    if (response.ok) {
        container.innerHTML = html;  // raw HTML injection
    }
}
```

**This approach is replaced.** The new dashboard does not inject raw HTML from the server. Instead, Forge controls are pre-defined in the HTML with `x-show` directives. Only the data (not the layout) comes from the server.

---

## 13. Accessibility

### 13.1 Keyboard Navigation

| Action | Key | Behavior |
|--------|-----|----------|
| Switch tab | Tab + Enter | Focus cycles through nav tabs, Enter activates |
| Close modal | Escape | Closes any open modal or expanded panel |
| Navigate table | Arrow Up/Down | Moves between rows in invocation table |
| Expand row | Enter / Space | Expands the currently focused table row |
| Topology node | Tab (in topology) | Cycles focus through nodes, shows tooltip |

### 13.2 ARIA Attributes

```html
<!-- Navigation -->
<nav role="tablist" aria-label="Dashboard navigation">
    <button role="tab" aria-selected="true" aria-controls="page-overview">Overview</button>
    <button role="tab" aria-selected="false" aria-controls="page-metrics">Metrics</button>
</nav>
<div role="tabpanel" id="page-overview" aria-labelledby="tab-overview">...</div>

<!-- Health Banner -->
<div role="status" aria-live="polite" aria-label="Cluster health status">
    <span aria-label="Health status: healthy">HEALTHY</span>
</div>

<!-- Event Feed -->
<div role="log" aria-live="polite" aria-label="Cluster events">
    <div role="listitem">...</div>
</div>

<!-- Topology Canvas -->
<canvas role="img" aria-label="Cluster topology showing 5 nodes. Leader: node-1. All nodes healthy.">
</canvas>
<!-- Screen reader fallback for topology -->
<div class="sr-only" aria-live="polite">
    Cluster has 5 nodes. Leader is node-1. All nodes are healthy.
</div>

<!-- Modals -->
<div role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <h3 id="modal-title">Trace Detail</h3>
</div>
```

### 13.3 Screen Reader Fallback for Topology

The `<canvas>` topology is inherently inaccessible to screen readers. A hidden `<div>` with `aria-live="polite"` provides a text summary:

```
"Cluster has N nodes. Leader is {leaderId}. {healthyCount} healthy, {degradedCount} degraded, {failedCount} failed."
```

This text is updated whenever the cluster store changes.

### 13.4 Focus Management

- When a modal opens, focus is trapped inside the modal.
- When a modal closes, focus returns to the element that triggered it.
- Tab order follows visual layout order (left to right, top to bottom).

---

## 14. Testing Approach

### 14.1 Testing Strategy

Since the dashboard is vanilla HTML + Alpine.js (no build step), traditional unit testing with Jest/Vitest requires a DOM environment. The recommended approach is layered:

| Layer | Tool | What It Tests |
|-------|------|---------------|
| Store Logic | Plain JS tests (any runner) | Data transformation, state updates |
| Component Rendering | Playwright/Puppeteer | DOM output given store state |
| Integration | Playwright E2E | Full page with mocked WebSocket |
| Visual Regression | Playwright screenshots | Layout and styling consistency |

### 14.2 Store Tests (Unit)

Alpine stores are plain objects. They can be tested without Alpine.js or a browser:

```javascript
// test/stores/cluster.test.js
import { clusterStore } from '../../js/stores/cluster.js';

test('update sets leader correctly', () => {
    const store = { ...clusterStore };
    store.update({
        cluster: { nodes: [{id: 'n1', isLeader: true}], leaderId: 'n1', nodeCount: 1 },
        uptimeSeconds: 100
    });
    assert.equal(store.leaderId, 'n1');
    assert.equal(store.nodeCount, 1);
});
```

For this to work, stores are written as exportable objects with an `update()` method, not as closures over `Alpine.store()`. The `app.js` file registers them with Alpine at runtime.

### 14.3 Integration Tests (E2E)

Using Playwright to test the dashboard against a mocked backend:

```javascript
// test/e2e/overview.spec.js
test('overview page shows healthy status', async ({ page }) => {
    // Mock WebSocket with known data
    await page.route('/ws/status', route => {
        // WebSocket mock setup
    });
    await page.goto('http://localhost:8080/dashboard');
    await expect(page.locator('.health-banner')).toContainText('HEALTHY');
    await expect(page.locator('.topology-canvas')).toBeVisible();
});
```

### 14.4 Mock WebSocket Server

For local development and testing, a lightweight Node.js mock server can replay recorded WebSocket messages:

```javascript
// test/mock-server.js
const WebSocket = require('ws');
const recorded = require('./fixtures/cluster-5-nodes.json');

const wss = new WebSocket.Server({ port: 8081 });
wss.on('connection', ws => {
    let i = 0;
    setInterval(() => {
        ws.send(JSON.stringify(recorded[i % recorded.length]));
        i++;
    }, 1000);
});
```

[ASSUMPTION] Developers have Node.js installed for running mock servers. This is only for development/testing, not required for the dashboard itself.

### 14.5 Topology Rendering Tests

Canvas-based rendering cannot be DOM-tested. Use Playwright screenshot comparison:

```javascript
test('topology renders 5 nodes', async ({ page }) => {
    // ... setup mock with 5 nodes
    await page.waitForTimeout(2000); // let simulation settle
    await expect(page.locator('#topology-canvas')).toHaveScreenshot('5-nodes.png', {
        maxDiffPixels: 100 // allow minor anti-aliasing differences
    });
});
```

---

## 15. Migration Plan

### 15.1 What to Keep

| Current Asset | Decision | Reason |
|---------------|----------|--------|
| CSS color palette | KEEP | Same design tokens, proven dark theme |
| Event type color mapping | KEEP | Same severity-to-color scheme |
| Panel/card styling | KEEP (adapted) | Same visual language, reorganized |
| Node list rendering logic | KEEP (in Alpine template) | Same data shape |
| REST API helpers | KEEP (simplified) | Same endpoints |
| Chart.js dependency | REMOVE | Replaced by uPlot |
| CDN script tag | REMOVE | Vendored Alpine + uPlot + d3-force |
| 500ms polling loop | REMOVE | Replaced by WebSocket |
| Raw HTML chaos panel injection | REMOVE | Replaced by Alpine x-show |
| Monolithic dashboard.js | REMOVE | Split into stores + components |

### 15.2 Migration Phases

**Phase 0: Backend Preparation**
- Add `GET /api/forge/status` endpoint to ForgeServer (clean detection)
- Verify all 3 WebSocket endpoints work with concurrent connections
- No dashboard UI changes yet

**Phase 1: New Dashboard Shell**
- New `index.html` with Alpine.js, uPlot, d3-force vendored
- WebSocket manager connecting to all 3 endpoints
- Health banner + key metrics strip (WebSocket-driven)
- Basic topology view (d3-force layout, canvas rendering)
- Coexists with old dashboard: new served at `/dashboard/v2`, old at `/dashboard`

**Phase 2: Operations Pages**
- Metrics page with uPlot time-series charts
- Invocations page with sortable table
- Alerts page with threshold editing
- Config page (largely ported from current)

**Phase 3: Drill-Downs and Interactions**
- Invocation -> slow calls -> trace detail flow
- Node click -> node detail panel
- Topology hover tooltips and click navigation

**Phase 4: Forge Integration**
- Forge detection and conditional rendering
- Chaos + load controls on overview page
- Testing tab with correlation charts
- Load config upload

**Phase 5: Polish and Cutover**
- Accessibility audit (keyboard navigation, ARIA)
- 100-node performance testing
- Visual regression test suite
- Remove old dashboard, promote v2 to `/dashboard`

### 15.3 Coexistence Strategy

During migration, both dashboards are available:

```java
// DashboardRoutes.java (modified)
public Stream<Route<?>> routes() {
    return Stream.concat(
        StaticFileRouteSource.staticFiles("/dashboard", "dashboard/").routes(),
        StaticFileRouteSource.staticFiles("/dashboard/v2", "dashboard-v2/").routes()
    );
}
```

The old dashboard at `/dashboard` remains the default until Phase 5 is complete. This allows A/B comparison and safe rollback.

---

## 16. Implementation Phases

### 16.1 Effort Estimates

| Phase | Scope | Estimated Effort |
|-------|-------|------------------|
| Phase 0 | Backend prep (forge/status endpoint) | 2 hours |
| Phase 1 | Shell + topology + health + metrics strip | 3-4 days |
| Phase 2 | Operations pages (metrics, invocations, alerts, config) | 4-5 days |
| Phase 3 | Drill-downs (trace detail, node detail) | 2-3 days |
| Phase 4 | Forge integration (chaos, load, testing page) | 2-3 days |
| Phase 5 | Polish, accessibility, testing, cutover | 2-3 days |
| **Total** | | **~14-18 days** |

### 16.2 Dependencies

```
Phase 0 ---------> Phase 1 ---------> Phase 2 ---------> Phase 5
                     |                   |
                     +-------> Phase 3 --+
                     |                   |
                     +-------> Phase 4 --+
```

Phases 2, 3, and 4 can be partially parallelized after Phase 1 is complete.

### 16.3 Success Criteria

| Criterion | Measurement |
|-----------|-------------|
| Topology renders 5 nodes in < 500ms | Playwright timer from page load to stable canvas |
| Zero polling on overview page | Network tab shows only WebSocket frames, no XHR |
| Total JS bundle < 100KB | `wc -c vendor/*.min.js js/**/*.js` |
| 100-node rendering at 60fps | Chrome DevTools Performance panel, no jank |
| Forge mode auto-detected | Testing tab appears within 1s of page load on Forge |
| All current functionality preserved | Manual comparison checklist |

---

## 17. References

### Technical Documentation

- [Aether Management API Reference](/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/docs/reference/management-api.md) -- Comprehensive REST + WebSocket endpoint documentation
- [Alpine.js Documentation](https://alpinejs.dev/) -- Reactive framework documentation
- [uPlot GitHub](https://github.com/leeoniya/uPlot) -- Time series charting library
- [d3-force Documentation](https://d3js.org/d3-force) -- Force-directed graph layout module
- [uPlot vs Chart.js Performance](https://leeoniya.github.io/uPlot/) -- Benchmark comparisons

### Internal References

- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/dashboard/src/main/resources/dashboard/` -- Current dashboard source (index.html, dashboard.js, style.css)
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/forge/forge-core/src/main/resources/static/` -- Forge dashboard source (index.html, dashboard.js, style.css)
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/DashboardWebSocketHandler.java` -- WebSocket handler for /ws/dashboard
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/DashboardMetricsPublisher.java` -- Metrics aggregation and broadcasting
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/StatusWebSocketHandler.java` -- WebSocket handler for /ws/status
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/EventWebSocketHandler.java` -- WebSocket handler for /ws/events (delta mode)
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/EventWebSocketPublisher.java` -- Delta event broadcasting
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/StatusWebSocketPublisher.java` -- Periodic status broadcasting
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/ManagementServer.java` -- HTTP server setup with all 3 WebSocket endpoints
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/node/src/main/java/org/pragmatica/aether/api/routes/DashboardRoutes.java` -- Static file serving for dashboard
- `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/forge/forge-core/src/main/java/org/pragmatica/aether/forge/ForgeServer.java` -- Forge server with WebSocket setup

### Bundle Size Analysis

- [Alpine.js Bundlephobia](https://bundlephobia.com/package/alpinejs) -- ~22KB minified, ~7KB gzipped
- [uPlot npm](https://www.npmjs.com/package/uplot) -- ~48KB minified, ~16KB gzipped
- [d3-force Bundlephobia](https://bundlephobia.com/package/d3-force) -- ~18KB minified, ~6KB gzipped

---

## Appendix A: Existing WebSocket Message Type Reference

Extracted from `DashboardWebSocketHandler.java` and `DashboardMetricsPublisher.java`:

| Message Type | Direction | Source |
|-------------|-----------|--------|
| `INITIAL_STATE` | Server -> Client | Sent on WebSocket open, contains full cluster snapshot |
| `METRICS_UPDATE` | Server -> Client | Sent every 1000ms with current metrics |
| `SUBSCRIBE` | Client -> Server | Client subscribes to streams (parsed but currently a no-op) |
| `SET_THRESHOLD` | Client -> Server | Client sets alert threshold for a metric |
| `GET_HISTORY` | Client -> Server | Client requests historical metrics for a time range |
| `HISTORY` | Server -> Client | Response to GET_HISTORY with node-keyed time series |
| `ALERT` | Server -> Client | Broadcast when a threshold is crossed |

## Appendix B: Full REST API Surface Used by Dashboard

| Endpoint | Layer | Usage |
|----------|-------|-------|
| `GET /api/status` | 1 (fallback) | Polling fallback for /ws/status |
| `GET /api/health` | 1 | Health banner data |
| `GET /api/events` | 1 (initial) | Initial event history on page load |
| `GET /api/events?since=` | 1 (fallback) | Polling fallback for /ws/events |
| `GET /api/node-metrics` | 1 | Per-node CPU/heap |
| `GET /api/invocation-metrics` | 1,2 | Entry points table |
| `GET /api/invocation-metrics?artifact=&method=` | 2 | Filtered invocation view |
| `GET /api/invocation-metrics/slow` | 2 | Slow call list for drill-down |
| `GET /api/traces` | 2 | Trace list for drill-down |
| `GET /api/traces/{requestId}` | 2 | Full trace detail |
| `GET /api/traces/stats` | 2 | Trace statistics summary |
| `GET /api/metrics/history?range=` | 2 | Historical time-series charts |
| `GET /api/metrics/comprehensive` | 2 | Detailed aggregated metrics |
| `GET /api/metrics/derived` | 2 | Computed metrics (trends, health score) |
| `GET /api/slices/status` | 1,2 | Slice instance map |
| `GET /api/blueprints` | 2 | Blueprint list |
| `GET /api/blueprint/{id}` | 2 | Blueprint detail |
| `GET /api/blueprint/{id}/status` | 2 | Blueprint deployment status |
| `GET /api/rolling-updates` | 2 | Active rolling updates |
| `GET /api/rolling-update/{id}` | 2 | Rolling update detail |
| `GET /api/rolling-update/{id}/health` | 2 | Rolling update health metrics |
| `POST /api/rolling-update/{id}/routing` | 2 | Adjust traffic routing |
| `POST /api/rolling-update/{id}/complete` | 2 | Complete rolling update |
| `POST /api/rolling-update/{id}/rollback` | 2 | Rollback rolling update |
| `POST /api/scale` | 2 | Manual scaling |
| `GET /api/alerts/active` | 2 | Active alerts |
| `GET /api/alerts/history` | 2 | Alert history |
| `POST /api/alerts/clear` | 2 | Clear active alerts |
| `GET /api/thresholds` | 2 | Alert thresholds |
| `POST /api/thresholds` | 2 | Set/update threshold |
| `DELETE /api/thresholds/{metric}` | 2 | Delete threshold |
| `GET /api/controller/config` | 2 | Controller configuration |
| `POST /api/controller/config` | 2 | Update controller config |
| `GET /api/controller/status` | 2 | Controller status |
| `GET /api/ttm/status` | 2 | TTM predictive scaling status |
| `GET /api/config` | 2 | Dynamic configuration |
| `GET /api/config/overrides` | 2 | Configuration overrides |
| `POST /api/config` | 2 | Set config override |
| `GET /api/logging/levels` | 2 | Log level overrides |
| `POST /api/logging/levels` | 2 | Set log level |
| `GET /api/scheduled-tasks` | 2 | Scheduled task list |
| `GET /api/aspects` | 3 | Dynamic aspects list |
| `POST /api/aspects` | 3 | Set aspect mode |
| `DELETE /api/aspects/{artifact}/{method}` | 3 | Remove aspect |
| `GET /api/forge/status` | 3 | Forge mode detection |
| `POST /api/chaos/kill/{nodeId}` | 3 | Kill specific node |
| `POST /api/chaos/rolling-restart` | 3 | Start rolling restart |
| `POST /api/chaos/add-node` | 3 | Add node to cluster |
| `POST /api/chaos/reset-metrics` | 3 | Reset metrics |
| `POST /api/load/set/{rate}` | 3 | Set load rate |
| `POST /api/load/ramp` | 3 | Ramp load to target |
| `POST /api/load/config` | 3 | Upload load config |
| `POST /api/load/config/enabled` | 3 | Toggle load generator |
| `POST /api/load/config/multiplier` | 3 | Set rate multiplier |
