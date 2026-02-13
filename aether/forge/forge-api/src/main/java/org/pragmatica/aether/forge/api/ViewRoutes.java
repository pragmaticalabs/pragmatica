package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
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

import static org.pragmatica.http.routing.Route.in;

/// HTML view endpoints for the dashboard.
/// Returns static shell HTML with placeholder divs that client-side JS populates
/// via a single /api/status fetch.
public sealed interface ViewRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    static RouteSource viewRoutes(ForgeCluster cluster,
                                   Option<Path> loadConfigPath) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return in("/api/view")
        .serve(overviewRoute(),
               testingTabRoute(loadConfigPath),
               alertsTabRoute(),
               activeAlertsRoute(cluster, http),
               alertHistoryRoute(cluster, http));
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
                        <h2 class="panel-header" id="nodes-header">Cluster Nodes</h2>
                        <div id="nodes-list" class="nodes-list">
                            <div class="node-item placeholder">Loading...</div>
                        </div>
                    </div>
                </div>
                <div class="right-column">
                    <div class="panel">
                        <h2 class="panel-header" id="slices-header">Slices Status</h2>
                        <div id="slices-content" class="slices-content">
                            <div class="placeholder">Loading...</div>
                        </div>
                    </div>
                    <div class="panel">
                        <h2>Metrics</h2>
                        <div id="metrics-content" class="config-content">
                            <div class="placeholder">Loading...</div>
                        </div>
                    </div>
                </div>
            </div>
            """;
    }

    // ========== Testing Tab ==========
    private static Route<String> testingTabRoute(Option<Path> loadConfigPath) {
        return Route.<String> get("/testing")
                    .to(_ -> Promise.success(renderTestingTab(loadConfigPath)))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String renderTestingTab(Option<Path> loadConfigPath) {
        var configContent = loadConfigPath.flatMap(ViewRoutes::readFile).or("");
        return """
            <div class="testing-tab">
                <div class="panel">
                    <h2>Performance</h2>
                    <div class="metrics-row">
                        <div class="metric-card">
                            <div class="metric-value" id="requests-per-sec">0</div>
                            <div class="metric-label">req/s</div>
                        </div>
                        <div class="metric-card success">
                            <div class="metric-value" id="success-rate">100%%</div>
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
                <div class="panel">
                    <h2>Per-Target Metrics</h2>
                    <div id="load-metrics-container">
                        <div class="placeholder">No targets running</div>
                    </div>
                </div>
                <div class="panel">
                    <h2 class="panel-header">Controls<span class="panel-badge" id="load-runner-state">IDLE</span></h2>
                    <div class="testing-controls-row">
                        <button id="btn-kill-node" class="btn btn-danger btn-small" onclick="showNodeModal(false)">Kill Node</button>
                        <button id="btn-kill-leader" class="btn btn-warning btn-small" onclick="killLeader()">Kill Leader</button>
                        <button id="btn-rolling-restart" class="btn btn-secondary btn-small" onclick="toggleRollingRestart()">Rolling Restart</button>
                        <span class="controls-separator"></span>
                        <button class="btn btn-success btn-small" onclick="loadAction('start')">Start</button>
                        <button class="btn btn-warning btn-small" onclick="loadAction('pause')">Pause</button>
                        <button class="btn btn-info btn-small" onclick="loadAction('resume')">Resume</button>
                        <button class="btn btn-danger btn-small" onclick="loadAction('stop')">Stop</button>
                        <span class="controls-separator"></span>
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
                        <span class="controls-separator"></span>
                        <button id="btn-reset" class="btn btn-secondary btn-small" onclick="resetMetrics()">Reset Metrics</button>
                    </div>
                </div>
                <div class="testing-config-row">
                    <div class="panel">
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
            </div>
            """.formatted(escapeHtml(configContent));
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
