document.addEventListener('alpine:init', function() {
    // Desired cluster shape, per source and per role (RFC-0017 C1).
    //
    // Distinct from the `topology` store, which reports OBSERVED nodes. This one reports what the
    // cluster was ASKED for. The gap between the two is what an operator acts on, and it is not
    // visible anywhere else: `coreCount` alone cannot say which source is short.
    //
    // Degenerate values are shown as they are. A single-source cluster genuinely has one row; that
    // is a true reading, not a placeholder.
    Alpine.store('desiredTopology', {
        entries: [],
        coreCount: 0,
        coreMin: 0,
        coreMax: 0,
        configVersion: 0,
        loaded: false,

        async refresh() {
            var data = await RestClient.get('/api/cluster/config');

            if (!data) {
                return;
            }

            this.entries = Array.isArray(data.desiredTopology) ? data.desiredTopology : [];
            this.coreCount = data.coreCount || 0;
            this.coreMin = data.coreMin || 0;
            this.coreMax = data.coreMax || 0;
            this.configVersion = data.configVersion || 0;
            this.loaded = true;
        },

        // Sources carrying at least one core entry. Two or more is what makes a bare
        // `aether cluster scale --role core` ambiguous, so the panel flags it.
        coreSources() {
            var seen = [];

            this.entries.forEach(function(entry) {
                if (entry.role && entry.role.toLowerCase() === 'core' && seen.indexOf(entry.sourceName) < 0) {
                    seen.push(entry.sourceName);
                }
            });

            return seen;
        },

        scaleNeedsExplicitSource() {
            return this.coreSources().length > 1;
        },

        sourceLabel(entry) {
            return entry.sourceName && entry.sourceName.length > 0 ? entry.sourceName : '(unnamed)';
        }
    });
});
