package org.pragmatica.aether.forge.api;

import org.pragmatica.http.routing.CommonContentTypes;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.Route.in;

/// Routes for HTML panel endpoints.
/// Returns HTML fragments for the dashboard panels.
public final class PanelRoutes {
    private PanelRoutes() {}

    /// Create panel routes that return HTML content.
    ///
    /// @return RouteSource containing panel routes
    public static RouteSource panelRoutes() {
        return in("/api/panel").serve(chaosPanelRoute(), loadPanelRoute());
    }

    private static Route<String> chaosPanelRoute() {
        return Route.<String> get("/chaos")
                    .to(_ -> Promise.success(chaosPanelHtml()))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static Route<String> loadPanelRoute() {
        return Route.<String> get("/load")
                    .to(_ -> Promise.success(loadPanelHtml()))
                    .as(CommonContentTypes.TEXT_HTML);
    }

    private static String chaosPanelHtml() {
        return """
            <div class="panel panel-full-width panel-chaos">
                <h2>Chaos Controls</h2>
                <div class="control-section">
                    <div class="control-buttons">
                        <button id="btn-kill-node" class="btn btn-danger btn-small" onclick="showNodeModal(false)">Kill Node</button>
                        <button id="btn-kill-leader" class="btn btn-warning btn-small" onclick="killLeader()">Kill Leader</button>
                        <button id="btn-rolling-restart" class="btn btn-secondary btn-small" onclick="toggleRollingRestart()">Rolling Restart</button>
                        <button id="btn-reset" class="btn btn-secondary btn-small" onclick="resetMetrics()">Reset Metrics</button>
                    </div>
                </div>
            </div>
            """;
    }

    private static String loadPanelHtml() {
        return """
            <!-- Load Testing Panel -->
            <div class="panel-section">
                <h3>Configuration</h3>
                <div class="config-upload">
                    <textarea id="loadConfigText" placeholder="Paste TOML config here..." rows="10"></textarea>
                    <button onclick="uploadLoadConfig()">Upload Config</button>
                </div>
                <div id="loadConfigStatus"></div>
            </div>
            <div class="panel-section">
                <h3>Controls</h3>
                <div class="load-controls">
                    <button onclick="loadAction('start')" class="btn-primary">Start</button>
                    <button onclick="loadAction('pause')" class="btn-warning">Pause</button>
                    <button onclick="loadAction('resume')" class="btn-success">Resume</button>
                    <button onclick="loadAction('stop')" class="btn-danger">Stop</button>
                </div>
                <div class="load-state">
                    State: <span id="loadState">IDLE</span>
                </div>
            </div>
            <div class="panel-section">
                <h3>Per-Target Metrics</h3>
                <table class="metrics-table">
                    <thead>
                        <tr>
                            <th>Target</th>
                            <th>Rate (actual/target)</th>
                            <th>Requests</th>
                            <th>Success Rate</th>
                            <th>Avg Latency</th>
                        </tr>
                    </thead>
                    <tbody id="loadTargetMetrics">
                    </tbody>
                </table>
            </div>
            <script>
            function uploadLoadConfig() {
                const text = document.getElementById('loadConfigText').value;
                fetch('/api/load/config', { method: 'POST', body: text })
                    .then(r => r.json())
                    .then(data => {
                        if (data.error) {
                            document.getElementById('loadConfigStatus').innerHTML =
                                '<span class="error">' + data.error + '</span>';
                        } else {
                            document.getElementById('loadConfigStatus').innerHTML =
                                '<span class="success">Loaded ' + data.targetCount + ' targets</span>';
                        }
                    });
            }
            function loadAction(action) {
                fetch('/api/load/' + action, { method: 'POST' })
                    .then(r => r.json())
                    .then(data => {
                        if (data.state) {
                            document.getElementById('loadState').textContent = data.state;
                        }
                    });
            }
            function updateLoadMetrics() {
                fetch('/api/load/status')
                    .then(r => r.json())
                    .then(data => {
                        document.getElementById('loadState').textContent = data.state;
                        const tbody = document.getElementById('loadTargetMetrics');
                        tbody.innerHTML = data.targets.map(t =>
                            '<tr>' +
                            '<td>' + t.name + '</td>' +
                            '<td>' + t.actualRate + '/' + t.targetRate + '</td>' +
                            '<td>' + t.requests + '</td>' +
                            '<td>' + t.successRate.toFixed(1) + '%</td>' +
                            '<td>' + t.avgLatencyMs.toFixed(1) + 'ms</td>' +
                            '</tr>'
                        ).join('');
                    });
            }
            setInterval(updateLoadMetrics, 1000);
            updateLoadMetrics();
            </script>
            """;
    }
}
