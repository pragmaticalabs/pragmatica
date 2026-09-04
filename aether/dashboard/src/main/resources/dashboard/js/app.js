// #294/#300 (three-state health gate, corrected after #846 review): a pure decision function,
// kept separate from checkHealth()'s store-mutation and warn-once side effects, so the health
// classification is one small reviewable unit. Inputs are probeHealth() results ({reachable, json}
// for each of the versioned and bare paths); output is either `{unknown: true}` — deliberately
// OMITTING `degraded` so the caller has nothing to assign and a prior verdict survives untouched —
// or `{unknown: false, degraded: <bool>}` once at least one path proved the server is reachable.
//
// No JS test runner exists anywhere in this repo (confirmed: no GraalJS/Nashorn/javax.script
// dependency in any pom.xml or test source) and adding one is out of scope for this fix, so this
// stays pinned by structural Java contract-test assertions on the extracted body — the same honest,
// disclosed technique already used for the rest of this file's coverage — rather than by executing
// it. Extracting it as a standalone pure function is still worth doing on its own terms: it is
// reviewable in isolation, and it makes "unknown never overwrites degraded" a structural property
// of the object shape instead of something checkHealth() has to remember to preserve.
function decideHealthState(v1, bare) {
    var reachable = v1.reachable || bare.reachable;
    if (!reachable) {
        return { unknown: true };
    }
    var health = (v1.json && v1.json.status) ? v1 : bare;
    return {
        unknown: false,
        degraded: !!(health.json && health.json.status) && health.json.status !== 'healthy'
    };
}

