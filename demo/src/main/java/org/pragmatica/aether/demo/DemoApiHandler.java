package org.pragmatica.aether.demo;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.pragmatica.aether.demo.DemoCluster.ClusterStatus;
import org.pragmatica.aether.demo.DemoMetrics.MetricsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handles REST API requests for the demo dashboard.
 * Provides endpoints for cluster status, node management, and load control.
 */
@Sharable
public final class DemoApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(DemoApiHandler.class);

    private final DemoCluster cluster;
    private final LoadGenerator loadGenerator;
    private final DemoMetrics metrics;
    private final List<DemoEvent> events = new CopyOnWriteArrayList<>();
    private final long startTime = System.currentTimeMillis();

    private DemoApiHandler(DemoCluster cluster, LoadGenerator loadGenerator, DemoMetrics metrics) {
        this.cluster = cluster;
        this.loadGenerator = loadGenerator;
        this.metrics = metrics;
    }

    public static DemoApiHandler demoApiHandler(DemoCluster cluster, LoadGenerator loadGenerator, DemoMetrics metrics) {
        return new DemoApiHandler(cluster, loadGenerator, metrics);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        var path = request.uri();
        var method = request.method();

        log.debug("API request: {} {}", method, path);

        try {
            if (path.equals("/api/status") && method == HttpMethod.GET) {
                handleStatus(ctx);
            } else if (path.equals("/api/add-node") && method == HttpMethod.POST) {
                handleAddNode(ctx);
            } else if (path.startsWith("/api/kill/") && method == HttpMethod.POST) {
                handleKillNode(ctx, path.substring("/api/kill/".length()));
            } else if (path.startsWith("/api/crash/") && method == HttpMethod.POST) {
                handleCrashNode(ctx, path.substring("/api/crash/".length()));
            } else if (path.equals("/api/rolling-restart") && method == HttpMethod.POST) {
                handleRollingRestart(ctx);
            } else if (path.startsWith("/api/load/set/") && method == HttpMethod.POST) {
                handleSetLoad(ctx, path.substring("/api/load/set/".length()));
            } else if (path.equals("/api/load/ramp") && method == HttpMethod.POST) {
                handleRampLoad(ctx, request);
            } else if (path.equals("/api/events") && method == HttpMethod.GET) {
                handleEvents(ctx);
            } else if (path.equals("/api/reset-metrics") && method == HttpMethod.POST) {
                handleResetMetrics(ctx);
            } else {
                sendResponse(ctx, NOT_FOUND, "{\"error\": \"Not found\"}");
            }
        } catch (Exception e) {
            log.error("Error handling API request: {}", e.getMessage(), e);
            sendResponse(ctx, INTERNAL_SERVER_ERROR, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleStatus(ChannelHandlerContext ctx) {
        var clusterStatus = cluster.status();
        var metricsSnapshot = metrics.currentMetrics();
        var loadStatus = new LoadStatus(
                loadGenerator.currentRate(),
                loadGenerator.targetRate(),
                loadGenerator.isRunning()
        );

        var response = new StatusResponse(clusterStatus, metricsSnapshot, loadStatus, uptimeSeconds());
        sendResponse(ctx, OK, toJson(response));
    }

    private void handleAddNode(ChannelHandlerContext ctx) {
        addEvent("ADD_NODE", "Adding new node to cluster");

        cluster.addNode()
               .onSuccess(nodeId -> {
                   addEvent("NODE_JOINED", "Node " + nodeId.id() + " joined the cluster");
                   sendResponse(ctx, OK, "{\"success\": true, \"nodeId\": \"" + nodeId.id() + "\", \"state\": \"joining\"}");
               })
               .onFailure(cause -> {
                   addEvent("ADD_NODE_FAILED", "Failed to add node: " + cause.message());
                   sendResponse(ctx, INTERNAL_SERVER_ERROR, "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
               });
    }

    private void handleKillNode(ChannelHandlerContext ctx, String nodeId) {
        var wasLeader = cluster.currentLeader().fold(() -> false, l -> l.equals(nodeId));

        addEvent("KILL_NODE", "Killing node " + nodeId + (wasLeader ? " (leader)" : ""));

        cluster.killNode(nodeId)
               .onSuccess(_ -> {
                   var newLeader = cluster.currentLeader().fold(() -> "none", l -> l);
                   addEvent("NODE_KILLED", "Node " + nodeId + " killed" + (wasLeader ? ", new leader: " + newLeader : ""));
                   sendResponse(ctx, OK, "{\"success\": true, \"newLeader\": \"" + newLeader + "\"}");
               })
               .onFailure(cause -> {
                   addEvent("KILL_FAILED", "Failed to kill node " + nodeId);
                   sendResponse(ctx, INTERNAL_SERVER_ERROR, "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
               });
    }

    private void handleCrashNode(ChannelHandlerContext ctx, String nodeId) {
        var wasLeader = cluster.currentLeader().fold(() -> false, l -> l.equals(nodeId));

        addEvent("CRASH_NODE", "Crashing node " + nodeId + " abruptly" + (wasLeader ? " (leader)" : ""));

        cluster.crashNode(nodeId)
               .onSuccess(_ -> {
                   var newLeader = cluster.currentLeader().fold(() -> "none", l -> l);
                   addEvent("NODE_CRASHED", "Node " + nodeId + " crashed");
                   sendResponse(ctx, OK, "{\"success\": true, \"newLeader\": \"" + newLeader + "\"}");
               })
               .onFailure(cause -> {
                   addEvent("CRASH_FAILED", "Failed to crash node " + nodeId);
                   sendResponse(ctx, INTERNAL_SERVER_ERROR, "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
               });
    }

    private void handleRollingRestart(ChannelHandlerContext ctx) {
        addEvent("ROLLING_RESTART", "Starting rolling restart of all nodes");

        cluster.rollingRestart()
               .onSuccess(_ -> {
                   addEvent("ROLLING_RESTART_COMPLETE", "Rolling restart completed successfully");
                   sendResponse(ctx, OK, "{\"success\": true}");
               })
               .onFailure(cause -> {
                   addEvent("ROLLING_RESTART_FAILED", "Rolling restart failed: " + cause.message());
                   sendResponse(ctx, INTERNAL_SERVER_ERROR, "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
               });
    }

    private void handleSetLoad(ChannelHandlerContext ctx, String rateStr) {
        try {
            var rate = Integer.parseInt(rateStr);
            loadGenerator.setRate(rate);
            addEvent("LOAD_SET", "Load set to " + rate + " req/sec");
            sendResponse(ctx, OK, "{\"success\": true, \"newRate\": " + rate + "}");
        } catch (NumberFormatException e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\": false, \"error\": \"Invalid rate\"}");
        }
    }

    private void handleRampLoad(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);
            // Simple parsing - expect {"targetRate": 5000, "durationMs": 30000}
            var targetRate = extractInt(body, "targetRate", 5000);
            var durationMs = extractLong(body, "durationMs", 30000);

            loadGenerator.rampUp(targetRate, durationMs);
            addEvent("LOAD_RAMP", "Ramping load to " + targetRate + " req/sec over " + (durationMs / 1000) + "s");
            sendResponse(ctx, OK, "{\"success\": true, \"targetRate\": " + targetRate + ", \"durationMs\": " + durationMs + "}");
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleEvents(ChannelHandlerContext ctx) {
        var eventsJson = new StringBuilder("[");
        var first = true;
        for (var event : events) {
            if (!first) eventsJson.append(",");
            first = false;
            eventsJson.append("{")
                      .append("\"timestamp\":\"").append(event.timestamp).append("\",")
                      .append("\"type\":\"").append(event.type).append("\",")
                      .append("\"message\":\"").append(escapeJson(event.message)).append("\"")
                      .append("}");
        }
        eventsJson.append("]");
        sendResponse(ctx, OK, eventsJson.toString());
    }

    private void handleResetMetrics(ChannelHandlerContext ctx) {
        metrics.reset();
        events.clear();
        addEvent("RESET", "Metrics and events reset");
        sendResponse(ctx, OK, "{\"success\": true}");
    }

    public void addEvent(String type, String message) {
        var event = new DemoEvent(Instant.now().toString(), type, message);
        events.add(event);

        // Keep only last 100 events
        while (events.size() > 100) {
            events.removeFirst();
        }

        log.info("[EVENT] {}: {}", type, message);
    }

    private long uptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        var content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String toJson(StatusResponse response) {
        var nodes = new StringBuilder("[");
        var first = true;
        for (var node : response.cluster.nodes()) {
            if (!first) nodes.append(",");
            first = false;
            nodes.append("{")
                 .append("\"id\":\"").append(node.id()).append("\",")
                 .append("\"port\":").append(node.port()).append(",")
                 .append("\"state\":\"").append(node.state()).append("\",")
                 .append("\"isLeader\":").append(node.isLeader())
                 .append("}");
        }
        nodes.append("]");

        return "{" +
               "\"cluster\":{" +
               "\"nodes\":" + nodes + "," +
               "\"leaderId\":\"" + response.cluster.leaderId() + "\"," +
               "\"nodeCount\":" + response.cluster.nodes().size() +
               "}," +
               "\"metrics\":{" +
               "\"requestsPerSecond\":" + String.format("%.1f", response.metrics.requestsPerSecond()) + "," +
               "\"successRate\":" + String.format("%.2f", response.metrics.successRate()) + "," +
               "\"avgLatencyMs\":" + String.format("%.2f", response.metrics.avgLatencyMs()) + "," +
               "\"totalSuccess\":" + response.metrics.totalSuccess() + "," +
               "\"totalFailures\":" + response.metrics.totalFailures() +
               "}," +
               "\"load\":{" +
               "\"currentRate\":" + response.load.currentRate + "," +
               "\"targetRate\":" + response.load.targetRate + "," +
               "\"running\":" + response.load.running +
               "}," +
               "\"uptimeSeconds\":" + response.uptimeSeconds +
               "}";
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    private int extractInt(String json, String key, int defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return defaultValue;
    }

    private long extractLong(String json, String key, long defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return defaultValue;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in API handler: {}", cause.getMessage());
        ctx.close();
    }

    private record StatusResponse(ClusterStatus cluster, MetricsSnapshot metrics, LoadStatus load, long uptimeSeconds) {}
    private record LoadStatus(int currentRate, int targetRate, boolean running) {}
    private record DemoEvent(String timestamp, String type, String message) {}
}
