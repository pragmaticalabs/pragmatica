document.addEventListener('alpine:init', function() {
    Alpine.store('requests', {
        invocations: [],
        slowInvocations: [],
        traces: [],
        traceStats: null,
        timeRange: '5m',
        methodFilter: '',
        statusFilter: '',
        refreshTimer: null,
        sortField: 'count',
        sortDir: -1,
        expandedRow: null,
        expandedSlow: null,

        // --- Data Fetching ---

        async fetchInvocations() {
            var data = await RestClient.get('/api/invocation-metrics');
            if (data && data.snapshots) {
                this.invocations = data.snapshots.map(this.mapSnapshot);
            }
        },

        async fetchSlowInvocations() {
            var data = await RestClient.get('/api/invocation-metrics/slow');
            if (data && data.slowInvocations) {
                this.slowInvocations = data.slowInvocations;
            }
        },

        async fetchTraces() {
            var data = await RestClient.get('/api/traces?limit=50');
            if (data && Array.isArray(data)) {
                this.traces = data;
            }
        },

        async fetchTraceStats() {
            var data = await RestClient.get('/api/traces/stats');
            if (data) {
                this.traceStats = data;
            }
        },

        async refreshAll() {
            this.fetchInvocations();
            this.fetchSlowInvocations();
            this.fetchTraces();
            this.fetchTraceStats();
        },

        startPolling() {
            if (this.refreshTimer) return;
            var self = this;
            this.refreshAll();
            this.refreshTimer = setInterval(function() {
                self.refreshAll();
            }, 3000);
        },

        stopPolling() {
            if (this.refreshTimer) {
                clearInterval(this.refreshTimer);
                this.refreshTimer = null;
            }
        },

        // --- Mapping ---

        mapSnapshot(snap) {
            var errorRate = snap.count > 0 ? snap.failureCount / snap.count : 0;
            var errorsPerMin = snap.failureCount > 0 ? snap.failureCount : 0;
            return {
                artifact: snap.artifact,
                method: snap.method,
                count: snap.count,
                successCount: snap.successCount,
                failureCount: snap.failureCount,
                successRate: snap.count > 0 ? snap.successCount / snap.count : 1,
                avgDurationMs: snap.avgDurationMs,
                p50Ns: snap.p50DurationNs || 0,
                p95Ns: snap.p95DurationNs || 0,
                p99Ns: snap.p99DurationNs || snap.p95DurationNs || 0,
                errorRate: errorRate,
                errorsPerMin: errorsPerMin,
                slowCalls: snap.slowInvocations || 0
            };
        },

        // --- Filtering ---

        filteredInvocations() {
            var self = this;
            var invs = this.invocations.filter(function(inv) {
                if (self.methodFilter && inv.method.toLowerCase().indexOf(self.methodFilter.toLowerCase()) === -1) return false;
                if (self.statusFilter === 'success' && inv.errorRate > 0.01) return false;
                if (self.statusFilter === 'error' && inv.errorRate < 0.01) return false;
                return true;
            });
            return this.sortInvocations(invs);
        },

        filteredSlowInvocations() {
            var self = this;
            return this.slowInvocations.filter(function(slow) {
                if (self.methodFilter && slow.method.toLowerCase().indexOf(self.methodFilter.toLowerCase()) === -1) return false;
                if (self.statusFilter === 'success' && !slow.success) return false;
                if (self.statusFilter === 'error' && slow.success) return false;
                return true;
            });
        },

        // --- Sorting ---

        sortInvocations(invs) {
            var field = this.sortField;
            var dir = this.sortDir;
            return invs.slice().sort(function(a, b) {
                var va = a[field] || 0;
                var vb = b[field] || 0;
                if (typeof va === 'string') return dir * va.localeCompare(vb);
                return dir * (va - vb);
            });
        },

        toggleSort(field) {
            if (this.sortField === field) {
                this.sortDir = -this.sortDir;
            } else {
                this.sortField = field;
                this.sortDir = -1;
            }
        },

        sortIndicator(field) {
            if (this.sortField !== field) return '';
            return this.sortDir > 0 ? ' \u25B2' : ' \u25BC';
        },

        // --- Expansion ---

        toggleExpand(inv) {
            var key = inv.artifact + ':' + inv.method;
            this.expandedRow = this.expandedRow === key ? null : key;
        },

        isExpanded(inv) {
            return this.expandedRow === (inv.artifact + ':' + inv.method);
        },

        toggleSlowExpand(slow) {
            var key = slow.timestampNs + ':' + slow.method;
            this.expandedSlow = this.expandedSlow === key ? null : key;
        },

        isSlowExpanded(slow) {
            return this.expandedSlow === (slow.timestampNs + ':' + slow.method);
        },

        // --- Time range ---

        setTimeRange(range) {
            this.timeRange = range;
        },

        // --- Helpers ---

        formatSlowTimestamp(timestampNs) {
            if (!timestampNs) return '--:--';
            var ms = timestampNs / 1000000;
            var d = new Date(ms);
            return d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
        },

        tracesByRequest(requestId) {
            return this.traces.filter(function(t) { return t.requestId === requestId; });
        }
    });
});
