document.addEventListener('alpine:init', function() {
    Alpine.store('forge', {
        isForge: false,
        loadState: 'IDLE',
        loadConfigText: '',
        targetCount: 0,
        targets: [],

        async detectForge() {
            try {
                var response = await fetch('/api/forge/status');
                if (response.ok) {
                    var data = await response.json();
                    this.isForge = data.forge === true;
                } else {
                    this.isForge = false;
                }
            } catch (e) {
                this.isForge = false;
            }
        },

        updateFromStatus(data) {
            if (data.load) {
                this.loadState = data.load.state || 'IDLE';
                this.targetCount = data.load.targetCount || 0;
            }
            if (data.loadTargets) {
                this.targets = data.loadTargets.map(function(t) {
                    return {
                        name: t.name,
                        targetRate: t.targetRate || 0,
                        actualRate: t.actualRate || 0,
                        totalRequests: t.requests || 0,
                        successCount: t.success || 0,
                        failureCount: t.failures || 0,
                        avgLatencyMs: t.avgLatencyMs || 0,
                        successRate: t.successRate != null ? t.successRate : 1
                    };
                });
            }
        },

        async killNode() {
            var nodes = Alpine.store('cluster').nodes;
            var nonLeader = nodes.find(function(n) { return !n.isLeader; });
            if (nonLeader) {
                await RestClient.post('/api/chaos/kill/' + nonLeader.nodeId);
            }
        },

        async killLeader() {
            var leaderId = Alpine.store('cluster').leaderId;
            if (leaderId) {
                await RestClient.post('/api/chaos/kill/' + leaderId);
            }
        },

        async rollingRestart() {
            await RestClient.post('/api/chaos/start-rolling-restart');
        },

        async addNode() {
            await RestClient.post('/api/chaos/add-node');
        },

        async startLoad() {
            await RestClient.post('/api/load/start');
            this.loadState = 'RUNNING';
        },

        async stopLoad() {
            await RestClient.post('/api/load/stop');
            this.loadState = 'IDLE';
        },

        async pauseLoad() {
            await RestClient.post('/api/load/pause');
            this.loadState = 'PAUSED';
        },

        async resumeLoad() {
            await RestClient.post('/api/load/resume');
            this.loadState = 'RUNNING';
        },

        async setRate(rate) {
            await RestClient.post('/api/load/rate/' + rate);
        },

        async uploadConfig() {
            if (!this.loadConfigText) return;
            // TOML config is sent as raw text, not JSON
            try {
                await fetch('/api/load/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: this.loadConfigText
                });
            } catch (e) {
                console.error('Failed to upload config:', e);
            }
        },

        async resetMetrics() {
            await RestClient.post('/api/chaos/reset-metrics');
        }
    });
});
