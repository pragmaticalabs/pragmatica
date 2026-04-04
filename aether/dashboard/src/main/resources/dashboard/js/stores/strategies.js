document.addEventListener('alpine:init', function() {
    Alpine.store('strategies', {
        canaries: [],
        blueGreen: [],
        abTests: [],

        _splitDeployments(deployments) {
            if (!Array.isArray(deployments)) return;
            this.canaries = deployments.filter(function(d) { return d.strategy === 'CANARY'; });
            this.blueGreen = deployments.filter(function(d) { return d.strategy === 'BLUE_GREEN'; });
        },

        updateFromWsDashboard(data) {
            if (data.strategies) {
                if (Array.isArray(data.strategies.deployments)) {
                    this._splitDeployments(data.strategies.deployments);
                }
                if (Array.isArray(data.strategies.abTests)) {
                    this.abTests = data.strategies.abTests;
                }
            }
        },

        async refresh() {
            var deployData = await RestClient.get('/api/deploy');
            if (deployData && deployData.deployments) {
                this._splitDeployments(deployData.deployments);
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
