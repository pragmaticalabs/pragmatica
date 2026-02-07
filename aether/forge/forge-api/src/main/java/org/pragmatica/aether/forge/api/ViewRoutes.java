package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.aether.forge.ForgeMetrics;
import org.pragmatica.aether.forge.api.ForgeApiResponses.ForgeEvent;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.routing.CommonContentTypes;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Deque;

import static org.pragmatica.http.routing.Route.in;

/**
 * HTML fragment endpoints for HTMX-driven dashboard.
 * Each endpoint returns server-rendered HTML that HTMX swaps into the page.
 */
public sealed interface ViewRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    static RouteSource viewRoutes(ForgeCluster cluster,
                                   ForgeMetrics metrics,
                                   ConfigurableLoadRunner loadRunner,
                                   Deque<ForgeEvent> events,
                                   long startTime,
                                   Option<Path> loadConfigPath) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return in("/api/view")
        .serve(headerRoute(cluster, startTime),
               overviewRoute(),
               nodesRoute(cluster),
               slicesRoute(cluster),
               metricsRoute(cluster, http),
               loadTabRoute(loadConfigPath),
               loadStatusRoute(loadRunner),
               alertsTabRoute(),
               activeAlertsRoute(cluster, http),
               alertHistoryRoute(cluster, http));
    }

    // ========== Header Stats ==========
    private static Route<String> headerRoute(ForgeCluster cluster, long startTime) {
        return Route.<String> get("/header")
                    .to(_ -> Promise.success(renderHeader(cluster, startTime)))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderHeader(ForgeCluster cluster, long startTime) {
        var uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;
        var mins = uptimeSeconds / 60;
        var secs = uptimeSeconds % 60;
        var uptime = mins + ":" + String.format("%02d", secs);
        var nodeCount = cluster.nodeCount();
        var sliceCount = cluster.slicesStatus().stream()
                                .filter(s -> "ACTIVE".equals(s.state()))
                                .count();
        return """
            <span class="stat"><span class="stat-label">Uptime:</span><span class="stat-value">%s</span></span>
            <span class="stat"><span class="stat-label">Nodes:</span><span class="stat-value">%d</span></span>
            <span class="stat"><span class="stat-label">Slices:</span><span class="stat-value">%d</span></span>
            """.formatted(uptime, nodeCount, sliceCount);
    }

    // ========== Overview Tab Shell ==========
    private static Route<String> overviewRoute() {
        return Route.<String> get("/overview")
                    .to(_ -> Promise.success(renderOverviewShell()))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderOverviewShell() {
        return """
            <div class="overview-grid">
                <div class="left-column">
                    <div class="panel panel-grow">
                        <h2>Cluster Nodes</h2>
                        <div id="nodes-list" class="nodes-list"
                             hx-get="/api/view/overview/nodes"
                             hx-trigger="load, every 1s"
                             hx-swap="innerHTML">
                            <div class="node-item placeholder">Loading...</div>
                        </div>
                    </div>
                </div>
                <div class="right-column">
                    <div class="panel">
                        <h2>Slices Status</h2>
                        <div id="slices-status" class="slices-content"
                             hx-get="/api/view/overview/slices"
                             hx-trigger="load, every 3s"
                             hx-swap="innerHTML">
                            <div class="placeholder">Loading...</div>
                        </div>
                    </div>
                    <div class="panel">
                        <h2>Metrics</h2>
                        <div id="comprehensive-metrics" class="config-content"
                             hx-get="/api/view/overview/metrics"
                             hx-trigger="load, every 3s"
                             hx-swap="innerHTML">
                            <div class="placeholder">Loading...</div>
                        </div>
                    </div>
                </div>
            </div>
            <div class="panel panel-full-width panel-performance">
                <h2>Performance</h2>
                <div class="metrics-row">
                    <div class="metric-card">
                        <div class="metric-value" id="requests-per-sec">0</div>
                        <div class="metric-label">req/s</div>
                    </div>
                    <div class="metric-card success">
                        <div class="metric-value" id="success-rate">100%</div>
                        <div class="metric-label">success</div>
                    </div>
                    <div class="metric-card">
                        <div class="metric-value" id="avg-latency">0ms</div>
                        <div class="metric-label">latency</div>
                    </div>
                </div>
                <div class="chart-row">
                    <div class="chart-container"><canvas id="success-chart"></canvas></div>
                    <div class="chart-container"><canvas id="throughput-chart"></canvas></div>
                </div>
            </div>
            <div id="chaos-panel"
                 hx-get="/api/panel/chaos"
                 hx-trigger="load"
                 hx-swap="innerHTML">
            </div>
            """;
    }

    // ========== Cluster Nodes Fragment ==========
    private static Route<String> nodesRoute(ForgeCluster cluster) {
        return Route.<String> get("/overview/nodes")
                    .to(_ -> Promise.success(renderNodes(cluster)))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderNodes(ForgeCluster cluster) {
        var status = cluster.status();
        var nodeMetrics = cluster.nodeMetrics();
        var slicesStatus = cluster.slicesStatus();
        if (status.nodes().isEmpty()) {
            return "<div class=\"node-item placeholder\">No nodes available</div>";
        }
        var sb = new StringBuilder();
        var sortedNodes = status.nodes().stream()
                                .sorted((a, b) -> a.isLeader() ? -1 : b.isLeader() ? 1 : a.id().compareTo(b.id()))
                                .toList();
        for (var node : sortedNodes) {
            var metrics = nodeMetrics.stream()
                                     .filter(m -> m.nodeId().equals(node.id()))
                                     .findFirst();
            var cpu = metrics.map(m -> String.valueOf((int) (m.cpuUsage() * 100))).orElse("?");
            var heap = metrics.map(m -> m.heapUsedMb() + "/" + m.heapMaxMb()).orElse("?/?");
            var leaderClass = node.isLeader() ? " leader" : "";
            sb.append("<div class=\"node-item").append(leaderClass).append("\">");
            sb.append("<span class=\"node-id\">").append(escapeHtml(node.id())).append("</span>");
            if (node.isLeader()) {
                sb.append("<span class=\"leader-badge\">LEADER</span>");
            }
            sb.append("<span class=\"node-stats\"><span>CPU ").append(cpu).append("%</span>");
            sb.append("<span>Heap ").append(heap).append("MB</span></span>");
            // Render slice tags for this node
            sb.append("<span class=\"node-slices\">");
            var hasSlices = false;
            for (var slice : slicesStatus) {
                for (var inst : slice.instances()) {
                    if (inst.nodeId().equals(node.id())) {
                        var artifactParts = slice.artifact().split(":");
                        var shortName = artifactParts.length >= 2 ? artifactParts[1] : slice.artifact();
                        var stateClass = "ACTIVE".equals(inst.state()) ? "active"
                                         : "LOADING".equals(inst.state()) ? "loading" : "inactive";
                        sb.append("<span class=\"slice-tag ").append(stateClass)
                          .append("\" title=\"").append(escapeHtml(inst.state())).append("\">")
                          .append(escapeHtml(shortName)).append("</span>");
                        hasSlices = true;
                    }
                }
            }
            if (!hasSlices) {
                sb.append("<span class=\"no-slices\">No slices</span>");
            }
            sb.append("</span></div>");
        }
        return sb.toString();
    }

    // ========== Slices Status Fragment ==========
    private static Route<String> slicesRoute(ForgeCluster cluster) {
        return Route.<String> get("/overview/slices")
                    .to(_ -> Promise.success(renderSlices(cluster)))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderSlices(ForgeCluster cluster) {
        var slices = cluster.slicesStatus();
        if (slices.isEmpty()) {
            return "<div class=\"placeholder\">No slices deployed</div>";
        }
        var sb = new StringBuilder();
        for (var slice : slices) {
            var stateClass = "ACTIVE".equals(slice.state()) ? ""
                             : "LOADING".equals(slice.state()) || "ACTIVATING".equals(slice.state()) ? " loading" : " failed";
            sb.append("<div class=\"slice-item").append(stateClass).append("\">");
            sb.append("<div class=\"slice-header\">");
            sb.append("<span class=\"slice-artifact\">").append(escapeHtml(slice.artifact())).append("</span>");
            sb.append("<span class=\"slice-state ").append(slice.state()).append("\">")
              .append(slice.state()).append("</span>");
            sb.append("</div>");
            sb.append("<div class=\"slice-instances\">");
            if (slice.instances().isEmpty()) {
                sb.append("<span class=\"no-slices\">No instances</span>");
            } else {
                for (var inst : slice.instances()) {
                    var healthClass = "ACTIVE".equals(inst.state()) ? " healthy" : "";
                    sb.append("<span class=\"instance-badge").append(healthClass).append("\">")
                      .append(escapeHtml(inst.nodeId())).append(": ").append(inst.state())
                      .append("</span>");
                }
            }
            sb.append("</div></div>");
        }
        return sb.toString();
    }

    // ========== Comprehensive Metrics Fragment ==========
    private static Route<String> metricsRoute(ForgeCluster cluster, JdkHttpOperations http) {
        return Route.<String> get("/overview/metrics")
                    .to(_ -> renderMetrics(cluster, http))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static Promise<String> renderMetrics(ForgeCluster cluster, JdkHttpOperations http) {
        return cluster.getLeaderManagementPort()
                      .async(MetricsNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/metrics/comprehensive"))
                      .map(ViewRoutes::formatMetricsHtml)
                      .recover(_ -> "<div class=\"placeholder\">Metrics not available (no leader)</div>");
    }

    private static String formatMetricsHtml(String jsonBody) {
        var sb = new StringBuilder();
        sb.append("<div class=\"metrics-grid\">");
        appendMetricCard(sb, "CPU", formatPercent(extractDouble(jsonBody, "avgCpuUsage")), "cpu");
        appendMetricCard(sb, "Heap", formatPercent(extractDouble(jsonBody, "avgHeapUsage")), "heap");
        appendMetricCard(sb, "Avg Latency", formatMs(extractDouble(jsonBody, "avgLatencyMs")), "latency");
        appendMetricCard(sb, "P50", formatMs(extractDouble(jsonBody, "latencyP50")), "latency");
        appendMetricCard(sb, "P95", formatMs(extractDouble(jsonBody, "latencyP95")), "latency");
        appendMetricCard(sb, "P99", formatMs(extractDouble(jsonBody, "latencyP99")), "latency");
        appendMetricCard(sb, "Invocations", formatLong(extractLong(jsonBody, "totalInvocations")), "invocations");
        appendMetricCard(sb, "Error Rate", formatPercent(extractDouble(jsonBody, "errorRate")), "error");
        appendMetricCard(sb, "GC Pause", extractLong(jsonBody, "totalGcPauseMs") + "ms", "gc");
        appendMetricCard(sb, "Event Loop Lag", formatMs(extractDouble(jsonBody, "avgEventLoopLagMs")), "lag");
        appendMetricCard(sb, "Events", String.valueOf(extractLong(jsonBody, "eventCount")), "events");
        appendMetricCard(sb, "Samples", String.valueOf(extractLong(jsonBody, "sampleCount")), "samples");
        sb.append("</div>");
        return sb.toString();
    }

    private static void appendMetricCard(StringBuilder sb, String label, String value, String cssClass) {
        sb.append("<div class=\"mini-metric ").append(cssClass).append("\">");
        sb.append("<span class=\"mini-metric-value\">").append(value).append("</span>");
        sb.append("<span class=\"mini-metric-label\">").append(label).append("</span>");
        sb.append("</div>");
    }

    private static double extractDouble(String json, String key) {
        var search = "\"" + key + "\":";
        var idx = json.indexOf(search);
        if (idx < 0) {
            return 0.0;
        }
        var start = idx + search.length();
        var end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                                        || json.charAt(end) == '.'
                                        || json.charAt(end) == '-'
                                        || json.charAt(end) == 'E'
                                        || json.charAt(end) == 'e')) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException _) {
            return 0.0;
        }
    }

    private static long extractLong(String json, String key) {
        var search = "\"" + key + "\":";
        var idx = json.indexOf(search);
        if (idx < 0) {
            return 0;
        }
        var start = idx + search.length();
        var end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static String formatPercent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String formatMs(double ms) {
        return String.format("%.1fms", ms);
    }

    private static String formatLong(long value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    // ========== Load Testing Tab ==========
    private static Route<String> loadTabRoute(Option<Path> loadConfigPath) {
        return Route.<String> get("/load")
                    .to(_ -> Promise.success(renderLoadTab(loadConfigPath)))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderLoadTab(Option<Path> loadConfigPath) {
        var configContent = loadConfigPath.flatMap(ViewRoutes::readFile).or("");
        return """
            <div class="load-testing-grid">
                <div class="panel panel-wide">
                    <h2>Configuration</h2>
                    <div class="load-config-section">
                        <textarea id="load-config-text" placeholder="Paste TOML configuration here..." rows="10">%s</textarea>
                        <div class="load-config-actions">
                            <button id="btn-upload-config" class="btn btn-primary" onclick="uploadLoadConfig()">Upload Config</button>
                            <span id="load-config-status"></span>
                        </div>
                    </div>
                    <div class="load-config-info" id="load-config-info">
                        <span>No configuration loaded</span>
                    </div>
                </div>
                <div class="panel">
                    <h2>Controls</h2>
                    <div class="load-controls-section">
                        <div class="load-state-display">
                            <span class="state-label">State:</span>
                            <span id="load-runner-state" class="state-value">IDLE</span>
                        </div>
                        <div class="load-control-buttons">
                            <button class="btn btn-success" onclick="loadAction('start')">Start</button>
                            <button class="btn btn-warning" onclick="loadAction('pause')">Pause</button>
                            <button class="btn btn-info" onclick="loadAction('resume')">Resume</button>
                            <button class="btn btn-danger" onclick="loadAction('stop')">Stop</button>
                        </div>
                        <div class="rate-buttons">
                            <span class="rate-label">Rate:</span>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(100)">100</button>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(200)">200</button>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(500)">500</button>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(1000)">1K</button>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(2000)">2K</button>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(5000)">5K</button>
                            <button class="btn btn-primary btn-small" onclick="setTotalRate(10000)">10K</button>
                        </div>
                        <div class="config-row">
                            <button class="btn btn-secondary btn-small" onclick="resetMetrics()">Reset Metrics</button>
                        </div>
                    </div>
                </div>
                <div class="panel panel-wide">
                    <h2>Per-Target Metrics</h2>
                    <div id="load-metrics-container"
                         hx-get="/api/view/load/status"
                         hx-trigger="load, every 1s"
                         hx-swap="innerHTML">
                        <div class="placeholder">No targets running</div>
                    </div>
                </div>
                <div class="panel">
                    <h2>Pattern Reference</h2>
                    <div class="pattern-help">
                        <div class="pattern-item"><code>${uuid}</code> - Random UUID</div>
                        <div class="pattern-item"><code>${random:SKU-#####}</code> - # = digit, ? = letter, * = alphanumeric</div>
                        <div class="pattern-item"><code>${range:1-100}</code> - Random integer in range</div>
                        <div class="pattern-item"><code>${choice:NYC,LAX,CHI}</code> - Random pick from list</div>
                        <div class="pattern-item"><code>${seq:1000}</code> - Sequential counter</div>
                    </div>
                </div>
            </div>
            """.formatted(escapeHtml(configContent));
    }

    // ========== Load Runner Status Fragment ==========
    private static Route<String> loadStatusRoute(ConfigurableLoadRunner loadRunner) {
        return Route.<String> get("/load/status")
                    .to(_ -> Promise.success(renderLoadStatus(loadRunner)))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderLoadStatus(ConfigurableLoadRunner loadRunner) {
        var targets = loadRunner.allTargetMetrics();
        if (targets.isEmpty()) {
            return "<div class=\"placeholder\">No targets running</div>";
        }
        var sb = new StringBuilder();
        sb.append("<table class=\"load-metrics-table-inner\"><thead><tr>");
        sb.append("<th>Target</th><th>Rate (actual/target)</th><th>Requests</th>");
        sb.append("<th>Success</th><th>Failures</th><th>Success %</th><th>Avg Latency</th><th>Remaining</th>");
        sb.append("</tr></thead><tbody>");
        for (var entry : targets.values()) {
            sb.append("<tr>");
            sb.append("<td>").append(escapeHtml(entry.name())).append("</td>");
            sb.append("<td>").append(entry.actualRate()).append(" / ").append(entry.targetRate()).append("</td>");
            sb.append("<td>").append(entry.totalRequests()).append("</td>");
            sb.append("<td class=\"success\">").append(entry.successCount()).append("</td>");
            sb.append("<td class=\"error\">").append(entry.failureCount()).append("</td>");
            sb.append("<td>").append(String.format("%.1f", entry.successRate())).append("%</td>");
            sb.append("<td>").append(String.format("%.1f", entry.avgLatencyMs())).append("ms</td>");
            sb.append("<td>").append(entry.remainingDuration().map(Object::toString).or("-")).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    // ========== Alerts Tab Shell ==========
    private static Route<String> alertsTabRoute() {
        return Route.<String> get("/alerts")
                    .to(_ -> Promise.success(renderAlertsShell()))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderAlertsShell() {
        return """
            <div class="alerts-grid">
                <div class="panel panel-wide">
                    <h2>Active Alerts</h2>
                    <div id="active-alerts" class="alerts-content"
                         hx-get="/api/view/alerts/active"
                         hx-trigger="load, every 5s"
                         hx-swap="innerHTML">
                        <div class="placeholder">Loading...</div>
                    </div>
                    <div class="panel-actions">
                        <button class="btn btn-warning btn-small"
                                hx-post="/api/alerts/clear"
                                hx-swap="none"
                                hx-on::after-request="htmx.trigger('#active-alerts','htmx:load')">Clear All</button>
                    </div>
                </div>
                <div class="panel panel-wide">
                    <h2>Alert History</h2>
                    <div id="alert-history" class="alerts-content"
                         hx-get="/api/view/alerts/history"
                         hx-trigger="load, every 10s"
                         hx-swap="innerHTML">
                        <div class="placeholder">Loading...</div>
                    </div>
                </div>
            </div>
            """;
    }

    // ========== Active Alerts Fragment ==========
    private static Route<String> activeAlertsRoute(ForgeCluster cluster, JdkHttpOperations http) {
        return Route.<String> get("/alerts/active")
                    .to(_ -> renderAlertFragment(cluster, http, "/api/alerts/active"))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    // ========== Alert History Fragment ==========
    private static Route<String> alertHistoryRoute(ForgeCluster cluster, JdkHttpOperations http) {
        return Route.<String> get("/alerts/history")
                    .to(_ -> renderAlertFragment(cluster, http, "/api/alerts/history"))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static Promise<String> renderAlertFragment(ForgeCluster cluster,
                                                        JdkHttpOperations http,
                                                        String path) {
        return cluster.getLeaderManagementPort()
                      .async(MetricsNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, path))
                      .map(ViewRoutes::formatAlertHtml)
                      .recover(_ -> "<div class=\"placeholder\">Alerts not available (no leader)</div>");
    }

    private static String formatAlertHtml(String jsonBody) {
        // Render raw JSON response as pre-formatted text
        // A proper implementation would parse JSON and render structured HTML,
        // but this avoids adding a JSON parser dependency to the view layer
        if (jsonBody.contains("\"alerts\":[]") || jsonBody.contains("\"alerts\": []")) {
            return "<div class=\"no-alerts\">No alerts</div>";
        }
        return "<pre class=\"metrics-pre\">" + escapeHtml(jsonBody) + "</pre>";
    }

    // ========== Helpers ==========
    private static Option<String> readFile(Path path) {
        try {
            return Option.some(Files.readString(path));
        } catch (IOException _) {
            return Option.none();
        }
    }

    private static Promise<String> sendGet(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult().async());
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    enum MetricsNotAvailable implements org.pragmatica.lang.Cause {
        INSTANCE;
        @Override
        public String message() {
            return "Metrics not available";
        }
    }

    record unused() implements ViewRoutes {}
}
