document.addEventListener('alpine:init', function() {
    Alpine.store('governors', {
        governors: [],
        clusterTopology: null,

        updateFromWsDashboard(data) {
            if (data.governors && Array.isArray(data.governors)) {
                this.governors = data.governors;
            }
            if (data.clusterTopology) {
                this.clusterTopology = data.clusterTopology;
            }
        },

        async refresh() {
            var data = await RestClient.get('/api/cluster/governors');
            if (data && data.governors) {
                this.governors = data.governors;
            }
            var topo = await RestClient.get('/api/cluster/topology');
            if (topo) {
                this.clusterTopology = topo;
            }
        },

        totalMembers() {
            return this.governors.reduce(function(sum, g) { return sum + (g.memberCount || 0); }, 0);
        },

        coreCount() {
            return this.clusterTopology ? this.clusterTopology.coreCount : 0;
        },

        workerCount() {
            return this.clusterTopology ? this.clusterTopology.workerCount : 0;
        }
    });
});
