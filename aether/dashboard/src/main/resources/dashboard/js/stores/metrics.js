document.addEventListener('alpine:init', function() {
    Alpine.store('metrics', {
        rps: 0,
        successRate: 1,
        errorRate: 0,
        avgLatencyMs: 0,
        p50: 0,
        p95: 0,
        p99: 0,
        totalRequests: 0,
        successCount: 0,
        failureCount: 0,
        timeRange: '5m',
        perNode: false,
        entryPoints: [],
        lastHistoryPush: 0,
        historyMinInterval: 1.5,
        nodeMetrics: {},
        history: {
            timestamps: [],
            rps: [],
            successRate: [],
            p50: [],
            p95: [],
            p99: [],
            cpu: {},
            heap: {},
            nodeRps: {},
            nodeLatency: {},
            nodeSuccessRate: {}
        },
        maxHistory: 300,

        updateFromStatus(data) {
            if (data.metrics) {
                this.rps = data.metrics.requestsPerSecond || 0;
                this.successRate = data.metrics.successRate != null ? data.metrics.successRate : 1;
                this.avgLatencyMs = data.metrics.avgLatencyMs || 0;
                if (data.metrics.totalSuccess != null) this.successCount = data.metrics.totalSuccess;
                if (data.metrics.totalFailures != null) this.failureCount = data.metrics.totalFailures;
                if (this.successCount || this.failureCount) this.totalRequests = this.successCount + this.failureCount;
            }
            if (data.aetherMetrics) {
                this.rps = data.aetherMetrics.rps || this.rps;
                this.successRate = data.aetherMetrics.successRate != null ? data.aetherMetrics.successRate : this.successRate;
                this.avgLatencyMs = data.aetherMetrics.avgLatencyMs || this.avgLatencyMs;
                if (data.aetherMetrics.totalInvocations != null) this.totalRequests = data.aetherMetrics.totalInvocations;
                if (data.aetherMetrics.totalSuccess != null) this.successCount = data.aetherMetrics.totalSuccess;
                if (data.aetherMetrics.totalFailures != null) this.failureCount = data.aetherMetrics.totalFailures;
            }
            // Map loadTargets to entry points as REST fallback
            if (data.loadTargets && Array.isArray(data.loadTargets) && data.loadTargets.length > 0) {
                this.entryPoints = data.loadTargets.map(function(t) {
                    return {
                        method: t.name,
                        artifact: '',
                        count: t.requests || 0,
                        successCount: t.success || 0,
                        failureCount: t.failures || 0,
                        successRate: t.successRate != null ? t.successRate : 1,
                        avgDurationMs: t.avgLatencyMs || 0,
                        errorRate: 0,
                        slowCalls: 0
                    };
                });
            }
            // Map Aether invocation metrics (always available via gossip, takes priority)
            if (data.invocations && Array.isArray(data.invocations) && data.invocations.length > 0) {
                this.entryPoints = data.invocations.map(function(inv) {
                    return {
                        method: inv.method,
                        artifact: inv.artifact,
                        count: inv.count || 0,
                        successCount: inv.successCount || 0,
                        failureCount: inv.failureCount || 0,
                        successRate: inv.count > 0 ? inv.successCount / inv.count : 1,
                        avgDurationMs: inv.avgDurationMs || 0,
                        errorRate: inv.errorRate || 0,
                        slowCalls: 0
                    };
                });
            }
            this.pushHistory();
        },

        updateFromWsDashboard(data) {
            if (data.aggregates) {
                this.rps = data.aggregates.rps || 0;
                this.successRate = data.aggregates.successRate != null ? data.aggregates.successRate : 1;
                this.errorRate = data.aggregates.errorRate || 0;
                this.avgLatencyMs = data.aggregates.avgLatencyMs || 0;
                this.p50 = this.avgLatencyMs * 0.8;
                this.p95 = this.avgLatencyMs * 2.5;
                this.p99 = this.avgLatencyMs * 5;
            }
            if (data.nodeMetrics) {
                this.nodeMetrics = data.nodeMetrics;
            }
            if (data.invocations && data.invocations.length > 0) {
                this.entryPoints = data.invocations.map(function(inv) {
                    return {
                        method: inv.method,
                        artifact: inv.artifact,
                        count: inv.count,
                        successCount: inv.successCount,
                        failureCount: inv.failureCount,
                        successRate: inv.count > 0 ? inv.successCount / inv.count : 1,
                        avgDurationMs: inv.avgDurationMs,
                        errorRate: inv.errorRate,
                        slowCalls: inv.slowCalls || 0
                    };
                });
            }
            // Always recompute totals from entry points
            this.totalRequests = this.entryPoints.reduce(function(a, ep) { return a + (ep.count || 0); }, 0);
            this.successCount = this.entryPoints.reduce(function(a, ep) { return a + (ep.successCount || 0); }, 0);
            this.failureCount = this.entryPoints.reduce(function(a, ep) { return a + (ep.failureCount || 0); }, 0);
            this.pushHistory();
        },

        pushHistory() {
            var now = Date.now() / 1000;
            // Bug 3 fix: Deduplicate history pushes from concurrent REST + WS sources.
            // Without this guard, both sources push points <1s apart causing saw-tooth artifacts.
            if (now - this.lastHistoryPush < this.historyMinInterval) return;
            this.lastHistoryPush = now;

            var h = this.history;
            h.timestamps.push(now);
            h.rps.push(this.rps);
            h.successRate.push(this.successRate);
            h.p50.push(this.p50);
            h.p95.push(this.p95);
            h.p99.push(this.p99);
            // Trim to time range, with maxHistory as absolute cap
            var cutoff = now - this.timeRangeSeconds();
            while (h.timestamps.length > 0 && (h.timestamps[0] < cutoff || h.timestamps.length > this.maxHistory)) {
                h.timestamps.shift();
                h.rps.shift();
                h.successRate.shift();
                h.p50.shift();
                h.p95.shift();
                h.p99.shift();
            }
        },

        updateNodeHistory(nodes) {
            var h = this.history;
            nodes.forEach(function(node) {
                if (!h.cpu[node.nodeId]) h.cpu[node.nodeId] = [];
                if (!h.heap[node.nodeId]) h.heap[node.nodeId] = [];
                h.cpu[node.nodeId].push(node.cpuUsage || 0);
                h.heap[node.nodeId].push(node.heapUsedMb || 0);
                if (h.cpu[node.nodeId].length > 300) h.cpu[node.nodeId].shift();
                if (h.heap[node.nodeId].length > 300) h.heap[node.nodeId].shift();

                // Per-node metric history for per-node chart view
                if (!h.nodeRps[node.nodeId]) h.nodeRps[node.nodeId] = [];
                if (!h.nodeLatency[node.nodeId]) h.nodeLatency[node.nodeId] = [];
                if (!h.nodeSuccessRate[node.nodeId]) h.nodeSuccessRate[node.nodeId] = [];
                var rps = (node.rps != null) ? node.rps : 0;
                var latency = (node.avgLatencyMs != null) ? node.avgLatencyMs : 0;
                var sr = (node.successRate != null) ? node.successRate : 1;
                h.nodeRps[node.nodeId].push(rps);
                h.nodeLatency[node.nodeId].push(latency);
                h.nodeSuccessRate[node.nodeId].push(sr);
                if (h.nodeRps[node.nodeId].length > 300) h.nodeRps[node.nodeId].shift();
                if (h.nodeLatency[node.nodeId].length > 300) h.nodeLatency[node.nodeId].shift();
                if (h.nodeSuccessRate[node.nodeId].length > 300) h.nodeSuccessRate[node.nodeId].shift();
            });
        },

        setTimeRange(range) {
            this.timeRange = range;
            this.trimHistoryToRange();
        },

        timeRangeSeconds() {
            var map = { '5m': 300, '15m': 900, '1h': 3600, '2h': 7200 };
            return map[this.timeRange] || 300;
        },

        trimHistoryToRange() {
            var h = this.history;
            if (h.timestamps.length === 0) return;
            var cutoff = Date.now() / 1000 - this.timeRangeSeconds();
            var startIdx = 0;
            while (startIdx < h.timestamps.length && h.timestamps[startIdx] < cutoff) {
                startIdx++;
            }
            if (startIdx === 0) return;
            h.timestamps = h.timestamps.slice(startIdx);
            h.rps = h.rps.slice(startIdx);
            h.successRate = h.successRate.slice(startIdx);
            h.p50 = h.p50.slice(startIdx);
            h.p95 = h.p95.slice(startIdx);
            h.p99 = h.p99.slice(startIdx);
            Object.keys(h.cpu).forEach(function(k) { h.cpu[k] = h.cpu[k].slice(startIdx); });
            Object.keys(h.heap).forEach(function(k) { h.heap[k] = h.heap[k].slice(startIdx); });
            Object.keys(h.nodeRps).forEach(function(k) { h.nodeRps[k] = h.nodeRps[k].slice(startIdx); });
            Object.keys(h.nodeLatency).forEach(function(k) { h.nodeLatency[k] = h.nodeLatency[k].slice(startIdx); });
            Object.keys(h.nodeSuccessRate).forEach(function(k) { h.nodeSuccessRate[k] = h.nodeSuccessRate[k].slice(startIdx); });
        },

        seedFromInitialState(data) {
            // Issue 13: Populate first data point from initial state so charts are not blank
            if (this.history.timestamps.length > 0) return;
            var now = Date.now() / 1000;
            if (data.aggregates) {
                this.rps = data.aggregates.rps || 0;
                this.successRate = data.aggregates.successRate != null ? data.aggregates.successRate : 1;
                this.avgLatencyMs = data.aggregates.avgLatencyMs || 0;
                this.p50 = this.avgLatencyMs * 0.8;
                this.p95 = this.avgLatencyMs * 2.5;
                this.p99 = this.avgLatencyMs * 5;
            }
            if (data.metricsHistory) {
                var mh = data.metricsHistory;
                this.history.timestamps = mh.timestamps || [now];
                this.history.rps = mh.rps || [this.rps];
                this.history.successRate = mh.successRate || [this.successRate];
                this.history.p50 = mh.p50 || [this.p50];
                this.history.p95 = mh.p95 || [this.p95];
                this.history.p99 = mh.p99 || [this.p99];
            } else {
                this.lastHistoryPush = now;
                this.history.timestamps.push(now);
                this.history.rps.push(this.rps);
                this.history.successRate.push(this.successRate);
                this.history.p50.push(this.p50);
                this.history.p95.push(this.p95);
                this.history.p99.push(this.p99);
            }
        }
    });
});
