document.addEventListener('alpine:init', function() {
    Alpine.store('schema', {
        datasources: [],

        // Mirrors ClusterDeploymentState's blocking-status set (#542): a record in one of these
        // states withholds activation of its owning blueprint's slices. COMPLETED is the only
        // status that releases.
        blockingStatuses: ['PENDING', 'MIGRATING', 'FAILED'],

        updateFromWsDashboard(data) {
            if (data.schema && Array.isArray(data.schema.datasources)) {
                this.datasources = data.schema.datasources;
            }
        },

        async refresh() {
            var data = await RestClient.get('/api/schema/status');
            if (data && data.datasources) {
                this.datasources = data.datasources.map(this.fromRestShape, this);
            }
        },

        // The WebSocket frame and `GET /api/schema/status` describe the same KV record under
        // different field names (`name` vs `datasource`), and the REST shape carries no
        // `blocksActivation` flag. Normalize REST rows into the WS shape so the panel renders
        // identically whichever source last populated it — without this, the post-retry refresh
        // blanked every row's name (and its x-for key) and dropped the hold indicator.
        fromRestShape(ds) {
            return {
                name: ds.datasource,
                status: ds.status,
                currentVersion: ds.currentVersion,
                lastMigration: ds.lastMigration,
                owningBlueprint: ds.owningBlueprint,
                blocksActivation: this.blockingStatuses.indexOf((ds.status || '').toUpperCase()) >= 0
            };
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
