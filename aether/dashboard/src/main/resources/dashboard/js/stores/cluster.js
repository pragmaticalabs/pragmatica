document.addEventListener('alpine:init', function() {
    Alpine.store('cluster', {
        nodes: [],
        leaderId: '',
        targetClusterSize: 0,
        healthy: true,
        // #294: never fabricate a degraded verdict before the first health probe returns — only
        // app.js's checkHealth() flips this. `degraded` means an explicit "unhealthy" status was
        // RECEIVED; a probe that answers with a 404 (reachable, no health route here) fails OPEN
        // and never touches this. See `healthUnknown` below for the third state: a probe that could
        // not reach the server at all leaves `degraded` exactly as it was.
        degraded: false,
        // #294 (three-state health gate, corrected after #846 review): distinct from `degraded`.
        // Set true only when BOTH health paths fail with a network-level error (refused, timeout) —
        // the server could not be reached at all, as opposed to being reached and found unhealthy,
        // or reached and found to have no health route (both of those are `degraded`/false, never
        // this). checkHealth() never overwrites a prior `degraded=true` when this is set — an
        // outage on top of a known-degraded cluster must not read back as "healthy". Cleared the
        // moment either probe answers anything, even a 404. While true, pollers back off to a slow
        // 10s retry via `unknownRetryDue()` instead of hammering a dead backend at full cadence, and
        // RestClient suppresses the resulting network-error toasts (see rest-client.js).
        healthUnknown: false,
        _lastUnknownRetryAt: 0,
        _lastHealthProbeRetryAt: 0,

        // Shared throttle for the DATA-poll timers only (app.js's secondary timer and its primary
        // timer's post-checkHealth batch, requests.js's own) so they agree on one slow retry cadence
        // during an unknown-health outage instead of each independently hammering a dead backend.
        // Deliberately NOT used to gate the health re-probe itself (see healthProbeRetryDue below):
        // this is a read-and-consume throttle, so whichever timer's tick calls it first in a window
        // wins the slot and the others skip that tick — fine for data staleness, but when the
        // primary timer's checkHealth() call shared this same slot (#846 review), a different
        // timer winning the slot first could make checkHealth() skip an entire cycle, pushing
        // recovery detection past the intended 10s bound by an amount that depended on interval
        // drift between the timers.
        unknownRetryDue() {
            var now = Date.now();
            if (!this._lastUnknownRetryAt || now - this._lastUnknownRetryAt >= 10000) {
                this._lastUnknownRetryAt = now;
                return true;
            }
            return false;
        },

        // Dedicated to the health re-probe (app.js's primary timer's checkHealth() call) alone.
        // No other timer reads or consumes this throttle, so the re-probe fires on a fixed ~10s
        // cadence regardless of what the data-poll timers above are doing in the same window —
        // giving recovery detection a fixed bound instead of one that depends on which timer's
        // tick happens to consume unknownRetryDue()'s shared slot first.
        healthProbeRetryDue() {
            var now = Date.now();
            if (!this._lastHealthProbeRetryAt || now - this._lastHealthProbeRetryAt >= 10000) {
                this._lastHealthProbeRetryAt = now;
                return true;
            }
            return false;
        },
        uptimeSeconds: 0,
        controllerConfig: null,
        ttmStatus: 'DISABLED',
        logLevels: {},

        updateFromStatus(data) {
            if (data.cluster) {
                this.leaderId = data.cluster.leaderId || '';
                this.targetClusterSize = data.cluster.nodeCount || data.targetClusterSize || 0;
                // Populate nodes from cluster.nodes (REST /api/nodes/status) when nodeMetrics not available
                if (data.cluster.nodes && Array.isArray(data.cluster.nodes) && !data.nodeMetrics) {
                    var self = this;
                    data.cluster.nodes.forEach(function(n) {
                        var nodeId = n.id || n.nodeId;
                        var existing = self.nodes.find(function(e) { return e.nodeId === nodeId; });
                        if (!existing) {
                            self.nodes.push({
                                nodeId: nodeId,
                                isLeader: n.isLeader || nodeId === self.leaderId,
                                cpuUsage: 0, heapUsedMb: 0, heapMaxMb: 0,
                                lifecycleState: 'ON_DUTY', slices: []
                            });
                        } else {
                            existing.isLeader = n.isLeader || nodeId === self.leaderId;
                        }
                    });
                }
            }
            if (data.targetClusterSize) {
                this.targetClusterSize = data.targetClusterSize;
            }
            if (data.uptimeSeconds != null) {
                this.uptimeSeconds = data.uptimeSeconds;
            }
            if (data.nodeMetrics && Array.isArray(data.nodeMetrics)) {
                this.nodes = data.nodeMetrics.map(function(n) {
                    return {
                        nodeId: n.nodeId,
                        isLeader: n.isLeader,
                        cpuUsage: n.cpuUsage || 0,
                        heapUsedMb: n.heapUsedMb || 0,
                        heapMaxMb: n.heapMaxMb || 0,
                        lifecycleState: n.derivedStatus || n.kvState || 'ON_DUTY',
                        slices: []
                    };
                });
            }
            // Derive per-node slice assignments from slices data
            if (data.slices && Array.isArray(data.slices)) {
                var nodeSliceMap = {};
                data.slices.forEach(function(s) {
                    var artifact = typeof s === 'string' ? s : s.artifact;
                    var instances = (typeof s === 'string') ? [] : (s.instances || []);
                    instances.forEach(function(inst) {
                        if (!nodeSliceMap[inst.nodeId]) nodeSliceMap[inst.nodeId] = [];
                        nodeSliceMap[inst.nodeId].push({artifact: artifact, state: inst.state || 'ACTIVE'});
                    });
                });
                this.nodes.forEach(function(node) {
                    node.slices = nodeSliceMap[node.nodeId] || [];
                });
            }
            this.healthy = this.nodes.length >= this.targetClusterSize && this.nodes.length > 0;
        },

        updateSlices(data) {
            var slices = data.slices || (Array.isArray(data) ? data : []);
            if (!slices.length) return;
            var nodeSliceMap = {};
            slices.forEach(function(s) {
                (s.instances || []).forEach(function(inst) {
                    if (!nodeSliceMap[inst.nodeId]) nodeSliceMap[inst.nodeId] = [];
                    nodeSliceMap[inst.nodeId].push({artifact: s.artifact, state: inst.state || 'ACTIVE'});
                });
            });
            this.nodes.forEach(function(node) {
                node.slices = nodeSliceMap[node.nodeId] || node.slices || [];
            });
        },

        updateFromWsDashboard(data) {
            if (data.load) {
                var self = this;
                Object.keys(data.load).forEach(function(nodeId) {
                    var metrics = data.load[nodeId];
                    var existing = self.nodes.find(function(n) { return n.nodeId === nodeId; });
                    if (existing) {
                        existing.cpuUsage = metrics['cpu.usage'] || existing.cpuUsage;
                        existing.heapUsedMb = Math.round((metrics['heap.used'] || 0) / 1024 / 1024);
                        existing.heapMaxMb = Math.round((metrics['heap.max'] || 1) / 1024 / 1024);
                    }
                });
            }
            // Update per-node slices from deployments if available
            if (data.deployments && Array.isArray(data.deployments)) {
                var nodeSliceMap = {};
                data.deployments.forEach(function(d) {
                    (d.instances || []).forEach(function(inst) {
                        if (!nodeSliceMap[inst.nodeId]) nodeSliceMap[inst.nodeId] = [];
                        nodeSliceMap[inst.nodeId].push({artifact: d.artifact, state: inst.state || 'ACTIVE'});
                    });
                });
                this.nodes.forEach(function(node) {
                    node.slices = nodeSliceMap[node.nodeId] || [];
                });
            }
        },

        async refreshConfig() {
            var data = await RestClient.get('/api/controller/config');
            if (data) this.controllerConfig = data;
        },

        async saveConfig() {
            if (this.controllerConfig) {
                await RestClient.post('/api/controller/config', this.controllerConfig);
            }
        },

        updateConfig(key, value) {
            if (this.controllerConfig) {
                this.controllerConfig[key] = isNaN(parseFloat(value)) ? value : parseFloat(value);
            }
        },

        async refreshTtm() {
            var data = await RestClient.get('/api/ttm/status');
            if (data) this.ttmStatus = data.state || data.status || 'DISABLED';
        },

        async refreshLogLevels() {
            var data = await RestClient.get('/api/logging/levels');
            if (data) this.logLevels = data;
        },

        async setLogLevel(logger, level) {
            await RestClient.post('/api/logging/levels', { logger: logger, level: level });
        }
    });
});
