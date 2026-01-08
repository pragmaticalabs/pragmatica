package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.TlsContextFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * HTTP management API server for cluster administration.
 *
 * <p>Exposes REST endpoints for:
 * <ul>
 *   <li>GET /status - Cluster status</li>
 *   <li>GET /nodes - List active nodes</li>
 *   <li>GET /slices - List deployed slices</li>
 *   <li>GET /metrics - Cluster metrics</li>
 * </ul>
 */
public interface ManagementServer {
    Promise<Unit> start();

    Promise<Unit> stop();

    static ManagementServer managementServer(int port,
                                             Supplier<AetherNode> nodeSupplier,
                                             AlertManager alertManager,
                                             Option<TlsConfig> tls) {
        return new ManagementServerImpl(port, nodeSupplier, alertManager, tls);
    }
}

class ManagementServerImpl implements ManagementServer {
    private static final Logger log = LoggerFactory.getLogger(ManagementServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    // 64MB for artifact uploads
    private final int port;
    private final Supplier<AetherNode> nodeSupplier;
    private final MultiThreadIoEventLoopGroup bossGroup;
    private final MultiThreadIoEventLoopGroup workerGroup;
    private final AlertManager alertManager;
    private final DashboardMetricsPublisher metricsPublisher;
    private final ObservabilityRegistry observability;
    private final Option<SslContext> sslContext;
    private Channel serverChannel;

    ManagementServerImpl(int port,
                         Supplier<AetherNode> nodeSupplier,
                         AlertManager alertManager,
                         Option<TlsConfig> tls) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.alertManager = alertManager;
        this.metricsPublisher = new DashboardMetricsPublisher(nodeSupplier, alertManager);
        this.observability = ObservabilityRegistry.prometheus();
        this.sslContext = tls.map(TlsContextFactory::create)
                             .flatMap(Result::option);
    }

    @Override
    public Promise<Unit> start() {
        return Promise.promise(promise -> {
                                   var bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                                                                        .channel(NioServerSocketChannel.class)
                                                                        .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                                                                                          ChannelPipeline p = ch.pipeline();
                                                                                          sslContext.onPresent(ctx -> p.addLast(ctx.newHandler(ch.alloc())));
                                                                                          p.addLast(new HttpServerCodec());
                                                                                          p.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                                                                                          p.addLast(new WebSocketServerProtocolHandler("/ws/dashboard",
                                                                                                                                       null,
                                                                                                                                       true));
                                                                                          p.addLast(new DashboardWebSocketHandler(metricsPublisher));
                                                                                          p.addLast(new HttpRequestHandler(nodeSupplier,
                                                                                                                           alertManager,
                                                                                                                           observability));
                                                                                      }
        });
                                   bootstrap.bind(port)
                                            .addListener(future -> {
                                                             if (future.isSuccess()) {
                                                                 serverChannel = ((io.netty.channel.ChannelFuture) future).channel();
                                                                 metricsPublisher.start();
                                                                 var protocol = sslContext.isPresent()
                                                                                ? "HTTPS"
                                                                                : "HTTP";
                                                                 log.info("{} management server started on port {} (dashboard at /dashboard)",
                                                                          protocol,
                                                                          port);
                                                                 promise.succeed(unit());
                                                             } else {
                                                                 log.error("Failed to start management server on port {}",
                                                                           port,
                                                                           future.cause());
                                                                 promise.fail(Causes.fromThrowable(future.cause()));
                                                             }
                                                         });
                               });
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.promise(promise -> {
                                   metricsPublisher.stop();
                                   if (serverChannel != null) {
                                       serverChannel.close()
                                                    .addListener(_ -> shutdownEventLoops(promise));
                                   } else {
                                       shutdownEventLoops(promise);
                                   }
                               });
    }

    private void shutdownEventLoops(Promise<Unit> promise) {
        var bossFuture = bossGroup.shutdownGracefully();
        var workerFuture = workerGroup.shutdownGracefully();
        bossFuture.addListener(_ -> workerFuture.addListener(_ -> {
                                                                 log.info("Management server stopped");
                                                                 promise.succeed(unit());
                                                             }));
    }
}

