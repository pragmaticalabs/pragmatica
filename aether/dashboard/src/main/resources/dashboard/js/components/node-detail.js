window.NodeDetail = {
    expandedNode: null,

    toggle(nodeId) {
        this.expandedNode = this.expandedNode === nodeId ? null : nodeId;
    },

    isExpanded(nodeId) {
        return this.expandedNode === nodeId;
    },

    getNodeSlices(nodeId, slices) {
        if (!slices) return [];
        return slices.filter(function(s) {
            return (s.instances || []).some(function(i) { return i.nodeId === nodeId; });
        }).map(function(s) { return s.artifact; });
    }
};
