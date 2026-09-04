document.addEventListener('alpine:init', function() {
    Alpine.store('events', {
        recent: [],
        maxEvents: 200,
        severityFilter: 'ALL',

        // #304: node-mode ClusterEventView carries its HLC time under `at` (packed physical-ms/counter,
        // HlcTimestamp.pack), never `timestamp` — that field belongs to Forge-mode ForgeEvent only.
        // These helpers read whichever the payload actually carries instead of assuming `timestamp`.
        eventMillis(event) {
            if (event.at && typeof event.at.packed === 'number') {
                return Math.floor(event.at.packed / 65536);
            }
            // Neither `at` nor `timestamp` present collapses eventKey() to a constant "0:TYPE" for
            // every event of that type — an honest limit, not a bug: no known payload shape reaches
            // this branch today, so it has never needed a real fallback.
            return event.timestamp || 0;
        },

        eventKey(event) {
            return this.eventMillis(event) + ':' + event.type;
        },

        addEvents(events) {
            if (!Array.isArray(events)) return;
            var self = this;
            events.forEach(function(event) {
                var key = self.eventKey(event);
                var exists = self.recent.some(function(e) { return self.eventKey(e) === key; });
                if (!exists) {
                    self.recent.unshift(event);
                }
            });
            if (self.recent.length > self.maxEvents) {
                self.recent = self.recent.slice(0, self.maxEvents);
            }
        },

        updateFromStatus(data) {
            // /api/nodes/status doesn't include events directly; poll /api/events
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
