document.addEventListener('alpine:init', function() {
    Alpine.store('events', {
        recent: [],
        maxEvents: 200,
        severityFilter: 'ALL',

        addEvents(events) {
            if (!Array.isArray(events)) return;
            var self = this;
            events.forEach(function(event) {
                var exists = self.recent.some(function(e) {
                    return e.timestamp === event.timestamp && e.type === event.type;
                });
                if (!exists) {
                    self.recent.unshift(event);
                }
            });
            if (self.recent.length > self.maxEvents) {
                self.recent = self.recent.slice(0, self.maxEvents);
            }
        },

        updateFromStatus(data) {
            // /api/status doesn't include events directly; poll /api/events
        },

        async refresh() {
            var data = await RestClient.get('/api/events');
            if (data && Array.isArray(data)) {
                this.addEvents(data);
            }
        },

        filtered() {
            if (this.severityFilter === 'ALL') return this.recent;
            var filter = this.severityFilter;
            return this.recent.filter(function(e) { return e.severity === filter; });
        }
    });
});
