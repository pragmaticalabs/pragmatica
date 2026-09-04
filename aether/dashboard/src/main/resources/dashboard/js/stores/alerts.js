document.addEventListener('alpine:init', function() {
    Alpine.store('alerts', {
        active: [],
        history: [],
        thresholds: [],

        // #292: the WS envelope discriminator lives at the TOP level only (`{"type":"ALERT","data":{...}}`,
        // AlertManager.buildAlertMessage) — never duplicated inside `data`. This handler is passed the raw,
        // still-wrapped envelope (see app.js onWsMessage) and reads `type` there, not on the unwrapped payload.
        updateFromWs(envelope) {
            var payload = envelope.data || envelope;
            if (envelope.type === 'ALERT') {
                this.active.push(payload);
            } else if (envelope.type === 'ALERT_RESOLVED') {
                var metric = payload.metric;
                var nodeId = payload.nodeId;
                this.active = this.active.filter(function(a) {
                    return !(a.metric === metric && a.nodeId === nodeId);
                });
                this.history.unshift(payload);
            }
        },

        updateFromWsHistory(data) {
            if (data.alerts && Array.isArray(data.alerts)) {
                this.active = data.alerts.filter(function(a) { return !a.resolved; });
                this.history = data.alerts.filter(function(a) { return a.resolved; });
            }
        },

        async refresh() {
            var active = await RestClient.get('/api/alerts/active');
            if (active && active.alerts) this.active = active.alerts;
            else if (Array.isArray(active)) this.active = active;

            var history = await RestClient.get('/api/alerts/history');
            if (history && history.alerts) this.history = history.alerts;
            else if (Array.isArray(history)) this.history = history;
        },

        async refreshThresholds() {
            var data = await RestClient.get('/api/thresholds');
            if (data && Array.isArray(data)) {
                this.thresholds = data;
            } else if (data && typeof data === 'object') {
                var arr = [];
                Object.keys(data).forEach(function(metric) {
                    arr.push({ metric: metric, warning: data[metric].warning, critical: data[metric].critical });
                });
                this.thresholds = arr;
            }
        },

        async clearAll() {
            await RestClient.post('/api/alerts/clear');
            this.active = [];
        },

        acknowledge(alert) {
            this.active = this.active.filter(function(a) { return a !== alert; });
            this.history.unshift(Object.assign({}, alert, { resolvedAt: new Date().toISOString() }));
        },

        async saveThreshold(t) {
            await RestClient.post('/api/thresholds', { metric: t.metric, warning: t.warning, critical: t.critical });
        },

        async deleteThreshold(t) {
            await RestClient.del('/api/thresholds/' + encodeURIComponent(t.metric));
            this.thresholds = this.thresholds.filter(function(th) { return th.metric !== t.metric; });
        },

        addThreshold(t) {
            if (!t.metric) return;
            this.thresholds.push({ metric: t.metric, warning: t.warning || 0.7, critical: t.critical || 0.9 });
            this.saveThreshold(t);
        },

        updateFromInitialState(data) {
            if (data.thresholds) {
                if (Array.isArray(data.thresholds)) {
                    this.thresholds = data.thresholds;
                } else if (typeof data.thresholds === 'object') {
                    var arr = [];
                    Object.keys(data.thresholds).forEach(function(metric) {
                        arr.push({ metric: metric, warning: data.thresholds[metric].warning, critical: data.thresholds[metric].critical });
                    });
                    this.thresholds = arr;
                }
            }
        }
    });
});
