document.addEventListener('alpine:init', function() {
    Alpine.store('alerts', {
        active: [],
        history: [],
        thresholds: [],

        updateFromWs(data) {
            if (data.type === 'ALERT') {
                this.active.push(data);
            } else if (data.type === 'ALERT_RESOLVED') {
                var metric = data.metric;
                var nodeId = data.nodeId;
                this.active = this.active.filter(function(a) {
                    return !(a.metric === metric && a.nodeId === nodeId);
                });
                this.history.unshift(data);
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
