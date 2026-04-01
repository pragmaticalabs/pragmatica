document.addEventListener('alpine:init', function() {
    Alpine.store('schema', {
        datasources: [],

        updateFromWsDashboard(data) {
            if (data.schema && Array.isArray(data.schema.datasources)) {
                this.datasources = data.schema.datasources;
            }
        },

        async refresh() {
            var data = await RestClient.get('/api/schema/status');
            if (data && data.datasources) {
                this.datasources = data.datasources;
            }
        },

        statusClass(status) {
            switch ((status || '').toUpperCase()) {
                case 'COMPLETED': return 'completed';
                case 'PENDING': return 'pending';
                case 'MIGRATING': return 'migrating';
                case 'FAILED': return 'failed';
                default: return 'unknown';
            }
        },

        async retrySchema(datasource) {
            var data = await RestClient.post('/api/schema/retry/' + datasource, {});
            if (data) {
                Notifications.show('Retry triggered for ' + datasource, 'success');
                await this.refresh();
            }
        }
    });
});
