document.addEventListener('alpine:init', function() {
    Alpine.store('storage', {
        instances: [],
        clusterInstances: [],
        selectedInstance: null,
        viewMode: 'local',

        async refresh() {
            var data = await RestClient.get('/api/storage');
            if (data && data.instances) {
                this.instances = data.instances;
            }
            var clusterData = await RestClient.get('/api/cluster/storage');
            if (clusterData && clusterData.instances) {
                this.clusterInstances = clusterData.instances;
            }
        },

        async refreshDetail(name) {
            var data = await RestClient.get('/api/storage/' + encodeURIComponent(name));
            if (data) {
                this.selectedInstance = data;
            }
        },

        async forceSnapshot(name) {
            var data = await RestClient.post('/api/storage/' + encodeURIComponent(name) + '/snapshot', {});
            if (data) {
                Notifications.show('Snapshot triggered for ' + name, 'success');
                this.refresh();
            }
        },

        totalInstances() {
            return this.viewMode === 'cluster'
                ? this.clusterInstances.length
                : this.instances.length;
        },

        displayInstances() {
            return this.viewMode === 'cluster'
                ? this.clusterInstances
                : this.instances;
        },

        readinessClass(readiness) {
            if (!readiness) return 'unknown';
            if (readiness.isWriteReady && readiness.isReadReady) return 'active';
            if (readiness.isReadReady) return 'pending';
            return 'failed';
        },

        readinessLabel(readiness) {
            if (!readiness) return 'UNKNOWN';
            if (readiness.isWriteReady && readiness.isReadReady) return 'READY';
            if (readiness.isReadReady) return 'READ_ONLY';
            return readiness.state || 'NOT_READY';
        },

        tierUtilization(tier) {
            if (!tier || !tier.maxBytes || tier.maxBytes === 0) return '0%';
            return (tier.utilizationPct || 0).toFixed(1) + '%';
        }
    });
});
