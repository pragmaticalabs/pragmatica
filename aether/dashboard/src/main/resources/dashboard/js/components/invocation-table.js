window.InvocationTable = {
    sortField: 'count',
    sortDir: -1, // descending
    expandedRow: null,

    sort(invocations, field, dir) {
        var d = dir || this.sortDir;
        return invocations.slice().sort(function(a, b) {
            var va = a[field] || 0;
            var vb = b[field] || 0;
            if (typeof va === 'string') return d * va.localeCompare(vb);
            return d * (va - vb);
        });
    },

    toggleSort(field) {
        if (this.sortField === field) {
            this.sortDir = -this.sortDir;
        } else {
            this.sortField = field;
            this.sortDir = -1;
        }
    },

    filter(invocations, filters) {
        return invocations.filter(function(inv) {
            if (filters.artifact && inv.artifact.toLowerCase().indexOf(filters.artifact.toLowerCase()) === -1) return false;
            if (filters.method && inv.method.toLowerCase().indexOf(filters.method.toLowerCase()) === -1) return false;
            if (filters.status === 'success' && inv.errorRate > 0.01) return false;
            if (filters.status === 'failure' && inv.errorRate < 0.01) return false;
            return true;
        });
    },

    toggleExpand(inv) {
        var key = inv.artifact + ':' + inv.method;
        this.expandedRow = this.expandedRow === key ? null : key;
    },

    isExpanded(inv) {
        return this.expandedRow === inv.artifact + ':' + inv.method;
    }
};
