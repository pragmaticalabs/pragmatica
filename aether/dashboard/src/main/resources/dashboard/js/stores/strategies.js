document.addEventListener('alpine:init', function() {
    Alpine.store('strategies', {
        canaries: [],
        blueGreen: [],
        abTests: [],

        updateFromWsDashboard(data) {
            if (data.strategies) {
                if (Array.isArray(data.strategies.canaries)) {
                    this.canaries = data.strategies.canaries;
                }
                if (Array.isArray(data.strategies.blueGreen)) {
                    this.blueGreen = data.strategies.blueGreen;
                }
                if (Array.isArray(data.strategies.abTests)) {
                    this.abTests = data.strategies.abTests;
                }
            }
        },

        async refresh() {
            var canaryData = await RestClient.get('/api/canaries');
            if (canaryData && canaryData.canaries) {
                this.canaries = canaryData.canaries;
            }
            var bgData = await RestClient.get('/api/blue-green-deployments');
            if (bgData && bgData.deployments) {
                this.blueGreen = bgData.deployments;
            }
            var abData = await RestClient.get('/api/ab-tests');
            if (abData && abData.tests) {
                this.abTests = abData.tests;
            }
        },

        totalActive() {
            return this.canaries.length + this.blueGreen.length + this.abTests.length;
        },

        stateClass(state) {
            switch ((state || '').toUpperCase()) {
                case 'ACTIVE':
                case 'RUNNING':
                case 'GREEN_ACTIVE': return 'active';
                case 'COMPLETED':
                case 'CONCLUDED': return 'completed';
                case 'ROLLED_BACK':
                case 'FAILED': return 'failed';
                default: return 'pending';
            }
        }
    });
});
