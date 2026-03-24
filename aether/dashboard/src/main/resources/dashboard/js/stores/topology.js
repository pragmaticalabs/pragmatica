document.addEventListener('alpine:init', function() {
    Alpine.store('topology', {
        nodes: [],
        edges: [],
        searchQuery: '',
        _lastHash: '',

        _computeHash: function(nodes, edges) {
            var nodeIds = (nodes || []).map(function(n) { return n.id + ':' + n.type; }).sort().join(',');
            var edgeIds = (edges || []).map(function(e) { return e.from + '->' + e.to; }).sort().join(',');
            return nodeIds + '|' + edgeIds;
        },

        updateFromWsDashboard(data) {
            if (data.topology) {
                var newNodes = data.topology.nodes || [];
                var newEdges = data.topology.edges || [];
                var hash = this._computeHash(newNodes, newEdges);
                if (hash !== this._lastHash) {
                    this._lastHash = hash;
                    this.nodes = newNodes;
                    this.edges = newEdges;
                }
            }
        },

        async refresh() {
            var data = await RestClient.get('/api/topology');
            if (data) {
                this.nodes = data.nodes || [];
                this.edges = data.edges || [];
                this._lastHash = this._computeHash(this.nodes, this.edges);
            }
        }
    });
});
