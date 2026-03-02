document.addEventListener('alpine:init', function() {
    Alpine.store('cluster', {
        nodes: [],
        leaderId: '',
        targetClusterSize: 0,
        healthy: true,
        uptimeSeconds: 0,
        controllerConfig: null,
        ttmStatus: 'DISABLED',
        logLevels: {},
        observabilityDepth: {},

        updateFromStatus(data) {
            if (data.cluster) {
                this.leaderId = data.cluster.leaderId || '';
                this.targetClusterSize = data.cluster.nodeCount || data.targetClusterSize || 0;
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
                        lifecycleState: n.lifecycleState || 'ON_DUTY',
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
                        nodeSliceMap[inst.nodeId].push(artifact);
                    });
                });
                this.nodes.forEach(function(node) {
                    node.slices = nodeSliceMap[node.nodeId] || [];
                });
            }
            this.healthy = this.nodes.length >= this.targetClusterSize && this.nodes.length > 0;
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
                        nodeSliceMap[inst.nodeId].push(d.artifact);
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
                await RestClient.put('/api/controller/config', this.controllerConfig);
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
            var data = await RestClient.get('/api/log-levels');
            if (data) this.logLevels = data;
        },

        async setLogLevel(logger, level) {
            await RestClient.put('/api/log-levels/' + encodeURIComponent(logger), { level: level });
        },

        async refreshObservabilityDepth() {
            var data = await RestClient.get('/api/observability/depth');
            if (data) this.observabilityDepth = data;
        },

        async setObservabilityDepth(key, depth) {
            await RestClient.put('/api/observability/depth', { key: key, depth: depth });
        }
    });
});
