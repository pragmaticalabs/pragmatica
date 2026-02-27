document.addEventListener('alpine:init', function() {
    Alpine.data('app', function() {
        return {
            currentPage: 'overview',
            wsConnected: false,
            pollTimer: null,
            sparklines: {},
            charts: {},
            requestFilters: { artifact: '', method: '', status: '' },
            invocationSort: { field: 'count', dir: -1 },
            newThreshold: { metric: '', warning: 0.7, critical: 0.9 },

            init() {
                var self = this;

                // Detect Forge mode
                Alpine.store('forge').detectForge();

                // Initialize WebSocket
                WsManager.init(
                    function(name, data) { self.onWsMessage(name, data); },
                    function(connected) { self.wsConnected = connected; }
                );

                // Start REST polling as fallback/supplement
                this.startPolling();

                // Load initial config data
                this.loadInitialData();

                // Initialize sparklines after DOM ready
                this.$nextTick(function() {
                    self.initSparklines();
                });

                // Resize handler for charts
                window.addEventListener('resize', function() {
                    self.resizeCharts();
                });
            },

            onWsMessage(name, data) {
                if (name === 'status') {
                    Alpine.store('cluster').updateFromStatus(data);
                    Alpine.store('metrics').updateFromStatus(data);
                    Alpine.store('deployments').updateFromStatus(data);
                    Alpine.store('forge').updateFromStatus(data);
                }
                if (name === 'dashboard') {
                    if (data.type === 'INITIAL_STATE' && data.data) {
                        Alpine.store('cluster').updateFromStatus(data.data);
                        Alpine.store('deployments').updateFromWsDashboard(data.data);
                        Alpine.store('alerts').updateFromInitialState(data.data);
                    } else if (data.type === 'METRICS_UPDATE' && data.data) {
                        Alpine.store('cluster').updateFromWsDashboard(data.data);
                        Alpine.store('metrics').updateFromWsDashboard(data.data);
                        Alpine.store('deployments').updateFromWsDashboard(data.data);
                        Alpine.store('metrics').updateNodeHistory(Alpine.store('cluster').nodes);
                        this.updateSparklines();
                        this.updateCharts();
                    } else if (data.type === 'ALERT' || data.type === 'ALERT_RESOLVED') {
                        Alpine.store('alerts').updateFromWs(data);
                    }
                }
                if (name === 'events') {
                    if (Array.isArray(data)) {
                        Alpine.store('events').addEvents(data);
                    }
                }
            },

            startPolling() {
                var self = this;
                // Poll every 2 seconds as WS supplement
                this.pollTimer = setInterval(function() {
                    if (!self.wsConnected) {
                        self.pollStatus();
                    }
                    // Events always via REST (WS may not be available)
                    Alpine.store('events').refresh();
                }, 2000);
            },

            async pollStatus() {
                var data = await RestClient.get('/api/status');
                if (data) {
                    Alpine.store('cluster').updateFromStatus(data);
                    Alpine.store('metrics').updateFromStatus(data);
                    Alpine.store('deployments').updateFromStatus(data);
                    Alpine.store('forge').updateFromStatus(data);
                }
            },

            async loadInitialData() {
                Alpine.store('cluster').refreshConfig();
                Alpine.store('cluster').refreshTtm();
                Alpine.store('cluster').refreshLogLevels();
                Alpine.store('cluster').refreshObservabilityDepth();
                Alpine.store('alerts').refresh();
                Alpine.store('alerts').refreshThresholds();
            },

            initSparklines() {
                var refs = this.$refs;
                if (refs.sparkRps) this.sparklines.rps = Sparkline.create(refs.sparkRps, 'rgba(88,166,255,0.8)');
                if (refs.sparkP50) this.sparklines.p50 = Sparkline.create(refs.sparkP50, 'rgba(63,185,80,0.8)');
                if (refs.sparkP99) this.sparklines.p99 = Sparkline.create(refs.sparkP99, 'rgba(210,153,34,0.8)');
                if (refs.sparkSuccess) this.sparklines.success = Sparkline.create(refs.sparkSuccess, 'rgba(63,185,80,0.8)');
                if (refs.sparkNodes) this.sparklines.nodes = Sparkline.create(refs.sparkNodes, 'rgba(188,140,255,0.8)');
            },

            updateSparklines() {
                var m = Alpine.store('metrics');
                var c = Alpine.store('cluster');
                if (this.sparklines.rps) Sparkline.update(this.sparklines.rps, m.rps);
                if (this.sparklines.p50) Sparkline.update(this.sparklines.p50, m.p50);
                if (this.sparklines.p99) Sparkline.update(this.sparklines.p99, m.p99);
                if (this.sparklines.success) Sparkline.update(this.sparklines.success, m.successRate);
                if (this.sparklines.nodes) Sparkline.update(this.sparklines.nodes, c.nodes.length);
            },

            initCharts() {
                var refs = this.$refs;
                if (refs.chartRps) {
                    this.charts.rps = TimeSeries.create(refs.chartRps, { series: ['RPS'], height: 200, fill: true, yLabel: 'req/s' });
                }
                if (refs.chartLatency) {
                    this.charts.latency = TimeSeries.create(refs.chartLatency, { series: ['P50', 'P95', 'P99'], height: 180, yLabel: 'ms' });
                }
                if (refs.chartSuccess) {
                    this.charts.success = TimeSeries.create(refs.chartSuccess, { series: ['Success Rate'], height: 180, fill: true, yLabel: '%' });
                }
            },

            updateCharts() {
                var h = Alpine.store('metrics').history;
                if (h.timestamps.length < 2) return;

                if (this.charts.rps) {
                    TimeSeries.updateData(this.charts.rps, h.timestamps, [h.rps]);
                }
                if (this.charts.latency) {
                    TimeSeries.updateData(this.charts.latency, h.timestamps, [h.p50, h.p95, h.p99]);
                }
                if (this.charts.success) {
                    TimeSeries.updateData(this.charts.success, h.timestamps, [h.successRate.map(function(r) { return r * 100; })]);
                }
            },

            resizeCharts() {
                var refs = this.$refs;
                Object.keys(this.charts).forEach(function(key) {
                    var ref = refs['chart' + key.charAt(0).toUpperCase() + key.slice(1)];
                    if (ref) TimeSeries.resize(this.charts[key], ref);
                }.bind(this));
            },

            // Navigation helpers
            navigateToNode(nodeId) {
                this.currentPage = 'metrics';
                NodeDetail.toggle(nodeId);
            },

            navigateToSlice(artifact) {
                this.currentPage = 'deployments';
            },

            // Helper methods bound to Alpine context
            formatUptime(s) { return Formatters.uptime(s); },
            formatNumber(n) { return Formatters.number(n); },
            formatLatency(ms) { return Formatters.latency(ms); },
            formatPercent(r) { return Formatters.percent(r); },
            formatTime(t) { return Formatters.time(t); },

            avgCpu() {
                var nodes = Alpine.store('cluster').nodes;
                if (nodes.length === 0) return '0%';
                var sum = nodes.reduce(function(a, n) { return a + (n.cpuUsage || 0); }, 0);
                return Math.round(sum / nodes.length * 100) + '%';
            },

            totalHeap() {
                var nodes = Alpine.store('cluster').nodes;
                var used = nodes.reduce(function(a, n) { return a + (n.heapUsedMb || 0); }, 0);
                var max = nodes.reduce(function(a, n) { return a + (n.heapMaxMb || 0); }, 0);
                return used + '/' + max + 'MB';
            },

            shortSliceName(artifact) {
                if (!artifact) return '';
                var parts = artifact.split(':');
                return parts.length >= 2 ? parts[parts.length - 2] + ':' + parts[parts.length - 1] : artifact;
            },

            sliceSummary(slice) {
                if (!slice.instances || slice.instances.length === 0) return '';
                var active = slice.instances.filter(function(i) { return i.state === 'ACTIVE'; }).length;
                var total = slice.instances.length;
                if (active === total) return active + '/' + total + ' ACTIVE';
                var loading = total - active;
                return active + '/' + total + ' (' + loading + ' LOADING)';
            },

            filteredInvocations() {
                var invs = Alpine.store('metrics').entryPoints;
                invs = InvocationTable.filter(invs, this.requestFilters);
                invs = InvocationTable.sort(invs, this.invocationSort.field, this.invocationSort.dir);
                return invs;
            },

            sortInvocations(field) {
                InvocationTable.toggleSort(field);
                this.invocationSort.field = InvocationTable.sortField;
                this.invocationSort.dir = InvocationTable.sortDir;
            },

            toggleInvocationDetail(inv) {
                InvocationTable.toggleExpand(inv);
            }
        };
    });
});