class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    private static final String CONTENT_TYPE_CSS = "text/css; charset=UTF-8";
    private static final String CONTENT_TYPE_JS = "application/javascript; charset=UTF-8";
    private static final String CONTENT_TYPE_PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8";

    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;
    private final ObservabilityRegistry observability;

    HttpRequestHandler(Supplier<AetherNode> nodeSupplier,
                       AlertManager alertManager,
                       ObservabilityRegistry observability) {
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.observability = observability;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult()
                    .isSuccess()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        var method = request.method();
        var uri = request.uri();
        log.debug("Received {} {}", method, uri);
        if (method == HttpMethod.GET) {
            handleGet(ctx, uri);
        } else if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            // Handle both POST and PUT for repository uploads (Maven uses PUT)
            handlePost(ctx, uri, request);
        } else if (method == HttpMethod.DELETE) {
            handleDelete(ctx, uri);
        } else {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
        }
    }

    private void handleGet(ChannelHandlerContext ctx, String uri) {
        var node = nodeSupplier.get();
        // Handle dashboard static files
        if (uri.equals("/dashboard") || uri.equals("/dashboard/")) {
            serveDashboardFile(ctx, "index.html", CONTENT_TYPE_HTML);
            return;
        }
        if (uri.equals("/dashboard/style.css")) {
            serveDashboardFile(ctx, "style.css", CONTENT_TYPE_CSS);
            return;
        }
        if (uri.equals("/dashboard/dashboard.js")) {
            serveDashboardFile(ctx, "dashboard.js", CONTENT_TYPE_JS);
            return;
        }
        // Handle /api/ prefixed endpoints
        if (uri.startsWith("/api/")) {
            handleApiGet(ctx, node, uri.substring(4));
            // Strip /api prefix
            return;
        }
        // Handle repository requests (no /api prefix)
        if (uri.startsWith("/repository/")) {
            handleRepositoryGet(ctx, node, uri);
            return;
        }
        sendError(ctx, HttpResponseStatus.NOT_FOUND);
    }

    private void handleApiGet(ChannelHandlerContext ctx, AetherNode node, String uri) {
        // Handle chaos panel endpoint (returns empty for Node, Forge overrides)
        if (uri.equals("/panel/chaos")) {
            sendText(ctx, "");
            return;
        }
        // Handle rolling update paths
        if (uri.startsWith("/rolling-update/") || uri.equals("/rolling-updates")) {
            handleRollingUpdateGet(ctx, node, uri);
            return;
        }
        // Handle Prometheus metrics endpoint
        if (uri.equals("/metrics/prometheus")) {
            sendPrometheus(ctx, observability.scrape());
            return;
        }
        // Handle invocation metrics with optional query parameters
        if (uri.startsWith("/invocation-metrics")) {
            var path = extractPath(uri);
            if (path.equals("/invocation-metrics/slow")) {
                sendJson(ctx, buildSlowInvocationsResponse(node));
            } else if (path.equals("/invocation-metrics/strategy")) {
                sendJson(ctx, buildStrategyResponse(node));
            } else if (path.equals("/invocation-metrics")) {
                var artifactFilter = extractQueryParam(uri, "artifact");
                var methodFilter = extractQueryParam(uri, "method");
                sendJson(ctx, buildInvocationMetricsResponse(node, artifactFilter, methodFilter));
            } else {
                sendError(ctx, HttpResponseStatus.NOT_FOUND);
            }
            return;
        }
        var response = switch (uri) {
            case "/status" -> buildStatusResponse(node);
            case "/nodes" -> buildNodesResponse(node);
            case "/slices" -> buildSlicesResponse(node);
            case "/slices/status" -> buildSlicesStatusResponse(node);
            case "/metrics" -> buildMetricsResponse(node);
            case "/metrics/comprehensive" -> buildComprehensiveMetricsResponse(node);
            case "/metrics/derived" -> buildDerivedMetricsResponse(node);
            case "/thresholds" -> alertManager.thresholdsAsJson();
            case "/alerts" -> buildAlertsResponse();
            case "/alerts/active" -> alertManager.activeAlertsAsJson();
            case "/alerts/history" -> alertManager.alertHistoryAsJson();
            case "/health" -> buildHealthResponse(node);
            case "/controller/config" -> buildControllerConfigResponse(node);
            case "/controller/status" -> buildControllerStatusResponse(node);
            case "/ttm/status" -> buildTtmStatusResponse(node);
            case "/node-metrics" -> buildNodeMetricsResponse(node);
            case "/events" -> "[]";
            // Empty events for Node (Forge provides events)
            default -> (String) null;
        };
        if (response != null) {
            sendJson(ctx, response);
        } else {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private void serveDashboardFile(ChannelHandlerContext ctx, String filename, String contentType) {
        try (InputStream is = getClass()
                                      .getResourceAsStream("/dashboard/" + filename)) {
            if (is == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }
            var content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers()
                    .setInt(HttpHeaderNames.CONTENT_LENGTH,
                            buf.readableBytes());
            response.headers()
                    .set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            ctx.writeAndFlush(response)
               .addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error serving dashboard file: {}", filename, e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void handleRepositoryGet(ChannelHandlerContext ctx, AetherNode node, String uri) {
        node.mavenProtocolHandler()
            .handleGet(uri)
            .onSuccess(response -> {
                           var httpStatus = HttpResponseStatus.valueOf(response.statusCode());
                           var httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                                          httpStatus,
                                                                          Unpooled.wrappedBuffer(response.content()));
                           httpResponse.headers()
                                       .set(HttpHeaderNames.CONTENT_TYPE,
                                            response.contentType());
                           httpResponse.headers()
                                       .set(HttpHeaderNames.CONTENT_LENGTH,
                                            response.content().length);
                           ctx.writeAndFlush(httpResponse)
                              .addListener(ChannelFutureListener.CLOSE);
                       })
            .onFailure(cause -> sendJsonError(ctx,
                                              HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                              cause.message()));
    }

    private void handlePost(ChannelHandlerContext ctx, String uri, FullHttpRequest request) {
        var node = nodeSupplier.get();
        // Handle repository PUT requests (binary content, no /api prefix)
        if (uri.startsWith("/repository/")) {
            handleRepositoryPut(ctx, node, uri, request);
            return;
        }
        // Require /api/ prefix for API endpoints
        if (!uri.startsWith("/api/")) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }
        var apiUri = uri.substring(4);
        // Strip /api prefix
        var body = request.content()
                          .toString(CharsetUtil.UTF_8);
        // Handle rolling update POST paths
        if (apiUri.startsWith("/rolling-update")) {
            handleRollingUpdatePost(ctx, node, apiUri, body);
            return;
        }
        switch (apiUri) {
            case "/deploy" -> handleDeploy(ctx, node, body);
            case "/scale" -> handleScale(ctx, node, body);
            case "/undeploy" -> handleUndeploy(ctx, node, body);
            case "/blueprint" -> handleBlueprint(ctx, node, body);
            case "/controller/config" -> handleControllerConfig(ctx, node, body);
            case "/controller/evaluate" -> handleControllerEvaluate(ctx, node);
            case "/thresholds" -> handleSetThreshold(ctx, body);
            case "/alerts/clear" -> handleClearAlerts(ctx);
            case "/invocation-metrics/strategy" -> handleSetStrategy(ctx, node, body);
            default -> sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private void handleDelete(ChannelHandlerContext ctx, String uri) {
        // Require /api/ prefix
        if (!uri.startsWith("/api/")) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }
        var apiUri = uri.substring(4);
        // Strip /api prefix
        // DELETE /api/thresholds/{metric}
        if (apiUri.startsWith("/thresholds/")) {
            var metric = apiUri.substring("/thresholds/".length());
            handleDeleteThreshold(ctx, metric);
            return;
        }
        sendError(ctx, HttpResponseStatus.NOT_FOUND);
    }

    private void handleDeleteThreshold(ChannelHandlerContext ctx, String metric) {
        if (metric.isEmpty()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Metric name required");
            return;
        }
        alertManager.removeThreshold(metric)
                    .onSuccess(_ -> sendJson(ctx, "{\"status\":\"threshold_removed\",\"metric\":\"" + metric + "\"}"))
                    .onFailure(cause -> sendJsonError(ctx,
                                                      HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                      "Failed to remove threshold: " + cause.message()));
    }

    private void handleRepositoryPut(ChannelHandlerContext ctx, AetherNode node, String uri, FullHttpRequest request) {
        // Read binary content directly from ByteBuf
        var byteBuf = request.content();
        var content = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(content);
        node.mavenProtocolHandler()
            .handlePut(uri, content)
            .onSuccess(response -> {
                           var httpStatus = HttpResponseStatus.valueOf(response.statusCode());
                           var httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                                          httpStatus,
                                                                          Unpooled.wrappedBuffer(response.content()));
                           httpResponse.headers()
                                       .set(HttpHeaderNames.CONTENT_TYPE,
                                            response.contentType());
                           httpResponse.headers()
                                       .set(HttpHeaderNames.CONTENT_LENGTH,
                                            response.content().length);
                           ctx.writeAndFlush(httpResponse)
                              .addListener(ChannelFutureListener.CLOSE);
                       })
            .onFailure(cause -> sendJsonError(ctx,
                                              HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                              cause.message()));
    }

    // URL parsing helpers
    private static String extractPath(String uri) {
        var queryIndex = uri.indexOf('?');
        return queryIndex >= 0
               ? uri.substring(0, queryIndex)
               : uri;
    }

    private static Option<String> extractQueryParam(String uri, String paramName) {
        var queryIndex = uri.indexOf('?');
        if (queryIndex < 0) return Option.empty();
        var query = uri.substring(queryIndex + 1);
        for (var pair : query.split("&")) {
            var kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(paramName)) {
                return Option.option(java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return Option.empty();
    }

    // Simple JSON parsing helpers
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile("\"artifact\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INSTANCES_PATTERN = Pattern.compile("\"instances\"\\s*:\\s*(\\d+)");

    private void handleDeploy(ChannelHandlerContext ctx, AetherNode node, String body) {
        // Parse: {"artifact": "group:id:version", "instances": 1}
        var artifactMatch = ARTIFACT_PATTERN.matcher(body);
        var instancesMatch = INSTANCES_PATTERN.matcher(body);
        if (!artifactMatch.find()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing 'artifact' field");
            return;
        }
        int instances = 1;
        if (instancesMatch.find()) {
            instances = Integer.parseInt(instancesMatch.group(1));
        }
        var artifactStr = artifactMatch.group(1);
        int finalInstances = instances;
        Artifact.artifact(artifactStr)
                .onSuccess(artifact -> {
                               AetherKey key = new AetherKey.BlueprintKey(artifact);
                               AetherValue value = new AetherValue.BlueprintValue(finalInstances);
                               KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
                               node.apply(List.of(command))
                                   .onSuccess(_ -> sendJson(ctx,
                                                            "{\"status\":\"deployed\",\"artifact\":\"" + artifactStr
                                                            + "\",\"instances\":" + finalInstances + "}"))
                                   .onFailure(cause -> sendJsonError(ctx,
                                                                     HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                                     cause.message()));
                           })
                .onFailure(cause -> sendJsonError(ctx,
                                                  HttpResponseStatus.BAD_REQUEST,
                                                  cause.message()));
    }

    private void handleScale(ChannelHandlerContext ctx, AetherNode node, String body) {
        // Parse: {"artifact": "group:id:version", "instances": N}
        var artifactMatch = ARTIFACT_PATTERN.matcher(body);
        var instancesMatch = INSTANCES_PATTERN.matcher(body);
        if (!artifactMatch.find() || !instancesMatch.find()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing 'artifact' or 'instances' field");
            return;
        }
        var artifactStr = artifactMatch.group(1);
        var instances = Integer.parseInt(instancesMatch.group(1));
        Artifact.artifact(artifactStr)
                .onSuccess(artifact -> {
                               AetherKey key = new AetherKey.BlueprintKey(artifact);
                               AetherValue value = new AetherValue.BlueprintValue(instances);
                               KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
                               node.apply(List.of(command))
                                   .onSuccess(_ -> sendJson(ctx,
                                                            "{\"status\":\"scaled\",\"artifact\":\"" + artifactStr
                                                            + "\",\"instances\":" + instances + "}"))
                                   .onFailure(cause -> sendJsonError(ctx,
                                                                     HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                                     cause.message()));
                           })
                .onFailure(cause -> sendJsonError(ctx,
                                                  HttpResponseStatus.BAD_REQUEST,
                                                  cause.message()));
    }

    private void handleUndeploy(ChannelHandlerContext ctx, AetherNode node, String body) {
        // Parse: {"artifact": "group:id:version"}
        var artifactMatch = ARTIFACT_PATTERN.matcher(body);
        if (!artifactMatch.find()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing 'artifact' field");
            return;
        }
        var artifactStr = artifactMatch.group(1);
        Artifact.artifact(artifactStr)
                .onSuccess(artifact -> {
                               AetherKey key = new AetherKey.BlueprintKey(artifact);
                               KVCommand<AetherKey> command = new KVCommand.Remove<>(key);
                               node.apply(List.of(command))
                                   .onSuccess(_ -> sendJson(ctx,
                                                            "{\"status\":\"undeployed\",\"artifact\":\"" + artifactStr
                                                            + "\"}"))
                                   .onFailure(cause -> sendJsonError(ctx,
                                                                     HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                                     cause.message()));
                           })
                .onFailure(cause -> sendJsonError(ctx,
                                                  HttpResponseStatus.BAD_REQUEST,
                                                  cause.message()));
    }

    private void handleBlueprint(ChannelHandlerContext ctx, AetherNode node, String body) {
        BlueprintParser.parse(body)
                       .onSuccess(blueprint -> {
                                      // Build commands for all slices in blueprint
        var commands = blueprint.slices()
                                .stream()
                                .map(spec -> {
                                         AetherKey key = new AetherKey.BlueprintKey(spec.artifact());
                                         AetherValue value = new AetherValue.BlueprintValue(spec.instances());
                                         return (KVCommand<AetherKey>) new KVCommand.Put<>(key, value);
                                     })
                                .toList();
                                      if (commands.isEmpty()) {
                                          sendJson(ctx,
                                                   "{\"status\":\"applied\",\"blueprint\":\"" + blueprint.id()
                                                                                                         .asString()
                                                   + "\",\"slices\":0}");
                                          return;
                                      }
                                      node.apply(commands)
                                          .onSuccess(_ -> sendJson(ctx,
                                                                   "{\"status\":\"applied\",\"blueprint\":\"" + blueprint.id()
                                                                                                                         .asString()
                                                                   + "\",\"slices\":" + commands.size() + "}"))
                                          .onFailure(cause -> sendJsonError(ctx,
                                                                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                                            cause.message()));
                                  })
                       .onFailure(cause -> sendJsonError(ctx,
                                                         HttpResponseStatus.BAD_REQUEST,
                                                         cause.message()));
    }

    // ===== Rolling Update Handlers =====
    private void handleRollingUpdateGet(ChannelHandlerContext ctx, AetherNode node, String uri) {
        if (uri.equals("/rolling-updates")) {
            // List all active rolling updates
            sendJson(ctx, buildRollingUpdatesResponse(node));
        } else if (uri.startsWith("/rolling-update/") && uri.endsWith("/health")) {
            // GET /rolling-update/{id}/health
            var updateId = extractUpdateId(uri, "/health");
            sendJson(ctx, buildRollingUpdateHealthResponse(node, updateId));
        } else if (uri.startsWith("/rolling-update/")) {
            // GET /rolling-update/{id}
            var updateId = uri.substring("/rolling-update/".length());
            sendJson(ctx, buildRollingUpdateResponse(node, updateId));
        } else {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private void handleRollingUpdatePost(ChannelHandlerContext ctx, AetherNode node, String uri, String body) {
        if (uri.equals("/rolling-update/start")) {
            handleRollingUpdateStart(ctx, node, body);
        } else if (uri.endsWith("/routing")) {
            var updateId = extractUpdateId(uri, "/routing");
            handleRollingUpdateRouting(ctx, node, updateId, body);
        } else if (uri.endsWith("/complete")) {
            var updateId = extractUpdateId(uri, "/complete");
            handleRollingUpdateComplete(ctx, node, updateId);
        } else if (uri.endsWith("/rollback")) {
            var updateId = extractUpdateId(uri, "/rollback");
            handleRollingUpdateRollback(ctx, node, updateId);
        } else {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private String extractUpdateId(String uri, String suffix) {
        var path = uri.substring("/rolling-update/".length());
        return path.substring(0,
                              path.length() - suffix.length());
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ARTIFACT_BASE_PATTERN = Pattern.compile("\"artifactBase\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ROUTING_PATTERN = Pattern.compile("\"routing\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAX_ERROR_RATE_PATTERN = Pattern.compile("\"maxErrorRate\"\\s*:\\s*([0-9.]+)");
    private static final Pattern MAX_LATENCY_PATTERN = Pattern.compile("\"maxLatencyMs\"\\s*:\\s*(\\d+)");
    private static final Pattern MANUAL_APPROVAL_PATTERN = Pattern.compile("\"requireManualApproval\"\\s*:\\s*(true|false)");
    private static final Pattern CLEANUP_POLICY_PATTERN = Pattern.compile("\"cleanupPolicy\"\\s*:\\s*\"([^\"]+)\"");

    private void handleRollingUpdateStart(ChannelHandlerContext ctx, AetherNode node, String body) {
        if (!requireLeader(ctx, node)) {
            return;
        }
        var artifactBaseMatch = ARTIFACT_BASE_PATTERN.matcher(body);
        var versionMatch = VERSION_PATTERN.matcher(body);
        var instancesMatch = INSTANCES_PATTERN.matcher(body);
        if (!artifactBaseMatch.find() || !versionMatch.find()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing artifactBase or version");
            return;
        }
        var artifactBaseStr = artifactBaseMatch.group(1);
        var versionStr = versionMatch.group(1);
        int instances = instancesMatch.find()
                        ? Integer.parseInt(instancesMatch.group(1))
                        : 1;
        // Parse optional health thresholds
        var errorRateMatch = MAX_ERROR_RATE_PATTERN.matcher(body);
        var latencyMatch = MAX_LATENCY_PATTERN.matcher(body);
        var manualApprovalMatch = MANUAL_APPROVAL_PATTERN.matcher(body);
        var cleanupMatch = CLEANUP_POLICY_PATTERN.matcher(body);
        double maxErrorRate = errorRateMatch.find()
                              ? Double.parseDouble(errorRateMatch.group(1))
                              : 0.01;
        long maxLatencyMs = latencyMatch.find()
                            ? Long.parseLong(latencyMatch.group(1))
                            : 500;
        boolean manualApproval = manualApprovalMatch.find() && Boolean.parseBoolean(manualApprovalMatch.group(1));
        var cleanupPolicy = cleanupMatch.find()
                            ? org.pragmatica.aether.update.CleanupPolicy.valueOf(cleanupMatch.group(1))
                            : org.pragmatica.aether.update.CleanupPolicy.GRACE_PERIOD;
        org.pragmatica.aether.artifact.ArtifactBase.artifactBase(artifactBaseStr)
           .flatMap(artifactBase -> org.pragmatica.aether.artifact.Version.version(versionStr)
                                       .flatMap(version -> org.pragmatica.aether.update.HealthThresholds.healthThresholds(maxErrorRate,
                                                                                                                          maxLatencyMs,
                                                                                                                          manualApproval)
                                                              .map(thresholds -> new Object[]{artifactBase, version, thresholds})))
           .onSuccess(arr -> {
                          var artifactBase = (org.pragmatica.aether.artifact.ArtifactBase) arr[0];
                          var version = (org.pragmatica.aether.artifact.Version) arr[1];
                          var thresholds = (org.pragmatica.aether.update.HealthThresholds) arr[2];
                          node.rollingUpdateManager()
                              .startUpdate(artifactBase, version, instances, thresholds, cleanupPolicy)
                              .onSuccess(update -> sendJson(ctx,
                                                            buildRollingUpdateJson(update)))
                              .onFailure(cause -> sendJsonError(ctx,
                                                                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                                cause.message()));
                      })
           .onFailure(cause -> sendJsonError(ctx,
                                             HttpResponseStatus.BAD_REQUEST,
                                             cause.message()));
    }

    private void handleRollingUpdateRouting(ChannelHandlerContext ctx, AetherNode node, String updateId, String body) {
        if (!requireLeader(ctx, node)) {
            return;
        }
        var routingMatch = ROUTING_PATTERN.matcher(body);
        if (!routingMatch.find()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing routing field");
            return;
        }
        org.pragmatica.aether.update.VersionRouting.parse(routingMatch.group(1))
           .onSuccess(routing -> node.rollingUpdateManager()
                                     .adjustRouting(updateId, routing)
                                     .onSuccess(update -> sendJson(ctx,
                                                                   buildRollingUpdateJson(update)))
                                     .onFailure(cause -> sendJsonError(ctx,
                                                                       HttpResponseStatus.BAD_REQUEST,
                                                                       cause.message())))
           .onFailure(cause -> sendJsonError(ctx,
                                             HttpResponseStatus.BAD_REQUEST,
                                             cause.message()));
    }

    private void handleRollingUpdateComplete(ChannelHandlerContext ctx, AetherNode node, String updateId) {
        if (!requireLeader(ctx, node)) {
            return;
        }
        node.rollingUpdateManager()
            .completeUpdate(updateId)
            .onSuccess(update -> sendJson(ctx,
                                          buildRollingUpdateJson(update)))
            .onFailure(cause -> sendJsonError(ctx,
                                              HttpResponseStatus.BAD_REQUEST,
                                              cause.message()));
    }

    private void handleRollingUpdateRollback(ChannelHandlerContext ctx, AetherNode node, String updateId) {
        if (!requireLeader(ctx, node)) {
            return;
        }
        node.rollingUpdateManager()
            .rollback(updateId)
            .onSuccess(update -> sendJson(ctx,
                                          buildRollingUpdateJson(update)))
            .onFailure(cause -> sendJsonError(ctx,
                                              HttpResponseStatus.BAD_REQUEST,
                                              cause.message()));
    }

    private String buildRollingUpdatesResponse(AetherNode node) {
        var updates = node.rollingUpdateManager()
                          .activeUpdates();
        var sb = new StringBuilder();
        sb.append("{\"updates\":[");
        boolean first = true;
        for (var update : updates) {
            if (!first) sb.append(",");
            sb.append(buildRollingUpdateJson(update));
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildRollingUpdateResponse(AetherNode node, String updateId) {
        return node.rollingUpdateManager()
                   .getUpdate(updateId)
                   .map(this::buildRollingUpdateJson)
                   .or(updateNotFoundJson(updateId));
    }

    private String updateNotFoundJson(String updateId) {
        return "{\"error\":\"Update not found\",\"updateId\":\"" + updateId + "\"}";
    }

    private String buildRollingUpdateHealthResponse(AetherNode node, String updateId) {
        var sb = new StringBuilder();
        node.rollingUpdateManager()
            .getHealthMetrics(updateId)
            .onSuccess(metrics -> {
                           sb.append("{\"updateId\":\"")
                             .append(updateId)
                             .append("\",");
                           sb.append("\"oldVersion\":{");
                           sb.append("\"version\":\"")
                             .append(metrics.oldVersion()
                                            .version())
                             .append("\",");
                           sb.append("\"requestCount\":")
                             .append(metrics.oldVersion()
                                            .requestCount())
                             .append(",");
                           sb.append("\"errorRate\":")
                             .append(metrics.oldVersion()
                                            .errorRate())
                             .append(",");
                           sb.append("\"avgLatencyMs\":")
                             .append(metrics.oldVersion()
                                            .avgLatencyMs());
                           sb.append("},\"newVersion\":{");
                           sb.append("\"version\":\"")
                             .append(metrics.newVersion()
                                            .version())
                             .append("\",");
                           sb.append("\"requestCount\":")
                             .append(metrics.newVersion()
                                            .requestCount())
                             .append(",");
                           sb.append("\"errorRate\":")
                             .append(metrics.newVersion()
                                            .errorRate())
                             .append(",");
                           sb.append("\"avgLatencyMs\":")
                             .append(metrics.newVersion()
                                            .avgLatencyMs());
                           sb.append("},\"collectedAt\":")
                             .append(metrics.collectedAt())
                             .append("}");
                       })
            .onFailure(cause -> sb.append("{\"error\":\"")
                                  .append(cause.message())
                                  .append("\"}"));
        return sb.length() > 0
               ? sb.toString()
               : "{\"error\":\"Unknown error\"}";
    }

    private String buildRollingUpdateJson(org.pragmatica.aether.update.RollingUpdate update) {
        return "{\"updateId\":\"" + update.updateId() + "\"," + "\"artifactBase\":\"" + update.artifactBase()
                                                                                              .asString() + "\","
               + "\"oldVersion\":\"" + update.oldVersion() + "\"," + "\"newVersion\":\"" + update.newVersion() + "\","
               + "\"state\":\"" + update.state()
                                        .name() + "\"," + "\"routing\":\"" + update.routing() + "\","
               + "\"newInstances\":" + update.newInstances() + "," + "\"createdAt\":" + update.createdAt() + ","
               + "\"updatedAt\":" + update.updatedAt() + "}";
    }

    private String buildHealthResponse(AetherNode node) {
        var metrics = node.metricsCollector()
                          .allMetrics();
        var metricsNodeCount = metrics.size();
        var connectedNodeCount = node.connectedNodeCount();
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        // Quorum based on actual network connections (connectedNodeCount + 1 for self)
        // connectedNodeCount() returns peer count (not including self)
        var totalNodes = connectedNodeCount + 1;
        var hasQuorum = totalNodes >= 2;
        // At least 2 nodes for minimal cluster
        // Determine status: healthy if quorum, degraded if no slices but quorum, unhealthy if no quorum
        var status = !hasQuorum
                     ? "unhealthy"
                     : sliceCount == 0
                       ? "degraded"
                       : "healthy";
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"status\":\"")
          .append(status)
          .append("\",");
        sb.append("\"quorum\":")
          .append(hasQuorum)
          .append(",");
        sb.append("\"nodeCount\":")
          .append(totalNodes)
          .append(",");
        sb.append("\"connectedPeers\":")
          .append(connectedNodeCount)
          .append(",");
        sb.append("\"metricsNodeCount\":")
          .append(metricsNodeCount)
          .append(",");
        sb.append("\"sliceCount\":")
          .append(sliceCount);
        sb.append("}");
        return sb.toString();
    }

    private String buildStatusResponse(AetherNode node) {
        var sb = new StringBuilder();
        sb.append("{");
        // Uptime
        var uptimeSeconds = node.uptimeSeconds();
        sb.append("\"uptimeSeconds\":")
          .append(uptimeSeconds)
          .append(",");
        // Cluster info
        sb.append("\"cluster\":{");
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var connectedNodes = allMetrics.keySet();
        var leaderId = node.leader()
                           .map(NodeId::id)
                           .or("none");
        sb.append("\"nodeCount\":")
          .append(connectedNodes.size())
          .append(",");
        sb.append("\"leaderId\":\"")
          .append(leaderId)
          .append("\",");
        sb.append("\"nodes\":[");
        boolean first = true;
        for (NodeId nodeId : connectedNodes) {
            if (!first) sb.append(",");
            var isLeader = node.leader()
                               .map(l -> l.equals(nodeId))
                               .or(false);
            sb.append("{\"id\":\"")
              .append(nodeId.id())
              .append("\",\"isLeader\":")
              .append(isLeader)
              .append("}");
            first = false;
        }
        sb.append("]},");
        // Slice count
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        sb.append("\"sliceCount\":")
          .append(sliceCount)
          .append(",");
        // Metrics summary
        var derived = node.snapshotCollector()
                          .derivedMetrics();
        sb.append("\"metrics\":{");
        sb.append("\"requestsPerSecond\":")
          .append(derived.requestRate())
          .append(",");
        sb.append("\"successRate\":")
          .append(100.0 - derived.errorRate() * 100.0)
          .append(",");
        sb.append("\"avgLatencyMs\":")
          .append(derived.latencyP50());
        sb.append("},");
        // Node info
        sb.append("\"nodeId\":\"")
          .append(node.self()
                      .id())
          .append("\",");
        sb.append("\"status\":\"running\",");
        sb.append("\"isLeader\":")
          .append(node.isLeader())
          .append(",");
        sb.append("\"leader\":\"")
          .append(leaderId)
          .append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String buildNodesResponse(AetherNode node) {
        var metrics = node.metricsCollector()
                          .allMetrics();
        var sb = new StringBuilder();
        sb.append("{\"nodes\":[");
        boolean first = true;
        for (NodeId nodeId : metrics.keySet()) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(nodeId.id())
              .append("\"");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildSlicesResponse(AetherNode node) {
        var slices = node.sliceStore()
                         .loaded();
        var sb = new StringBuilder();
        sb.append("{\"slices\":[");
        boolean first = true;
        for (var slice : slices) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(slice.artifact()
                           .asString())
              .append("\"");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildMetricsResponse(AetherNode node) {
        var sb = new StringBuilder();
        sb.append("{");
        // Load metrics section
        sb.append("\"load\":{");
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        boolean firstNode = true;
        for (var entry : allMetrics.entrySet()) {
            if (!firstNode) sb.append(",");
            sb.append("\"")
              .append(entry.getKey()
                           .id())
              .append("\":{");
            boolean firstMetric = true;
            for (var metric : entry.getValue()
                                   .entrySet()) {
                if (!firstMetric) sb.append(",");
                sb.append("\"")
                  .append(metric.getKey())
                  .append("\":")
                  .append(metric.getValue());
                firstMetric = false;
            }
            sb.append("}");
            firstNode = false;
        }
        sb.append("},");
        // Deployment metrics section
        sb.append("\"deployments\":{");
        var deploymentMetrics = node.deploymentMetricsCollector()
                                    .allDeploymentMetrics();
        boolean firstArtifact = true;
        for (var entry : deploymentMetrics.entrySet()) {
            if (!firstArtifact) sb.append(",");
            sb.append("\"")
              .append(entry.getKey()
                           .asString())
              .append("\":[");
            boolean firstDeployment = true;
            for (var metrics : entry.getValue()) {
                if (!firstDeployment) sb.append(",");
                sb.append("{");
                sb.append("\"nodeId\":\"")
                  .append(metrics.nodeId()
                                 .id())
                  .append("\",");
                sb.append("\"status\":\"")
                  .append(metrics.status()
                                 .name())
                  .append("\",");
                sb.append("\"fullDeploymentMs\":")
                  .append(metrics.fullDeploymentTime())
                  .append(",");
                sb.append("\"netDeploymentMs\":")
                  .append(metrics.netDeploymentTime())
                  .append(",");
                sb.append("\"transitions\":{");
                var latencies = metrics.transitionLatencies();
                boolean firstLatency = true;
                for (var latency : latencies.entrySet()) {
                    if (!firstLatency) sb.append(",");
                    sb.append("\"")
                      .append(latency.getKey())
                      .append("\":")
                      .append(latency.getValue());
                    firstLatency = false;
                }
                sb.append("},");
                sb.append("\"startTime\":")
                  .append(metrics.startTime())
                  .append(",");
                sb.append("\"activeTime\":")
                  .append(metrics.activeTime());
                sb.append("}");
                firstDeployment = false;
            }
            sb.append("]");
            firstArtifact = false;
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private String buildComprehensiveMetricsResponse(AetherNode node) {
        var recent = node.snapshotCollector()
                         .minuteAggregator()
                         .recent(1);
        if (recent.isEmpty()) {
            return "{\"error\":\"No comprehensive metrics available yet\"}";
        }
        var agg = recent.get(0);
        return "{" + "\"minuteTimestamp\":" + agg.minuteTimestamp() + "," + "\"avgCpuUsage\":" + agg.avgCpuUsage()
               + "," + "\"avgHeapUsage\":" + agg.avgHeapUsage() + "," + "\"avgEventLoopLagMs\":" + agg.avgEventLoopLagMs()
               + "," + "\"avgLatencyMs\":" + agg.avgLatencyMs() + "," + "\"totalInvocations\":" + agg.totalInvocations()
               + "," + "\"totalGcPauseMs\":" + agg.totalGcPauseMs() + "," + "\"latencyP50\":" + agg.latencyP50() + ","
               + "\"latencyP95\":" + agg.latencyP95() + "," + "\"latencyP99\":" + agg.latencyP99() + ","
               + "\"errorRate\":" + agg.errorRate() + "," + "\"eventCount\":" + agg.eventCount() + ","
               + "\"sampleCount\":" + agg.sampleCount() + "}";
    }

    private String buildDerivedMetricsResponse(AetherNode node) {
        var derived = node.snapshotCollector()
                          .derivedMetrics();
        return "{" + "\"requestRate\":" + derived.requestRate() + "," + "\"errorRate\":" + derived.errorRate() + ","
               + "\"gcRate\":" + derived.gcRate() + "," + "\"latencyP50\":" + derived.latencyP50() + ","
               + "\"latencyP95\":" + derived.latencyP95() + "," + "\"latencyP99\":" + derived.latencyP99() + ","
               + "\"eventLoopSaturation\":" + derived.eventLoopSaturation() + "," + "\"heapSaturation\":" + derived.heapSaturation()
               + "," + "\"cpuTrend\":" + derived.cpuTrend() + "," + "\"latencyTrend\":" + derived.latencyTrend() + ","
               + "\"errorTrend\":" + derived.errorTrend() + "," + "\"healthScore\":" + derived.healthScore() + ","
               + "\"stressed\":" + derived.stressed() + "," + "\"hasCapacity\":" + derived.hasCapacity() + "}";
    }

    private String buildSlicesStatusResponse(AetherNode node) {
        // Query KV-Store for all slice entries across the cluster
        Map<Artifact, java.util.List<SliceInstance>> slicesByArtifact = new HashMap<>();
        node.kvStore()
            .snapshot()
            .forEach((key, value) -> collectSliceInstance(slicesByArtifact, key, value));
        var sb = new StringBuilder();
        sb.append("{\"slices\":[");
        boolean first = true;
        for (var entry : slicesByArtifact.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            var artifact = entry.getKey();
            var instances = entry.getValue();
            // Determine aggregate state (ACTIVE if any instance is ACTIVE)
            var aggregateState = instances.stream()
                                          .map(SliceInstance::state)
                                          .filter(s -> s == SliceState.ACTIVE)
                                          .findAny()
                                          .orElse(instances.isEmpty()
                                                  ? SliceState.FAILED
                                                  : instances.getFirst()
                                                             .state());
            sb.append("{\"artifact\":\"")
              .append(artifact.asString())
              .append("\",\"state\":\"")
              .append(aggregateState.name())
              .append("\",\"instances\":[");
            boolean firstInstance = true;
            for (var instance : instances) {
                if (!firstInstance) sb.append(",");
                firstInstance = false;
                var health = instance.state() == SliceState.ACTIVE
                             ? "HEALTHY"
                             : "UNHEALTHY";
                sb.append("{\"nodeId\":\"")
                  .append(instance.nodeId()
                                  .id())
                  .append("\",\"state\":\"")
                  .append(instance.state()
                                  .name())
                  .append("\",\"health\":\"")
                  .append(health)
                  .append("\"}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private record SliceInstance(NodeId nodeId, SliceState state) {}

    private void collectSliceInstance(Map<Artifact, java.util.List<SliceInstance>> map,
                                      AetherKey key,
                                      AetherValue value) {
        if (key instanceof SliceNodeKey sliceKey && value instanceof SliceNodeValue sliceValue) {
            map.computeIfAbsent(sliceKey.artifact(),
                                _ -> new ArrayList<>())
               .add(new SliceInstance(sliceKey.nodeId(),
                                      sliceValue.state()));
        }
    }

    private String buildInvocationMetricsResponse(AetherNode node,
                                                  Option<String> artifactFilter,
                                                  Option<String> methodFilter) {
        var snapshots = node.invocationMetrics()
                            .snapshot();
        var sb = new StringBuilder();
        sb.append("{\"snapshots\":[");
        boolean first = true;
        for (var snapshot : snapshots) {
            // Apply artifact filter (partial match) - skip if filter present and doesn't match
            var skipByArtifact = artifactFilter.map(filter -> !snapshot.artifact()
                                                                       .asString()
                                                                       .contains(filter))
                                               .or(false);
            if (skipByArtifact) {
                continue;
            }
            // Apply method filter (exact match) - skip if filter present and doesn't match
            var skipByMethod = methodFilter.map(filter -> !snapshot.methodName()
                                                                   .name()
                                                                   .equals(filter))
                                           .or(false);
            if (skipByMethod) {
                continue;
            }
            if (!first) sb.append(",");
            sb.append("{\"artifact\":\"")
              .append(snapshot.artifact()
                              .asString())
              .append("\",");
            sb.append("\"method\":\"")
              .append(snapshot.methodName()
                              .name())
              .append("\",");
            sb.append("\"count\":")
              .append(snapshot.metrics()
                              .count())
              .append(",");
            sb.append("\"successCount\":")
              .append(snapshot.metrics()
                              .successCount())
              .append(",");
            sb.append("\"failureCount\":")
              .append(snapshot.metrics()
                              .failureCount())
              .append(",");
            sb.append("\"totalDurationNs\":")
              .append(snapshot.metrics()
                              .totalDurationNs())
              .append(",");
            // Estimate min/max from histogram (p5 and p95)
            sb.append("\"p50DurationNs\":")
              .append(snapshot.metrics()
                              .estimatePercentileNs(50))
              .append(",");
            sb.append("\"p95DurationNs\":")
              .append(snapshot.metrics()
                              .estimatePercentileNs(95))
              .append(",");
            sb.append("\"avgDurationMs\":")
              .append(snapshot.metrics()
                              .count() > 0
                      ? snapshot.metrics()
                                .totalDurationNs() / snapshot.metrics()
                                                            .count() / 1_000_000.0
                      : 0)
              .append(",");
            sb.append("\"slowInvocations\":")
              .append(snapshot.slowInvocations()
                              .size());
            sb.append("}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildSlowInvocationsResponse(AetherNode node) {
        var snapshots = node.invocationMetrics()
                            .snapshot();
        var sb = new StringBuilder();
        sb.append("{\"slowInvocations\":[");
        boolean first = true;
        for (var snapshot : snapshots) {
            for (var slow : snapshot.slowInvocations()) {
                if (!first) sb.append(",");
                sb.append("{\"artifact\":\"")
                  .append(snapshot.artifact()
                                  .asString())
                  .append("\",");
                sb.append("\"method\":\"")
                  .append(snapshot.methodName()
                                  .name())
                  .append("\",");
                sb.append("\"durationNs\":")
                  .append(slow.durationNs())
                  .append(",");
                sb.append("\"durationMs\":")
                  .append(slow.durationMs())
                  .append(",");
                sb.append("\"timestampNs\":")
                  .append(slow.timestampNs())
                  .append(",");
                sb.append("\"success\":")
                  .append(slow.success())
                  .append(",");
                sb.append("\"error\":")
                  .append(slow.errorType()
                              .map(err -> "\"" + err.replace("\"", "\\\"") + "\"")
                              .or("null"));
                sb.append("}");
                first = false;
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildStrategyResponse(AetherNode node) {
        var strategy = node.invocationMetrics()
                           .thresholdStrategy();
        return switch (strategy) {
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.Fixed f ->
            "{\"type\":\"fixed\",\"thresholdMs\":" + (f.thresholdNs() / 1_000_000) + "}";
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.Adaptive a ->
            "{\"type\":\"adaptive\",\"minMs\":" + (a.minThresholdNs() / 1_000_000) + ",\"maxMs\":" + (a.maxThresholdNs() / 1_000_000)
            + ",\"multiplier\":" + a.multiplier() + "}";
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.PerMethod p ->
            "{\"type\":\"perMethod\",\"defaultMs\":" + (p.defaultThresholdNs() / 1_000_000) + "}";
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.Composite c ->
            "{\"type\":\"composite\"}";
        };
    }

    private String buildControllerConfigResponse(AetherNode node) {
        return node.controller()
                   .getConfiguration()
                   .toJson();
    }

    private String buildControllerStatusResponse(AetherNode node) {
        var config = node.controller()
                         .getConfiguration();
        return "{\"enabled\":true," + "\"evaluationIntervalMs\":" + config.evaluationIntervalMs() + "," + "\"config\":" + config.toJson()
               + "}";
    }

    private String buildTtmStatusResponse(AetherNode node) {
        var ttm = node.ttmManager();
        var config = ttm.config();
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"configEnabled\":")
          .append(config.enabled())
          .append(",");
        sb.append("\"active\":")
          .append(ttm.isEnabled())
          .append(",");
        sb.append("\"state\":\"")
          .append(ttm.state()
                     .name())
          .append("\",");
        sb.append("\"modelPath\":\"")
          .append(config.modelPath())
          .append("\",");
        sb.append("\"inputWindowMinutes\":")
          .append(config.inputWindowMinutes())
          .append(",");
        sb.append("\"evaluationIntervalMs\":")
          .append(config.evaluationIntervalMs())
          .append(",");
        sb.append("\"confidenceThreshold\":")
          .append(config.confidenceThreshold())
          .append(",");
        sb.append("\"hasForecast\":")
          .append(ttm.currentForecast()
                     .isPresent());
        ttm.currentForecast()
           .onPresent(forecast -> {
                          sb.append(",\"lastForecast\":{");
                          sb.append("\"timestamp\":")
                            .append(forecast.timestamp())
                            .append(",");
                          sb.append("\"confidence\":")
                            .append(forecast.confidence())
                            .append(",");
                          sb.append("\"recommendation\":\"")
                            .append(forecast.recommendation()
                                            .getClass()
                                            .getSimpleName())
                            .append("\"}");
                      });
        sb.append("}");
        return sb.toString();
    }

    private void handleControllerConfig(ChannelHandlerContext ctx, AetherNode node, String body) {
        try{
            var cpuUpMatch = Pattern.compile("\"cpuScaleUpThreshold\"\\s*:\\s*([0-9.]+)")
                                    .matcher(body);
            var cpuDownMatch = Pattern.compile("\"cpuScaleDownThreshold\"\\s*:\\s*([0-9.]+)")
                                      .matcher(body);
            var callRateMatch = Pattern.compile("\"callRateScaleUpThreshold\"\\s*:\\s*([0-9.]+)")
                                       .matcher(body);
            var intervalMatch = Pattern.compile("\"evaluationIntervalMs\"\\s*:\\s*(\\d+)")
                                       .matcher(body);
            var currentConfig = node.controller()
                                    .getConfiguration();
            var newConfig = org.pragmatica.aether.controller.ControllerConfig.controllerConfig(cpuUpMatch.find()
                                                                                               ? Double.parseDouble(cpuUpMatch.group(1))
                                                                                               : currentConfig.cpuScaleUpThreshold(),
                                                                                               cpuDownMatch.find()
                                                                                               ? Double.parseDouble(cpuDownMatch.group(1))
                                                                                               : currentConfig.cpuScaleDownThreshold(),
                                                                                               callRateMatch.find()
                                                                                               ? Double.parseDouble(callRateMatch.group(1))
                                                                                               : currentConfig.callRateScaleUpThreshold(),
                                                                                               intervalMatch.find()
                                                                                               ? Long.parseLong(intervalMatch.group(1))
                                                                                               : currentConfig.evaluationIntervalMs());
            node.controller()
                .updateConfiguration(newConfig);
            sendJson(ctx, "{\"status\":\"updated\",\"config\":" + newConfig.toJson() + "}");
        } catch (Exception e) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void handleControllerEvaluate(ChannelHandlerContext ctx, AetherNode node) {
        sendJson(ctx, "{\"status\":\"evaluation_triggered\"}");
    }

    private void handleSetThreshold(ChannelHandlerContext ctx, String body) {
        try{
            var metricMatch = Pattern.compile("\"metric\"\\s*:\\s*\"([^\"]+)\"")
                                     .matcher(body);
            var warningMatch = Pattern.compile("\"warning\"\\s*:\\s*([0-9.]+)")
                                      .matcher(body);
            var criticalMatch = Pattern.compile("\"critical\"\\s*:\\s*([0-9.]+)")
                                       .matcher(body);
            if (!metricMatch.find() || !warningMatch.find() || !criticalMatch.find()) {
                sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing metric, warning, or critical field");
                return;
            }
            var metric = metricMatch.group(1);
            var warning = Double.parseDouble(warningMatch.group(1));
            var critical = Double.parseDouble(criticalMatch.group(1));
            alertManager.setThreshold(metric, warning, critical)
                        .onSuccess(_ -> sendJson(ctx,
                                                 "{\"status\":\"threshold_set\",\"metric\":\"" + metric
                                                 + "\",\"warning\":" + warning + ",\"critical\":" + critical + "}"))
                        .onFailure(cause -> sendJsonError(ctx,
                                                          HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                          "Failed to persist threshold: " + cause.message()));
        } catch (Exception e) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void handleClearAlerts(ChannelHandlerContext ctx) {
        alertManager.clearAlerts();
        sendJson(ctx, "{\"status\":\"alerts_cleared\"}");
    }

    private static final Pattern STRATEGY_TYPE_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern THRESHOLD_MS_PATTERN = Pattern.compile("\"thresholdMs\"\\s*:\\s*(\\d+)");
    private static final Pattern MIN_MS_PATTERN = Pattern.compile("\"minMs\"\\s*:\\s*(\\d+)");
    private static final Pattern MAX_MS_PATTERN = Pattern.compile("\"maxMs\"\\s*:\\s*(\\d+)");

    private void handleSetStrategy(ChannelHandlerContext ctx, AetherNode node, String body) {
        var typeMatch = STRATEGY_TYPE_PATTERN.matcher(body);
        if (!typeMatch.find()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing 'type' field");
            return;
        }
        var type = typeMatch.group(1);
        // Note: Runtime strategy change is not currently supported by InvocationMetricsCollector
        // Return current strategy with a message that runtime change is not supported
        sendJsonError(ctx,
                      HttpResponseStatus.NOT_IMPLEMENTED,
                      "Runtime strategy change not supported. Strategy must be set at node startup.");
    }

    private String buildNodeMetricsResponse(AetherNode node) {
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var entry : allMetrics.entrySet()) {
            if (!first) sb.append(",");
            var nodeId = entry.getKey();
            var metrics = entry.getValue();
            sb.append("{\"nodeId\":\"")
              .append(nodeId.id())
              .append("\",");
            sb.append("\"cpuUsage\":")
              .append(metrics.getOrDefault("cpuUsage", 0.0))
              .append(",");
            sb.append("\"heapUsedMb\":")
              .append(metrics.getOrDefault("heapUsedMb", 0.0)
                             .longValue())
              .append(",");
            sb.append("\"heapMaxMb\":")
              .append(metrics.getOrDefault("heapMaxMb", 0.0)
                             .longValue());
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildAlertsResponse() {
        return "{\"active\":" + alertManager.activeAlertsAsJson() + ",\"history\":" + alertManager.alertHistoryAsJson()
               + "}";
    }

    private void sendJson(ChannelHandlerContext ctx, String content) {
        var buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON);
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        buf.readableBytes());
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private void sendPrometheus(ChannelHandlerContext ctx, String content) {
        var buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_PROMETHEUS);
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        buf.readableBytes());
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private void sendText(ChannelHandlerContext ctx, String content) {
        var buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_HTML);
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        buf.readableBytes());
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        var content = "{\"error\":\"" + status.reasonPhrase() + "\"}";
        var buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON);
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        buf.readableBytes());
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Check if node is leader. Returns true if leader, sends error and returns false otherwise.
     */
    private boolean requireLeader(ChannelHandlerContext ctx, AetherNode node) {
        if (!node.isLeader()) {
            var leaderInfo = node.leader()
                                 .map(id -> " Current leader: " + id.id())
                                 .or("");
            sendJsonError(ctx,
                          HttpResponseStatus.SERVICE_UNAVAILABLE,
                          "This operation requires the leader node." + leaderInfo);
            return false;
        }
        return true;
    }

    private void sendJsonError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        var escapedMessage = message.replace("\"", "\\\"")
                                    .replace("\n", "\\n");
        var content = "{\"error\":\"" + escapedMessage + "\"}";
        var buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON);
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH,
                        buf.readableBytes());
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error handling HTTP request", cause);
        ctx.close();
    }
}
