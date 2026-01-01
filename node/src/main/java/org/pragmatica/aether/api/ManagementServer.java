package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.TlsContextFactory;

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

    static ManagementServer managementServer(int port, Supplier<AetherNode> nodeSupplier) {
        return managementServer(port, nodeSupplier, Option.empty());
    }

    static ManagementServer managementServer(int port, Supplier<AetherNode> nodeSupplier, Option<TlsConfig> tls) {
        return new ManagementServerImpl(port, nodeSupplier, tls);
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

    ManagementServerImpl(int port, Supplier<AetherNode> nodeSupplier, Option<TlsConfig> tls) {
        this.port = port;
        this.nodeSupplier = nodeSupplier;
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.alertManager = new AlertManager();
        this.metricsPublisher = new DashboardMetricsPublisher(nodeSupplier, alertManager);
        this.observability = ObservabilityRegistry.prometheus();
        this.sslContext = tls.map(TlsContextFactory::create)
                             .flatMap(result -> result.fold(_ -> Option.empty(),
                                                            Option::some));
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
                                                         }else {
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
                                                .addListener(f -> {
                                                                 bossGroup.shutdownGracefully();
                                                                 workerGroup.shutdownGracefully();
                                                                 log.info("Management server stopped");
                                                                 promise.succeed(unit());
                                                             });
                               }else {
                                   bossGroup.shutdownGracefully();
                                   workerGroup.shutdownGracefully();
                                   promise.succeed(unit());
                               }
                               });
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
        }else if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            // Handle both POST and PUT for repository uploads (Maven uses PUT)
            handlePost(ctx, uri, request);
        }else {
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
        // Handle repository requests
        if (uri.startsWith("/repository/")) {
            handleRepositoryGet(ctx, node, uri);
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
        var response = switch (uri) {
            case"/status" -> buildStatusResponse(node);
            case"/nodes" -> buildNodesResponse(node);
            case"/slices" -> buildSlicesResponse(node);
            case"/slices/status" -> buildSlicesStatusResponse(node);
            case"/metrics" -> buildMetricsResponse(node);
            case"/invocation-metrics" -> buildInvocationMetricsResponse(node);
            case"/thresholds" -> alertManager.thresholdsAsJson();
            case"/alerts" -> buildAlertsResponse();
            case"/health" -> buildHealthResponse(node);
            default -> null;
        };
        if (response != null) {
            sendJson(ctx, response);
        }else {
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
                           var httpResponse = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
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
        // Handle repository PUT requests (binary content)
        if (uri.startsWith("/repository/")) {
            handleRepositoryPut(ctx, node, uri, request);
            return;
        }
        // For other endpoints, read as string
        var body = request.content()
                          .toString(CharsetUtil.UTF_8);
        // Handle rolling update POST paths
        if (uri.startsWith("/rolling-update")) {
            handleRollingUpdatePost(ctx, node, uri, body);
            return;
        }
        switch (uri) {
            case"/deploy" -> handleDeploy(ctx, node, body);
            case"/scale" -> handleScale(ctx, node, body);
            case"/undeploy" -> handleUndeploy(ctx, node, body);
            case"/blueprint" -> handleBlueprint(ctx, node, body);
            default -> sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
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
                           var httpResponse = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
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
        }else if (uri.startsWith("/rolling-update/") && uri.endsWith("/health")) {
            // GET /rolling-update/{id}/health
            var updateId = extractUpdateId(uri, "/health");
            sendJson(ctx, buildRollingUpdateHealthResponse(node, updateId));
        }else if (uri.startsWith("/rolling-update/")) {
            // GET /rolling-update/{id}
            var updateId = uri.substring("/rolling-update/".length());
            sendJson(ctx, buildRollingUpdateResponse(node, updateId));
        }else {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private void handleRollingUpdatePost(ChannelHandlerContext ctx, AetherNode node, String uri, String body) {
        if (uri.equals("/rolling-update/start")) {
            handleRollingUpdateStart(ctx, node, body);
        }else if (uri.endsWith("/routing")) {
            var updateId = extractUpdateId(uri, "/routing");
            handleRollingUpdateRouting(ctx, node, updateId, body);
        }else if (uri.endsWith("/approve")) {
            var updateId = extractUpdateId(uri, "/approve");
            handleRollingUpdateApprove(ctx, node, updateId);
        }else if (uri.endsWith("/complete")) {
            var updateId = extractUpdateId(uri, "/complete");
            handleRollingUpdateComplete(ctx, node, updateId);
        }else if (uri.endsWith("/rollback")) {
            var updateId = extractUpdateId(uri, "/rollback");
            handleRollingUpdateRollback(ctx, node, updateId);
        }else {
            sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private String extractUpdateId(String uri, String suffix) {
        var path = uri.substring("/rolling-update/".length());
        return path.substring(0,
                              path.length() - suffix.length());
    }

    private void handleRollingUpdateStart(ChannelHandlerContext ctx, AetherNode node, String body) {
        // Parse: {"artifact": "group:artifact", "version": "1.0.0", "instances": 3, ...}
        // For now, return placeholder response since RollingUpdateManager implementation is pending
        sendJson(ctx,
                 "{\"status\":\"not_implemented\",\"message\":\"Rolling update start requires RollingUpdateManager implementation\"}");
    }

    private void handleRollingUpdateRouting(ChannelHandlerContext ctx, AetherNode node, String updateId, String body) {
        // Parse: {"newWeight": 1, "oldWeight": 3}
        sendJson(ctx, "{\"status\":\"not_implemented\",\"updateId\":\"" + updateId + "\"}");
    }

    private void handleRollingUpdateApprove(ChannelHandlerContext ctx, AetherNode node, String updateId) {
        sendJson(ctx, "{\"status\":\"not_implemented\",\"updateId\":\"" + updateId + "\"}");
    }

    private void handleRollingUpdateComplete(ChannelHandlerContext ctx, AetherNode node, String updateId) {
        sendJson(ctx, "{\"status\":\"not_implemented\",\"updateId\":\"" + updateId + "\"}");
    }

    private void handleRollingUpdateRollback(ChannelHandlerContext ctx, AetherNode node, String updateId) {
        sendJson(ctx, "{\"status\":\"not_implemented\",\"updateId\":\"" + updateId + "\"}");
    }

    private String buildRollingUpdatesResponse(AetherNode node) {
        // Return list of active rolling updates
        return "{\"updates\":[],\"message\":\"Rolling update listing requires RollingUpdateManager implementation\"}";
    }

    private String buildRollingUpdateResponse(AetherNode node, String updateId) {
        return "{\"updateId\":\"" + updateId + "\",\"status\":\"not_found\"}";
    }

    private String buildRollingUpdateHealthResponse(AetherNode node, String updateId) {
        return "{\"updateId\":\"" + updateId + "\",\"health\":\"unknown\"}";
    }

    private String buildHealthResponse(AetherNode node) {
        var metrics = node.metricsCollector()
                          .allMetrics();
        var nodeCount = metrics.size();
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        // Quorum: we have metrics from at least half the expected nodes
        // Use nodeCount > 1 as a simple proxy for "cluster is functioning"
        var hasQuorum = nodeCount >= 1;
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
          .append(nodeCount)
          .append(",");
        sb.append("\"sliceCount\":")
          .append(sliceCount);
        sb.append("}");
        return sb.toString();
    }

    private String buildStatusResponse(AetherNode node) {
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"nodeId\":\"")
          .append(node.self()
                      .id())
          .append("\",");
        sb.append("\"status\":\"running\"");
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

    private String buildSlicesStatusResponse(AetherNode node) {
        var sb = new StringBuilder();
        sb.append("{\"slices\":[");
        var slices = node.sliceStore()
                         .loaded();
        boolean first = true;
        for (var slice : slices) {
            if (!first) sb.append(",");
            sb.append("{");
            sb.append("\"artifact\":\"")
              .append(slice.artifact()
                           .asString())
              .append("\",");
            sb.append("\"state\":\"ACTIVE\",");
            // TODO: get actual state from kvstore
            sb.append("\"instances\":[{");
            sb.append("\"nodeId\":\"")
              .append(node.self()
                          .id())
              .append("\",");
            sb.append("\"state\":\"ACTIVE\",");
            sb.append("\"health\":\"HEALTHY\"");
            sb.append("}]");
            sb.append("}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildInvocationMetricsResponse(AetherNode node) {
        // TODO: expose invocationMetricsCollector via AetherNode interface
        return "{\"snapshots\":[]}";
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
