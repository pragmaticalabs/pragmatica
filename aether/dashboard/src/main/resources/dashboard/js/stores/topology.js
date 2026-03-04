document.addEventListener('alpine:init', function() {
    Alpine.store('topology', {
        nodes: [],
        edges: [],
        searchQuery: '',

        updateFromWsDashboard(data) {
            if (data.topology) {
                this.nodes = data.topology.nodes || [];
                this.edges = data.topology.edges || [];
            }
        },

        async refresh() {
            var data = await RestClient.get('/api/topology');
            if (data) {
                this.nodes = data.nodes || [];
                this.edges = data.edges || [];
            }
        }
    });
});
