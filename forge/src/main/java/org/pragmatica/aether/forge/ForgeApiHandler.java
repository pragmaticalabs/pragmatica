package org.pragmatica.aether.forge;

import org.pragmatica.aether.forge.api.ChaosRoutes.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.ForgeEvent;
import org.pragmatica.aether.forge.api.ForgeRouter;
import org.pragmatica.aether.forge.api.SimulatorRoutes.InventoryState;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.simulator.ChaosController;
import org.pragmatica.aether.forge.simulator.ChaosEvent;
import org.pragmatica.aether.forge.simulator.SimulatorConfig;
import org.pragmatica.aether.forge.simulator.SimulatorMode;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.RequestContextImpl;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Cause;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handles REST API requests for the Forge dashboard.
 * Uses RequestRouter for endpoint routing and delegates to domain-specific route handlers.
 */
@Sharable
public final class ForgeApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(ForgeApiHandler.class);
    private static final int MAX_EVENTS = 100;

    private final RequestRouter router;
    private final JsonCodec jsonCodec;
    private final Deque<ForgeEvent> events;
    private final long startTime;
    private final AtomicLong requestCounter = new AtomicLong();

    // Mutable state for modes/config
    private final Object modeLock = new Object();
    private volatile SimulatorConfig config = SimulatorConfig.defaultConfig();
    private volatile SimulatorMode currentMode = SimulatorMode.DEVELOPMENT;
    private final ChaosController chaosController;
    private final LoadGenerator loadGenerator;
    private final InventoryState inventoryState;

    private ForgeApiHandler(ForgeCluster cluster,
                            LoadGenerator loadGenerator,
                            ForgeMetrics metrics,
                            ConfigurableLoadRunner configurableLoadRunner,
                            ChaosController chaosController,
                            InventoryState inventoryState,
                            Deque<ForgeEvent> events,
                            long startTime) {
        this.loadGenerator = loadGenerator;
        this.chaosController = chaosController;
        this.inventoryState = inventoryState;
        this.events = events;
        this.startTime = startTime;
        this.jsonCodec = JsonCodecAdapter.defaultCodec();
        // Create router with all route sources
        this.router = ForgeRouter.forgeRouter(cluster,
                                              loadGenerator,
                                              configurableLoadRunner,
                                              chaosController,
                                              this::getConfig,
                                              inventoryState,
                                              metrics,
                                              events,
                                              startTime,
                                              this::logEvent);
    }

    public static ForgeApiHandler forgeApiHandler(ForgeCluster cluster,
                                                  LoadGenerator loadGenerator,
                                                  ForgeMetrics metrics,
                                                  ConfigurableLoadRunner configurableLoadRunner) {
        var chaosController = ChaosController.chaosController(event -> executeChaosEvent(cluster, event));
        var inventoryState = InventoryState.inventoryState();
        var events = new ConcurrentLinkedDeque<ForgeEvent>();
        var startTime = System.currentTimeMillis();
        return new ForgeApiHandler(cluster,
                                   loadGenerator,
                                   metrics,
                                   configurableLoadRunner,
                                   chaosController,
                                   inventoryState,
                                   events,
                                   startTime);
    }

    private static void executeChaosEvent(ForgeCluster cluster, ChaosEvent event) {
        switch (event) {
            case ChaosEvent.NodeKill kill -> cluster.crashNode(kill.nodeId());
            case ChaosEvent.LatencySpike _ -> {}
            case ChaosEvent.SliceCrash _ -> {}
            case ChaosEvent.InvocationFailure _ -> {}
            default -> {}
        }
    }

    public int sliceCount() {
        return 0;
    }

    private SimulatorConfig getConfig() {
        return config;
    }

    private void logEvent(EventLogEntry entry) {
        addEvent(entry.type(), entry.message());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        var path = extractPath(request.uri());
        log.debug("API request: {} {}", request.method(), path);
        try{
            // Handle panel endpoints separately (return HTML)
            if (isPanelRequest(path)) {
                handlePanelRequest(ctx, path);
                return;
            }
            // Handle reset metrics (not in router)
            if (path.equals("/api/chaos/reset-metrics") && request.method() == io.netty.handler.codec.http.HttpMethod.POST) {
                handleResetMetrics(ctx);
                return;
            }
            // Route API requests via RequestRouter
            var method = convertMethod(request.method());
            router.findRoute(method, path)
                  .onEmpty(() -> sendNotFound(ctx, path))
                  .onPresent(route -> handleRoute(ctx, request, route, path));
        } catch (Exception e) {
            log.error("Error handling API request: {}", e.getMessage(), e);
            sendError(ctx, INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private boolean isPanelRequest(String path) {
        return path.equals("/api/panel/chaos") || path.equals("/api/panel/load");
    }

    private void handlePanelRequest(ChannelHandlerContext ctx, String path) {
        var html = switch (path) {
            case "/api/panel/chaos" -> chaosPanelHtml();
            case "/api/panel/load" -> loadPanelHtml();
            default -> "";
        };
        sendHtml(ctx, html);
    }

    private void handleRoute(ChannelHandlerContext ctx, FullHttpRequest request, Route< ?> route, String path) {
        var requestId = "forge-" + requestCounter.incrementAndGet();
        var context = RequestContextImpl.requestContext(request, route, jsonCodec, requestId);
        route.handler()
             .handle(context)
             .onSuccess(result -> sendSuccessResponse(ctx, route, result))
             .onFailure(cause -> sendError(ctx,
                                           BAD_REQUEST,
                                           cause.message()));
    }

    @SuppressWarnings("unchecked")
    private void sendSuccessResponse(ChannelHandlerContext ctx, Route< ?> route, Object result) {
        jsonCodec.serialize(result)
                 .onSuccess(byteBuf -> {
                                var response = new DefaultFullHttpResponse(HTTP_1_1, OK, byteBuf);
                                response.headers()
                                        .set(HttpHeaderNames.CONTENT_TYPE,
                                             route.contentType()
                                                  .headerText());
                                response.headers()
                                        .setInt(HttpHeaderNames.CONTENT_LENGTH,
                                                byteBuf.readableBytes());
                                addCorsHeaders(response);
                                ctx.writeAndFlush(response)
                                   .addListener(ChannelFutureListener.CLOSE);
                            })
                 .onFailure(cause -> sendError(ctx,
                                               INTERNAL_SERVER_ERROR,
                                               cause.message()));
    }

    private HttpMethod convertMethod(io.netty.handler.codec.http.HttpMethod nettyMethod) {
        return switch (nettyMethod.name()) {
            case "GET" -> HttpMethod.GET;
            case "POST" -> HttpMethod.POST;
            case "PUT" -> HttpMethod.PUT;
            case "DELETE" -> HttpMethod.DELETE;
            case "PATCH" -> HttpMethod.PATCH;
            case "HEAD" -> HttpMethod.HEAD;
            case "OPTIONS" -> HttpMethod.OPTIONS;
            default -> HttpMethod.GET;
        };
    }

    private String extractPath(String uri) {
        var queryIdx = uri.indexOf('?');
        return queryIdx >= 0
               ? uri.substring(0, queryIdx)
               : uri;
    }

    private void sendNotFound(ChannelHandlerContext ctx, String path) {
        log.debug("Route not found: {}", path);
        sendError(ctx, NOT_FOUND, "Not found: " + path);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        var json = "{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}";
        sendJsonResponse(ctx, status, json);
    }

    private void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        var content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HTTP_1_1, status, content);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        content.readableBytes());
        addCorsHeaders(response);
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private void sendHtml(ChannelHandlerContext ctx, String html) {
        var buf = Unpooled.copiedBuffer(html, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HTTP_1_1, OK, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        buf.readableBytes());
        addCorsHeaders(response);
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private void addCorsHeaders(DefaultFullHttpResponse response) {
        response.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
    }

    private void handleResetMetrics(ChannelHandlerContext ctx) {
        events.clear();
        inventoryState.reset();
        addEvent("RESET", "Metrics and events reset");
        sendJsonResponse(ctx, OK, "{\"success\":true}");
    }

    public void addEvent(String type, String message) {
        var event = new ForgeEvent(Instant.now()
                                          .toString(),
                                   type,
                                   message);
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
        log.info("[EVENT] {}: {}", type, message);
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in API handler: {}", cause.getMessage());
        ctx.close();
    }

    // ========== Panel HTML ==========
    private String chaosPanelHtml() {
        return """
            <!-- Chaos Control Panel -->
            <div class="panel control-panel">
                <h2>Chaos Controls</h2>
                <div class="control-section">
                    <div class="control-group">
                        <span class="control-label">Node Chaos</span>
                        <div class="control-buttons">
                            <button id="btn-kill-node" class="btn btn-danger btn-small">Kill Node</button>
                            <button id="btn-kill-leader" class="btn btn-warning btn-small">Kill Leader</button>
                            <button id="btn-rolling-restart" class="btn btn-secondary btn-small">Rolling Restart</button>
                            <button id="btn-add-node" class="btn btn-primary btn-small">Add Node</button>
                        </div>
                    </div>
                    <div class="control-group">
                        <span class="control-label">Load Control</span>
                        <div class="control-buttons">
                            <button id="btn-load-1k" class="btn btn-secondary btn-small">1K</button>
                            <button id="btn-load-5k" class="btn btn-secondary btn-small">5K</button>
                            <button id="btn-load-10k" class="btn btn-secondary btn-small">10K</button>
                            <button id="btn-load-25k" class="btn btn-secondary btn-small">25K</button>
                            <button id="btn-load-50k" class="btn btn-secondary btn-small">50K</button>
                            <button id="btn-load-100k" class="btn btn-secondary btn-small">100K</button>
                            <button id="btn-ramp" class="btn btn-primary btn-small">Ramp</button>
                        </div>
                    </div>
                    <div class="control-group">
                        <span class="control-label">Rate Slider</span>
                        <div class="slider-container">
                            <input type="range" id="load-slider" min="0" max="100000" value="0" step="1000">
                            <span id="load-value" class="slider-value">0</span>
                        </div>
                    </div>
                    <div class="control-group">
                        <span class="control-label">Load Generator</span>
                        <div class="control-buttons">
                            <label class="toggle-label">
                                <input type="checkbox" id="load-generator-toggle" checked>
                                <span>Enabled</span>
                            </label>
                            <input type="number" id="rate-multiplier" value="1.0" min="0.1" max="10" step="0.1" class="multiplier-input">
                            <button id="btn-apply-multiplier" class="btn btn-secondary btn-small">Apply</button>
                        </div>
                    </div>
                    <div class="control-group">
                        <button id="btn-reset" class="btn btn-secondary btn-small">Reset Metrics</button>
                    </div>
                </div>
            </div>
            """;
    }

    private String loadPanelHtml() {
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
