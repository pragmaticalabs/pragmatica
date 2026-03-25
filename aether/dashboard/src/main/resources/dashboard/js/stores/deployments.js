document.addEventListener('alpine:init', function() {
    Alpine.store('deployments', {
        slices: [],
        blueprints: [],
        rollingUpdates: [],
        routes: [],

        _securityLabel: function(security) {
            if (!security || security === 'PUBLIC') return '\uD83D\uDD13 Public';
            if (security === 'AUTHENTICATED') return '\uD83D\uDD11 Authenticated';
            if (security.startsWith('ROLE:')) return '\uD83D\uDC64 Role:' + security.substring(5);
            return security;
        },

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
            if (data.routes && Array.isArray(data.routes)) {
                var self = this;
                this.routes = data.routes.map(function(r) {
                    return {
                        method: r.method,
                        path: r.path,
                        nodes: r.nodes || [],
                        security: r.security || 'PUBLIC',
                        securityLabel: self._securityLabel(r.security || 'PUBLIC')
                    };
                });
            }
        },

        async refreshSlices() {
            var data = await RestClient.get('/api/slices/status');
            if (data) {
                this.updateFromStatus({ slices: data });
            }
        },

        async refreshRoutes() {
            var data = await RestClient.get('/api/routes');
            if (data && data.routes) {
                var self = this;
                this.routes = data.routes.map(function(r) {
                    return {
                        method: r.method,
                        path: r.path,
                        nodes: r.nodes || [],
                        security: r.security || 'PUBLIC',
                        securityLabel: self._securityLabel(r.security || 'PUBLIC')
                    };
                });
            }
        }
    });
});
