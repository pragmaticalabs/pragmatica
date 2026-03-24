document.addEventListener('alpine:init', function() {
    Alpine.store('streams', {
        streams: [],

        updateFromWsDashboard(data) {
            if (data.streams && Array.isArray(data.streams)) {
                this.streams = data.streams;
            }
        },

        async refresh() {
            var data = await RestClient.get('/api/streams');
            if (data && data.streams) {
                this.streams = data.streams;
            }
        },

        totalEvents() {
            return this.streams.reduce(function(sum, s) { return sum + (s.totalEvents || 0); }, 0);
        },

        totalBytes() {
            return this.streams.reduce(function(sum, s) { return sum + (s.totalBytes || 0); }, 0);
        }
    });
});
