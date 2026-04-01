document.addEventListener('alpine:init', function() {
    Alpine.store('observability', {
        depthConfigs: [],
        editingKey: null,
        editDepth: 0,
        newRule: { artifact: '', method: '', depthThreshold: 1 },

        async refresh() {
            var data = await RestClient.get('/api/observability/depth');
            if (data && Array.isArray(data)) {
                this.depthConfigs = data;
            }
        },

        startEdit(config) {
            this.editingKey = config.key;
            this.editDepth = config.depthThreshold;
        },

        cancelEdit() {
            this.editingKey = null;
            this.editDepth = 0;
        },

        async saveEdit(config) {
            var parts = config.key.split('/');
            var artifact = parts.slice(0, -1).join('/');
            var method = parts[parts.length - 1];
            var result = await RestClient.post('/api/observability/depth', {
                artifact: artifact,
                method: method,
                depthThreshold: this.editDepth
            });
            if (result) {
                config.depthThreshold = this.editDepth;
            }
            this.editingKey = null;
            this.editDepth = 0;
        },

        async addRule() {
            var rule = this.newRule;
            if (!rule.artifact || !rule.method) return;
            var result = await RestClient.post('/api/observability/depth', {
                artifact: rule.artifact,
                method: rule.method,
                depthThreshold: rule.depthThreshold
            });
            if (result) {
                this.depthConfigs.push({
                    key: rule.artifact + '/' + rule.method,
                    depthThreshold: rule.depthThreshold,
                    targetTracesPerSec: 500
                });
                this.newRule = { artifact: '', method: '', depthThreshold: 1 };
            }
        },

        async removeRule(config) {
            var parts = config.key.split('/');
            var artifact = parts.slice(0, -1).join('/');
            var method = parts[parts.length - 1];
            var result = await RestClient.del('/api/observability/depth/' + encodeURIComponent(artifact) + '/' + encodeURIComponent(method));
            if (result) {
                this.depthConfigs = this.depthConfigs.filter(function(c) { return c.key !== config.key; });
            }
        },

        artifactFromKey(key) {
            var parts = key.split('/');
            return parts.slice(0, -1).join('/');
        },

        methodFromKey(key) {
            var parts = key.split('/');
            return parts[parts.length - 1];
        }
    });
});