document.addEventListener('alpine:init', function() {
    Alpine.data('app', function() {
        return {
            currentPage: 'overview',
            wsConnected: false,
            pollTimer: null,
            secondaryPollTimer: null,
            sparklines: {},
            charts: {},
            chartsInitialized: false,
            lastNodeCount: 0,
            lastPerNode: false,
            newThreshold: { metric: '', warning: 0.7, critical: 0.9 },

            init() {
                var self = this;

                // Gate all data fetching behind auth — no polls or WS until key is validated
                if (!window.AetherAuth || !window.AetherAuth.hasValidKey()) {
                    // Wait for auth to complete, then initialize
                    window.addEventListener('aether-auth-success', function() { self.startApp(); });
                    return;
                }
                this.startApp();
            },

            startApp() {
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

                // Initialize/reinitialize charts when metrics tab is shown.
                // Bug 1 fix: Always destroy and recreate charts when entering
                // the metrics tab. On page reload, old uPlot instances are
                // orphaned and DOM containers are recreated, so we must build
                // fresh chart instances every time the tab becomes visible.
                this.$watch('currentPage', function(page) {
                    if (page === 'metrics') {
                        self.$nextTick(function() {
                            self.destroyCharts();
                            self.initCharts();
                            self.chartsInitialized = true;
                            self.updateCharts();
                        });
                    }
                });

                // Start/stop requests store polling when entering/leaving Requests page
                this.$watch('currentPage', function(page) {
                    if (page === 'requests') {
                        Alpine.store('requests').startPolling();
                    } else {
                        Alpine.store('requests').stopPolling();
                    }
                });

                // Bug 2 fix: Watch per-node toggle and rebuild charts with
                // the appropriate series configuration.
                this.$watch('$store.metrics.perNode', function() {
                    if (self.currentPage === 'metrics' && self.chartsInitialized) {
                        self.$nextTick(function() {
                            self.destroyCharts();
                            self.initCharts();
                            self.updateCharts();
                        });
                    }
                });

                // Resize handler for charts (guarded against accumulation)
                if (!window._aetherResizeAttached) {
                    window._aetherResizeAttached = true;
                    window.addEventListener('resize', function() {
                        self.resizeCharts();
                    });
                }
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
                        Alpine.store('topology').updateFromWsDashboard(data.data);
                        Alpine.store('alerts').updateFromInitialState(data.data);
                        Alpine.store('schema').updateFromWsDashboard(data.data);
                        Alpine.store('governors').updateFromWsDashboard(data.data);
                        Alpine.store('strategies').updateFromWsDashboard(data.data);
                        Alpine.store('streams').updateFromWsDashboard(data.data);
                        // Issue 13: Seed initial history point so charts are not blank
                        Alpine.store('metrics').seedFromInitialState(data.data);
                    } else if (data.type === 'METRICS_UPDATE' && data.data) {
                        Alpine.store('cluster').updateFromWsDashboard(data.data);
                        Alpine.store('metrics').updateFromWsDashboard(data.data);
                        Alpine.store('deployments').updateFromWsDashboard(data.data);
                        Alpine.store('topology').updateFromWsDashboard(data.data);
                        Alpine.store('schema').updateFromWsDashboard(data.data);
                        Alpine.store('governors').updateFromWsDashboard(data.data);
                        Alpine.store('strategies').updateFromWsDashboard(data.data);
                        Alpine.store('streams').updateFromWsDashboard(data.data);
                        Alpine.store('metrics').updateNodeHistory(Alpine.store('cluster').nodes);
                        this.updateSparklines();
                        this.updateCharts();
                    } else if (data.type === 'ALERT' || data.type === 'ALERT_RESOLVED') {
                        // #292: the discriminator lives at the TOP level only (AlertManager.buildAlertMessage
                        // sends {"type":"ALERT","data":{...}}, never duplicated inside data) — pass the whole
                        // envelope through so the store can read `type` itself instead of an already-unwrapped
                        // payload that never carries it.
                        Alpine.store('alerts').updateFromWs(data);
                    } else if (data.type === 'HISTORY') {
                        Alpine.store('alerts').updateFromWsHistory(data.data || data);
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
                // Always poll REST for full state (loadTargets, slices, etc.)
                // WS provides incremental metrics but REST has the complete picture
                this.pollTimer = setInterval(function() {
                    var cluster = Alpine.store('cluster');
                    // #294 (three states): while the server is UNREACHABLE (network error on both
                    // health paths, not merely reachable-and-unhealthy), every timer — including the
                    // health probe itself — backs off to a shared slow 10s retry instead of hammering
                    // a dead backend at full 2s/3s cadence. `unknownRetryDue()` is a no-op false when
                    // healthUnknown is already false, so the known-reachable case (healthy OR
                    // degraded) is unaffected and still probes every tick to catch recovery fast.
                    if (cluster.healthUnknown && !cluster.unknownRetryDue()) return;
                    self.checkHealth();
                    if (cluster.degraded) return;
                    self.pollStatus();
                    Alpine.store('events').refresh();
                    Alpine.store('alerts').refresh();
                }, 2000);

                // Secondary stores: poll at lower frequency as fallback
                // for when WS misses INITIAL_STATE or drops connection
                this.secondaryPollTimer = setInterval(function() {
                    var cluster = Alpine.store('cluster');
                    if (cluster.degraded) return;
                    if (cluster.healthUnknown && !cluster.unknownRetryDue()) return;
                    Alpine.store('topology').refresh();
                    Alpine.store('desiredTopology').refresh();
                    Alpine.store('governors').refresh();
                    Alpine.store('strategies').refresh();
                    Alpine.store('streams').refresh();
                    Alpine.store('storage').refresh();
                    Alpine.store('observability').refresh();
                    Alpine.store('deployments').refreshSlices();
                    Alpine.store('deployments').refreshRoutes();
                    Alpine.store('schema').refresh();
                }, 10000);
            },

            async checkHealth() {
                // #294/#300 (three-state gate, corrected after #846 review): try the versioned path
                // FIRST — it is the ONLY health path the real node's Management API serves
                // (ManagementRoute has no bare '/health' route at all, per #300's path-versioning
                // gap) — then fall back to bare '/health', which is what Forge actually serves and
                // has never migrated. probeHealth() (not the shared RestClient.get()) is used here
                // because it alone distinguishes "reachable, no route" (any HTTP response, 404
                // included) from "unreachable" (a fetch-level network error) — a distinction
                // RestClient.get() collapses to an identical null for both, which is exactly the gap
                // the #846 review found: this method's PREVIOUS revision could not tell a real total
                // outage apart from a harmless 404 and fail-opened on both alike.
                //
                // decideHealthState() (module scope, top of this file) is the pure decision; this
                // method only applies it and owns the once-only warning side effect.
                var v1 = await RestClient.probeHealth('/api/v1/health');
                var bare = (v1.json && v1.json.status) ? { reachable: false, json: null } : await RestClient.probeHealth('/health');
                var decision = decideHealthState(v1, bare);
                var cluster = Alpine.store('cluster');

                cluster.healthUnknown = decision.unknown;
                if (decision.unknown) {
                    // Both paths were genuinely UNREACHABLE (refused/timeout), not merely 404 — the
                    // 404 case is folded into `degraded` below via decideHealthState's fail-open.
                    // `degraded` is deliberately left untouched: a prior known-degraded verdict must
                    // not read back as healthy just because the network to check it also died.
                    if (!this._healthProbeUnreachableWarned) {
                        this._healthProbeUnreachableWarned = true;
                        console.warn('[Dashboard] health probe unreachable on both /api/v1/health and /health; backing off to a slow 10s retry and holding the last known health state');
                    }
                    return;
                }
                this._healthProbeUnreachableWarned = false;
                // 'degraded' is keyed on the semantic `status !== 'healthy'`, not a literal 'degraded'
                // wire value — none exists. The real node's HealthResponse.status is only ever
                // "healthy"/"unhealthy"; Forge's is hardcoded to always "healthy" and can never signal
                // degradation, an honest limit on this gate against Forge. A path that answered but
                // has no health route here (404 on both) fails OPEN via decideHealthState — reachable
                // is not the same claim as degraded.
                cluster.degraded = decision.degraded;
            },

            async pollStatus() {
                var data = await RestClient.get('/api/nodes/status');
                if (data) {
                    Alpine.store('cluster').updateFromStatus(data);
                    Alpine.store('metrics').updateFromStatus(data);
                    Alpine.store('deployments').updateFromStatus(data);
                    Alpine.store('forge').updateFromStatus(data);
                    Alpine.store('metrics').updateNodeHistory(Alpine.store('cluster').nodes);
                    this.updateCharts();
                }
                // Fetch slice details for node→slice mapping (REST /api/nodes/status only has sliceCount)
                var slices = await RestClient.get('/api/slices');
                if (slices) {
                    Alpine.store('cluster').updateSlices(slices);
                }
            },

            async loadInitialData() {
                Alpine.store('cluster').refreshConfig();
                Alpine.store('cluster').refreshTtm();
                Alpine.store('cluster').refreshLogLevels();
                Alpine.store('observability').refresh();
                Alpine.store('alerts').refresh();
                Alpine.store('alerts').refreshThresholds();
                Alpine.store('topology').refresh();
                Alpine.store('schema').refresh();
                Alpine.store('governors').refresh();
                Alpine.store('strategies').refresh();
                Alpine.store('streams').refresh();
                Alpine.store('storage').refresh();
            },

            initSparklines() {
                // Issue 10: Destroy existing sparkline instances before reinit
                var self = this;
                Object.keys(this.sparklines).forEach(function(key) {
                    Sparkline.destroy(self.sparklines[key]);
                });
                this.sparklines = {};
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

            destroyCharts() {
                var self = this;
                Object.keys(this.charts).forEach(function(key) {
                    TimeSeries.destroy(self.charts[key]);
                });
                this.charts = {};
                // Clear container DOM so uPlot can create fresh canvases
                var refs = this.$refs;
                ['chartRps', 'chartLatency', 'chartSuccess', 'chartCpu', 'chartHeap'].forEach(function(name) {
                    if (refs[name]) refs[name].innerHTML = '';
                });
            },

            initCharts() {
                var refs = this.$refs;
                var nodes = Alpine.store('cluster').nodes;
                var nodeNames = nodes.map(function(n) { return n.nodeId; });
                var perNode = Alpine.store('metrics').perNode;
                this.lastNodeCount = nodeNames.length;
                this.lastPerNode = perNode;

                if (refs.chartRps) {
                    // Bug 2 fix: In per-node mode, create one series per node for RPS
                    var rpsSeries = perNode && nodeNames.length > 0 ? nodeNames : ['RPS'];
                    this.charts.rps = TimeSeries.create(refs.chartRps, { series: rpsSeries, height: 200, fill: !perNode, yLabel: 'req/s' });
                }
                if (refs.chartLatency) {
                    var latSeries = perNode && nodeNames.length > 0 ? nodeNames : ['P50', 'P95', 'P99'];
                    this.charts.latency = TimeSeries.create(refs.chartLatency, { series: latSeries, height: 180, yLabel: 'ms' });
                }
                if (refs.chartSuccess) {
                    var sucSeries = perNode && nodeNames.length > 0 ? nodeNames : ['Success Rate'];
                    this.charts.success = TimeSeries.create(refs.chartSuccess, { series: sucSeries, height: 180, fill: !perNode, yLabel: '%', yRange: [0, 105] });
                }
                if (refs.chartCpu && nodeNames.length > 0) {
                    this.charts.cpu = TimeSeries.create(refs.chartCpu, { series: nodeNames, height: 180, yLabel: '%' });
                }
                if (refs.chartHeap && nodeNames.length > 0) {
                    this.charts.heap = TimeSeries.create(refs.chartHeap, { series: nodeNames, height: 180, yLabel: 'MB' });
                }
            },

            updateCharts() {
                if (!this.chartsInitialized) return;

                var m = Alpine.store('metrics');
                var h = m.history;
                if (h.timestamps.length < 2) return;

                var nodes = Alpine.store('cluster').nodes;
                var perNode = m.perNode;

                // Bug 1 fix: If node count changed since charts were created,
                // rebuild charts so CPU/heap (and per-node) series match.
                if (this.currentPage === 'metrics' && (nodes.length !== this.lastNodeCount || perNode !== this.lastPerNode)) {
                    var self = this;
                    this.$nextTick(function() {
                        self.destroyCharts();
                        self.initCharts();
                        self.updateChartsData(nodes, h, perNode);
                    });
                    return;
                }

                this.updateChartsData(nodes, h, perNode);
            },

            updateChartsData(nodes, h, perNode) {
                if (this.charts.rps) {
                    if (perNode && nodes.length > 0) {
                        // Bug 2 fix: Show per-node RPS series
                        var rpsPerNode = nodes.map(function(n) { return h.nodeRps[n.nodeId] || []; });
                        TimeSeries.updateData(this.charts.rps, h.timestamps, rpsPerNode);
                    } else {
                        TimeSeries.updateData(this.charts.rps, h.timestamps, [h.rps]);
                    }
                }
                if (this.charts.latency) {
                    if (perNode && nodes.length > 0) {
                        var latPerNode = nodes.map(function(n) { return h.nodeLatency[n.nodeId] || []; });
                        TimeSeries.updateData(this.charts.latency, h.timestamps, latPerNode);
                    } else {
                        TimeSeries.updateData(this.charts.latency, h.timestamps, [h.p50, h.p95, h.p99]);
                    }
                }
                if (this.charts.success) {
                    if (perNode && nodes.length > 0) {
                        var sucPerNode = nodes.map(function(n) {
                            return (h.nodeSuccessRate[n.nodeId] || []).map(function(r) { return r * 100; });
                        });
                        TimeSeries.updateData(this.charts.success, h.timestamps, sucPerNode);
                    } else {
                        TimeSeries.updateData(this.charts.success, h.timestamps, [h.successRate.map(function(r) { return r * 100; })]);
                    }
                }
                if (this.charts.cpu) {
                    var cpuSeries = nodes.map(function(n) {
                        return (h.cpu[n.nodeId] || []).map(function(v) { return Math.round(v * 100); });
                    });
                    if (cpuSeries.length > 0) TimeSeries.updateData(this.charts.cpu, h.timestamps, cpuSeries);
                }
                if (this.charts.heap) {
                    var heapSeries = nodes.map(function(n) { return h.heap[n.nodeId] || []; });
                    if (heapSeries.length > 0) TimeSeries.updateData(this.charts.heap, h.timestamps, heapSeries);
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
            formatBytes(b) { return Formatters.bytes(b); },

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

            // No-op: invocation methods moved to $store.requests
            noop() {}
        };
    });
});
