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
        history: {
            timestamps: [],
            rps: [],
            successRate: [],
            p50: [],
            p95: [],
            p99: [],
            cpu: {},
            heap: {}
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
            var h = this.history;
            h.timestamps.push(now);
            h.rps.push(this.rps);
            h.successRate.push(this.successRate);
            h.p50.push(this.p50);
            h.p95.push(this.p95);
            h.p99.push(this.p99);
            if (h.timestamps.length > this.maxHistory) {
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
            });
        },

        setTimeRange(range) {
            this.timeRange = range;
        }
    });
});
