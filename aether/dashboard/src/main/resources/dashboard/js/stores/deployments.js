document.addEventListener('alpine:init', function() {
    Alpine.store('deployments', {
        slices: [],
        blueprints: [],
        rollingUpdates: [],

        updateFromStatus(data) {
            if (data.slices && Array.isArray(data.slices)) {
                this.slices = data.slices.map(function(s) {
                    if (typeof s === 'string') {
                        return { artifact: s, state: 'ACTIVE', instances: [] };
                    }
                    return {
                        artifact: s.artifact,
                        state: s.state || 'ACTIVE',
                        instances: (s.instances || []).map(function(i) {
                            return { nodeId: i.nodeId, state: i.state || 'ACTIVE' };
                        })
                    };
                });
            }
        },

        updateFromWsDashboard(data) {
            if (data.deployments && Array.isArray(data.deployments)) {
                this.slices = data.deployments.map(function(d) {
                    return {
                        artifact: d.artifact,
                        state: d.state || 'ACTIVE',
                        instances: (d.instances || []).map(function(i) {
                            return { nodeId: i.nodeId, state: i.state || 'ACTIVE' };
                        })
                    };
                });
            }
        },

        async refreshSlices() {
            var data = await RestClient.get('/api/slices/status');
            if (data) {
                this.updateFromStatus({ slices: data });
            }
        }
    });
});
