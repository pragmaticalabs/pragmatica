package org.pragmatica.aether.forge;

import org.pragmatica.aether.forge.ForgeCluster.ClusterStatus;
import org.pragmatica.aether.forge.ForgeMetrics.MetricsSnapshot;
import org.pragmatica.aether.forge.simulator.BackendSimulation;
import org.pragmatica.aether.forge.simulator.ChaosController;
import org.pragmatica.aether.forge.simulator.ChaosEvent;
import org.pragmatica.aether.forge.simulator.SimulatorConfig;
import org.pragmatica.aether.forge.simulator.SimulatorMode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.aether.forge.simulator.DataGenerator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handles REST API requests for the Forge dashboard.
 * Provides endpoints for cluster status, node management, and load control.
 */
@Sharable
public final class ForgeApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(ForgeApiHandler.class);

    private final ForgeCluster cluster;
    private final LoadGenerator loadGenerator;
    private final ForgeMetrics metrics;
    private final LocalSliceInvoker sliceInvoker;
    private final ChaosController chaosController;
    private static final int MAX_EVENTS = 100;
    private final Deque<ForgeEvent> events = new ConcurrentLinkedDeque<>();
    private final long startTime = System.currentTimeMillis();
    private final Object modeLock = new Object();
    private volatile SimulatorConfig config = SimulatorConfig.defaultConfig();
    private volatile SimulatorMode currentMode = SimulatorMode.DEVELOPMENT;

    // Simulated inventory state (for standalone mode without real slices)
    private volatile boolean infiniteInventoryMode = true;
    private volatile long simulatedReservations = 0;
    private volatile long simulatedReleases = 0;
    private volatile long simulatedStockOuts = 0;

    private ForgeApiHandler(ForgeCluster cluster,
                            LoadGenerator loadGenerator,
                            ForgeMetrics metrics,
                            LocalSliceInvoker sliceInvoker) {
        this.cluster = cluster;
        this.loadGenerator = loadGenerator;
        this.metrics = metrics;
        this.sliceInvoker = sliceInvoker;
        this.chaosController = ChaosController.chaosController(this::executeChaosEvent);
    }

    public static ForgeApiHandler forgeApiHandler(ForgeCluster cluster,
                                                  LoadGenerator loadGenerator,
                                                  ForgeMetrics metrics,
                                                  LocalSliceInvoker sliceInvoker) {
        return new ForgeApiHandler(cluster, loadGenerator, metrics, sliceInvoker);
    }

    /**
     * Execute a chaos event.
     */
    private void executeChaosEvent(ChaosEvent event) {
        switch (event) {
            case ChaosEvent.NodeKill kill -> {
                addEvent("CHAOS", "Killing node " + kill.nodeId());
                cluster.crashNode(kill.nodeId());
            }
            case ChaosEvent.LatencySpike spike -> {
                addEvent("CHAOS",
                         "Adding " + spike.latencyMs() + "ms latency to " + spike.nodeId());
            }
            case ChaosEvent.SliceCrash crash -> {
                addEvent("CHAOS", "Crashing slice " + crash.sliceArtifact());
            }
            case ChaosEvent.InvocationFailure failure -> {
                addEvent("CHAOS", "Injecting " + (int)(failure.failureRate() * 100) + "% failure rate");
            }
            default -> addEvent("CHAOS", "Executing: " + event.description());
        }
    }

    public int sliceCount() {
        return sliceInvoker.sliceCount();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        var path = request.uri();
        var method = request.method();
        log.debug("API request: {} {}", method, path);
        try{
            if (path.equals("/api/status") && method == HttpMethod.GET) {
                handleStatus(ctx);
            } else if (path.equals("/api/add-node") && method == HttpMethod.POST) {
                handleAddNode(ctx);
            } else if (path.startsWith("/api/kill/") && method == HttpMethod.POST) {
                handleKillNode(ctx,
                               path.substring("/api/kill/".length()));
            } else if (path.startsWith("/api/crash/") && method == HttpMethod.POST) {
                handleCrashNode(ctx,
                                path.substring("/api/crash/".length()));
            } else if (path.equals("/api/rolling-restart") && method == HttpMethod.POST) {
                handleRollingRestart(ctx);
            } else if (path.startsWith("/api/load/set/") && method == HttpMethod.POST) {
                handleSetLoad(ctx,
                              path.substring("/api/load/set/".length()));
            } else if (path.equals("/api/load/ramp") && method == HttpMethod.POST) {
                handleRampLoad(ctx, request);
            } else if (path.equals("/api/events") && method == HttpMethod.GET) {
                handleEvents(ctx);
            } else if (path.equals("/api/reset-metrics") && method == HttpMethod.POST) {
                handleResetMetrics(ctx);
            } else if (path.equals("/api/node-metrics") && method == HttpMethod.GET) {
                handleNodeMetrics(ctx);
            } else if (path.equals("/api/orders") && method == HttpMethod.POST) {
                handlePlaceOrder(ctx, request);
            } else if (path.startsWith("/api/orders/") && method == HttpMethod.GET) {
                handleGetOrderStatus(ctx,
                                     path.substring("/api/orders/".length()));
            } else if (path.startsWith("/api/orders/") && method == HttpMethod.DELETE) {
                handleCancelOrder(ctx,
                                  path.substring("/api/orders/".length()),
                                  request);
            } else if (path.startsWith("/api/inventory/") && method == HttpMethod.GET) {
                handleCheckStock(ctx,
                                 path.substring("/api/inventory/".length()));
            } else if (path.startsWith("/api/pricing/") && method == HttpMethod.GET) {
                handleGetPrice(ctx,
                               path.substring("/api/pricing/".length()));
            } else if (path.equals("/api/simulator/metrics") && method == HttpMethod.GET) {
                handleSimulatorMetrics(ctx);
            } else if (path.startsWith("/api/simulator/rate/") && method == HttpMethod.POST) {
                handleSimulatorRate(ctx,
                                    path.substring("/api/simulator/rate/".length()),
                                    request);
            } else if (path.equals("/api/simulator/entrypoints") && method == HttpMethod.GET) {
                handleSimulatorEntryPoints(ctx);
            } else if (path.equals("/api/simulator/config") && method == HttpMethod.GET) {
                handleGetConfig(ctx);
            } else if (path.equals("/api/simulator/config") && method == HttpMethod.PUT) {
                handlePutConfig(ctx, request);
            } else if (path.equals("/api/simulator/config/multiplier") && method == HttpMethod.POST) {
                handleSetMultiplier(ctx, request);
            } else if (path.equals("/api/simulator/config/enabled") && method == HttpMethod.POST) {
                handleSetEnabled(ctx, request);
            } else if (path.equals("/api/inventory/mode") && method == HttpMethod.GET) {
                handleGetInventoryMode(ctx);
            } else if (path.equals("/api/inventory/mode") && method == HttpMethod.POST) {
                handleSetInventoryMode(ctx, request);
            } else if (path.equals("/api/inventory/metrics") && method == HttpMethod.GET) {
                handleInventoryMetrics(ctx);
            } else if (path.equals("/api/inventory/stock") && method == HttpMethod.GET) {
                handleInventoryStock(ctx);
            } else if (path.equals("/api/inventory/reset") && method == HttpMethod.POST) {
                handleInventoryReset(ctx);
            } else if (path.equals("/api/chaos/status") && method == HttpMethod.GET) {
                handleChaosStatus(ctx);
            } else if (path.equals("/api/chaos/enable") && method == HttpMethod.POST) {
                handleChaosEnable(ctx, request);
            } else if (path.equals("/api/chaos/inject") && method == HttpMethod.POST) {
                handleChaosInject(ctx, request);
            } else if (path.startsWith("/api/chaos/stop/") && method == HttpMethod.POST) {
                handleChaosStop(ctx,
                                path.substring("/api/chaos/stop/".length()));
            } else if (path.equals("/api/chaos/stop-all") && method == HttpMethod.POST) {
                handleChaosStopAll(ctx);
            } else if (path.equals("/api/simulator/mode") && method == HttpMethod.GET) {
                handleGetMode(ctx);
            } else if (path.equals("/api/simulator/modes") && method == HttpMethod.GET) {
                handleGetModes(ctx);
            } else if (path.startsWith("/api/simulator/mode/") && method == HttpMethod.PUT) {
                handleSetMode(ctx,
                              path.substring("/api/simulator/mode/".length()));
            } else if (path.startsWith("/repository/") && method == HttpMethod.GET) {
                handleRepositoryGet(ctx, path);
            } else if (path.startsWith("/repository/") && (method == HttpMethod.PUT || method == HttpMethod.POST)) {
                handleRepositoryPut(ctx, path, request);
            } else if (path.equals("/api/deploy") && method == HttpMethod.POST) {
                handleDeploy(ctx, request);
            } else if (path.equals("/api/blueprint") && method == HttpMethod.POST) {
                handleBlueprint(ctx, request);
            } else if (path.equals("/api/undeploy") && method == HttpMethod.POST) {
                handleUndeploy(ctx, request);
            } else if (path.equals("/api/slices/status") && method == HttpMethod.GET) {
                handleSlicesStatus(ctx);
            } else if (path.equals("/api/cluster/metrics") && method == HttpMethod.GET) {
                handleClusterMetrics(ctx);
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
        var loadStatus = new LoadStatus(loadGenerator.currentRate(),
                                        loadGenerator.targetRate(),
                                        loadGenerator.isRunning());
        var response = new StatusResponse(clusterStatus,
                                          metricsSnapshot,
                                          loadStatus,
                                          uptimeSeconds(),
                                          sliceInvoker.sliceCount());
        sendResponse(ctx, OK, toJson(response));
    }

    private void handleAddNode(ChannelHandlerContext ctx) {
        addEvent("ADD_NODE", "Adding new node to cluster");
        cluster.addNode()
               .onSuccess(nodeId -> onNodeAdded(ctx, nodeId))
               .onFailure(cause -> onAddNodeFailed(ctx, cause));
    }

    private void onNodeAdded(ChannelHandlerContext ctx, org.pragmatica.consensus.NodeId nodeId) {
        addEvent("NODE_JOINED", "Node " + nodeId.id() + " joined the cluster");
        sendResponse(ctx, OK, "{\"success\": true, \"nodeId\": \"" + nodeId.id() + "\", \"state\": \"joining\"}");
    }

    private void onAddNodeFailed(ChannelHandlerContext ctx, Cause cause) {
        addEvent("ADD_NODE_FAILED", "Failed to add node: " + cause.message());
        sendResponse(ctx,
                     INTERNAL_SERVER_ERROR,
                     "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
    }

    private void handleKillNode(ChannelHandlerContext ctx, String nodeId) {
        var wasLeader = cluster.currentLeader()
                               .fold(() -> false,
                                     l -> l.equals(nodeId));
        addEvent("KILL_NODE", "Killing node " + nodeId + (wasLeader
                                                          ? " (leader)"
                                                          : ""));
        cluster.killNode(nodeId)
               .onSuccess(_ -> onNodeKilled(ctx, nodeId, wasLeader))
               .onFailure(cause -> onKillFailed(ctx, nodeId, cause));
    }

    private void onNodeKilled(ChannelHandlerContext ctx, String nodeId, boolean wasLeader) {
        var newLeader = cluster.currentLeader()
                               .fold(() -> "none",
                                     l -> l);
        addEvent("NODE_KILLED", "Node " + nodeId + " killed" + (wasLeader
                                                                ? ", new leader: " + newLeader
                                                                : ""));
        sendResponse(ctx, OK, "{\"success\": true, \"newLeader\": \"" + newLeader + "\"}");
    }

    private void onKillFailed(ChannelHandlerContext ctx, String nodeId, Cause cause) {
        addEvent("KILL_FAILED", "Failed to kill node " + nodeId);
        sendResponse(ctx,
                     INTERNAL_SERVER_ERROR,
                     "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
    }

    private void handleCrashNode(ChannelHandlerContext ctx, String nodeId) {
        var wasLeader = cluster.currentLeader()
                               .fold(() -> false,
                                     l -> l.equals(nodeId));
        addEvent("CRASH_NODE", "Crashing node " + nodeId + " abruptly" + (wasLeader
                                                                          ? " (leader)"
                                                                          : ""));
        cluster.crashNode(nodeId)
               .onSuccess(_ -> onNodeCrashed(ctx, nodeId))
               .onFailure(cause -> onCrashFailed(ctx, nodeId, cause));
    }

    private void onNodeCrashed(ChannelHandlerContext ctx, String nodeId) {
        var newLeader = cluster.currentLeader()
                               .fold(() -> "none",
                                     l -> l);
        addEvent("NODE_CRASHED", "Node " + nodeId + " crashed");
        sendResponse(ctx, OK, "{\"success\": true, \"newLeader\": \"" + newLeader + "\"}");
    }

    private void onCrashFailed(ChannelHandlerContext ctx, String nodeId, Cause cause) {
        addEvent("CRASH_FAILED", "Failed to crash node " + nodeId);
        sendResponse(ctx,
                     INTERNAL_SERVER_ERROR,
                     "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
    }

    private void handleRollingRestart(ChannelHandlerContext ctx) {
        addEvent("ROLLING_RESTART", "Starting rolling restart of all nodes");
        cluster.rollingRestart()
               .onSuccess(_ -> onRollingRestartComplete(ctx))
               .onFailure(cause -> onRollingRestartFailed(ctx, cause));
    }

    private void onRollingRestartComplete(ChannelHandlerContext ctx) {
        addEvent("ROLLING_RESTART_COMPLETE", "Rolling restart completed successfully");
        sendResponse(ctx, OK, "{\"success\": true}");
    }

    private void onRollingRestartFailed(ChannelHandlerContext ctx, Cause cause) {
        addEvent("ROLLING_RESTART_FAILED", "Rolling restart failed: " + cause.message());
        sendResponse(ctx,
                     INTERNAL_SERVER_ERROR,
                     "{\"success\": false, \"error\": \"" + escapeJson(cause.message()) + "\"}");
    }

    private void handleSetLoad(ChannelHandlerContext ctx, String rateStr) {
        try{
            var rate = Integer.parseInt(rateStr);
            loadGenerator.setRate(rate);
            addEvent("LOAD_SET", "Load set to " + rate + " req/sec");
            sendResponse(ctx, OK, "{\"success\": true, \"newRate\": " + rate + "}");
        } catch (NumberFormatException e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\": false, \"error\": \"Invalid rate\"}");
        }
    }

    private void handleRampLoad(ChannelHandlerContext ctx, FullHttpRequest request) {
        try{
            var body = request.content()
                              .toString(StandardCharsets.UTF_8);
            // Simple parsing - expect {"targetRate": 5000, "durationMs": 30000}
            var targetRate = extractInt(body, "targetRate", 5000);
            var durationMs = extractLong(body, "durationMs", 30000);
            loadGenerator.rampUp(targetRate, durationMs);
            addEvent("LOAD_RAMP", "Ramping load to " + targetRate + " req/sec over " + (durationMs / 1000) + "s");
            sendResponse(ctx,
                         OK,
                         "{\"success\": true, \"targetRate\": " + targetRate + ", \"durationMs\": " + durationMs + "}");
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
                      .append("\"timestamp\":\"")
                      .append(event.timestamp)
                      .append("\",")
                      .append("\"type\":\"")
                      .append(event.type)
                      .append("\",")
                      .append("\"message\":\"")
                      .append(escapeJson(event.message))
                      .append("\"")
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

    private void handleNodeMetrics(ChannelHandlerContext ctx) {
        var nodeMetrics = cluster.nodeMetrics();
        var json = new StringBuilder("[");
        var first = true;
        for (var m : nodeMetrics) {
            if (!first) json.append(",");
            first = false;
            json.append("{")
                .append("\"nodeId\":\"")
                .append(m.nodeId())
                .append("\",")
                .append("\"isLeader\":")
                .append(m.isLeader())
                .append(",")
                .append("\"cpuUsage\":")
                .append(String.format("%.3f",
                                      m.cpuUsage()))
                .append(",")
                .append("\"heapUsedMb\":")
                .append(m.heapUsedMb())
                .append(",")
                .append("\"heapMaxMb\":")
                .append(m.heapMaxMb())
                .append("}");
        }
        json.append("]");
        sendResponse(ctx, OK, json.toString());
    }

    /**
     * Handle simulator metrics request - returns per-entry-point metrics.
     */
    private void handleSimulatorMetrics(ChannelHandlerContext ctx) {
        var snapshots = loadGenerator.entryPointMetrics()
                                     .snapshot();
        var json = new StringBuilder("{\"entryPoints\":[");
        var first = true;
        for (var snapshot : snapshots) {
            if (!first) json.append(",");
            first = false;
            json.append(snapshot.toJson());
        }
        json.append("]}");
        sendResponse(ctx, OK, json.toString());
    }

    /**
     * Handle setting per-entry-point rate.
     * Path format: /api/simulator/rate/{entryPoint}
     * Body: {"rate": 500}
     */
    private void handleSimulatorRate(ChannelHandlerContext ctx, String entryPoint, FullHttpRequest request) {
        try{
            var body = request.content()
                              .toString(StandardCharsets.UTF_8);
            var rate = extractInt(body, "rate", - 1);
            if (rate < 0) {
                sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"Missing or invalid rate\"}");
                return;
            }
            loadGenerator.setRate(entryPoint, rate);
            addEvent("RATE_SET", "Set " + entryPoint + " rate to " + rate + " req/sec");
            sendResponse(ctx, OK, "{\"success\":true,\"entryPoint\":\"" + entryPoint + "\",\"rate\":" + rate + "}");
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle listing all available entry points.
     */
    private void handleSimulatorEntryPoints(ChannelHandlerContext ctx) {
        var entryPoints = loadGenerator.entryPoints();
        var json = new StringBuilder("{\"entryPoints\":[");
        var first = true;
        for (var ep : entryPoints) {
            if (!first) json.append(",");
            first = false;
            var rate = loadGenerator.currentRate(ep);
            json.append("{\"name\":\"")
                .append(ep)
                .append("\",\"rate\":")
                .append(rate)
                .append("}");
        }
        json.append("]}");
        sendResponse(ctx, OK, json.toString());
    }

    /**
     * Handle get current simulator config.
     */
    private void handleGetConfig(ChannelHandlerContext ctx) {
        sendResponse(ctx, OK, config.toJson());
    }

    /**
     * Handle replace simulator config.
     */
    private void handlePutConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        try{
            var body = request.content()
                              .toString(StandardCharsets.UTF_8);
            var newConfig = SimulatorConfig.parseJson(body);
            config = newConfig;
            applyLoadGeneratorSettings(newConfig);
            addEvent("CONFIG_UPDATE", "Simulator configuration updated");
            sendResponse(ctx, OK, "{\"success\":true}");
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle set global rate multiplier.
     */
    private void handleSetMultiplier(ChannelHandlerContext ctx, FullHttpRequest request) {
        try{
            var body = request.content()
                              .toString(StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("\"multiplier\"\\s*:\\s*([\\d.]+)");
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                var multiplier = Double.parseDouble(matcher.group(1));
                var newConfig = config.withGlobalMultiplier(multiplier);
                config = newConfig;
                applyLoadGeneratorSettings(newConfig);
                addEvent("MULTIPLIER_SET", "Global rate multiplier set to " + multiplier);
                sendResponse(ctx, OK, "{\"success\":true,\"multiplier\":" + multiplier + "}");
            } else {
                sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"Missing multiplier\"}");
            }
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle enable/disable load generator.
     */
    private void handleSetEnabled(ChannelHandlerContext ctx, FullHttpRequest request) {
        try{
            var body = request.content()
                              .toString(StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("\"enabled\"\\s*:\\s*(true|false)");
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                var enabled = Boolean.parseBoolean(matcher.group(1));
                var newConfig = config.withLoadGeneratorEnabled(enabled);
                config = newConfig;
                applyLoadGeneratorSettings(newConfig);
                addEvent("LOAD_GENERATOR", "Load generator " + (enabled
                                                                ? "enabled"
                                                                : "disabled"));
                sendResponse(ctx, OK, "{\"success\":true,\"enabled\":" + enabled + "}");
            } else {
                sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"Missing enabled\"}");
            }
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle get inventory mode - simulated.
     */
    private void handleGetInventoryMode(ChannelHandlerContext ctx) {
        sendResponse(ctx, OK, "{\"mode\":\"" + (infiniteInventoryMode
                                                ? "infinite"
                                                : "realistic") + "\"}");
    }

    /**
     * Handle set inventory mode - simulated.
     * Body: {"mode": "infinite"} or {"mode": "realistic"}
     */
    private void handleSetInventoryMode(ChannelHandlerContext ctx, FullHttpRequest request) {
        try{
            var body = request.content()
                              .toString(StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("\"mode\"\\s*:\\s*\"(\\w+)\"");
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                var mode = matcher.group(1);
                infiniteInventoryMode = "infinite".equalsIgnoreCase(mode);
                addEvent("INVENTORY_MODE", "Inventory mode set to " + (infiniteInventoryMode
                                                                       ? "infinite"
                                                                       : "realistic"));
                sendResponse(ctx,
                             OK,
                             "{\"success\":true,\"mode\":\"" + (infiniteInventoryMode
                                                                ? "infinite"
                                                                : "realistic") + "\"}");
            } else {
                sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"Missing mode\"}");
            }
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle inventory metrics request - simulated.
     */
    private void handleInventoryMetrics(ChannelHandlerContext ctx) {
        var json = String.format("{\"totalReservations\":%d,\"totalReleases\":%d,\"stockOuts\":%d,\"infiniteMode\":%b,\"refillRate\":%d}",
                                 simulatedReservations,
                                 simulatedReleases,
                                 simulatedStockOuts,
                                 infiniteInventoryMode,
                                 100);
        sendResponse(ctx, OK, json);
    }

    /**
     * Handle inventory stock levels request - simulated.
     */
    private void handleInventoryStock(ChannelHandlerContext ctx) {
        // Return simulated stock levels for known products
        var json = "{\"stock\":{\"PROD-ABC123\":500,\"PROD-DEF456\":350,\"PROD-GHI789\":200}}";
        sendResponse(ctx, OK, json);
    }

    /**
     * Handle inventory reset - simulated.
     */
    private void handleInventoryReset(ChannelHandlerContext ctx) {
        simulatedReservations = 0;
        simulatedReleases = 0;
        simulatedStockOuts = 0;
        addEvent("INVENTORY_RESET", "Inventory stock reset to initial levels");
        sendResponse(ctx, OK, "{\"success\":true}");
    }

    // ==================== Chaos API Handlers ====================
    /**
     * Get chaos controller status.
     */
    private void handleChaosStatus(ChannelHandlerContext ctx) {
        sendResponse(ctx,
                     OK,
                     chaosController.status()
                                    .toJson());
    }

    /**
     * Enable or disable chaos injection.
     */
    private void handleChaosEnable(ChannelHandlerContext ctx, FullHttpRequest request) {
        var body = request.content()
                          .toString(StandardCharsets.UTF_8);
        var enabled = extractBoolean(body, "enabled", false);
        chaosController.setEnabled(enabled);
        addEvent("CHAOS_" + (enabled
                             ? "ENABLED"
                             : "DISABLED"), "Chaos controller " + (enabled
                                                                   ? "enabled"
                                                                   : "disabled"));
        sendResponse(ctx, OK, "{\"success\":true,\"enabled\":" + enabled + "}");
    }

    private static final Fn1<Cause, String> UNKNOWN_CHAOS_TYPE = Causes.forOneValue("Unknown chaos type: {}");

    /**
     * Inject a chaos event.
     * Body format: {"type":"NODE_KILL","nodeId":"node-1","durationSeconds":30}
     */
    private void handleChaosInject(ChannelHandlerContext ctx, FullHttpRequest request) {
        var body = request.content()
                          .toString(StandardCharsets.UTF_8);
        var type = extractString(body, "type", "");
        var durationSeconds = extractLong(body, "durationSeconds", 60);
        var duration = Duration.ofSeconds(durationSeconds);
        parseChaosEvent(body, type, duration)
                       .async()
                       .flatMap(chaosController::injectChaos)
                       .onSuccess(eventId -> sendChaosSuccess(ctx, eventId, type))
                       .onFailure(cause -> sendChaosError(ctx, cause));
    }

    private Result<ChaosEvent> parseChaosEvent(String body, String type, Duration duration) {
        return switch (type.toUpperCase()) {
            case "NODE_KILL" -> ChaosEvent.NodeKill.kill(extractString(body, "nodeId", null),
                                                         duration)
                                          .map(e -> e);
            case "LATENCY_SPIKE" -> ChaosEvent.LatencySpike.addLatency(extractString(body, "nodeId", null),
                                                                       extractLong(body, "latencyMs", 500),
                                                                       duration)
                                              .map(e -> e);
            case "SLICE_CRASH" -> ChaosEvent.SliceCrash.crashSlice(extractString(body, "artifact", null),
                                                                   extractString(body, "nodeId", null),
                                                                   duration)
                                            .map(e -> e);
            case "INVOCATION_FAILURE" -> ChaosEvent.InvocationFailure.forSlice(extractString(body, "artifact", null),
                                                                               extractDouble(body, "failureRate", 0.5),
                                                                               duration)
                                                   .map(e -> e);
            case "CPU_SPIKE" -> ChaosEvent.CpuSpike.onNode(extractString(body, "nodeId", null),
                                                           extractDouble(body, "level", 0.8),
                                                           duration)
                                          .map(e -> e);
            case "MEMORY_PRESSURE" -> ChaosEvent.MemoryPressure.onNode(extractString(body, "nodeId", null),
                                                                       extractDouble(body, "level", 0.9),
                                                                       duration)
                                                .map(e -> e);
            default -> UNKNOWN_CHAOS_TYPE.apply(type)
                                         .result();
        };
    }

    private void sendChaosSuccess(ChannelHandlerContext ctx, String eventId, String type) {
        sendResponse(ctx, OK, "{\"success\":true,\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\"}");
    }

    private void sendChaosError(ChannelHandlerContext ctx, Cause cause) {
        sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
    }

    /**
     * Stop a specific chaos event.
     */
    private void handleChaosStop(ChannelHandlerContext ctx, String eventId) {
        chaosController.stopChaos(eventId);
        addEvent("CHAOS_STOPPED", "Stopped chaos event " + eventId);
        sendResponse(ctx, OK, "{\"success\":true,\"eventId\":\"" + eventId + "\"}");
    }

    /**
     * Stop all chaos events.
     */
    private void handleChaosStopAll(ChannelHandlerContext ctx) {
        chaosController.stopAllChaos();
        addEvent("CHAOS_STOPPED_ALL", "Stopped all chaos events");
        sendResponse(ctx, OK, "{\"success\":true}");
    }

    // ==================== Mode API Handlers ====================
    /**
     * Get current simulator mode.
     */
    private void handleGetMode(ChannelHandlerContext ctx) {
        sendResponse(ctx, OK, currentMode.toJson());
    }

    /**
     * Get all available modes.
     */
    private void handleGetModes(ChannelHandlerContext ctx) {
        sendResponse(ctx, OK, SimulatorMode.allModesJson());
    }

    /**
     * Set simulator mode.
     */
    private void handleSetMode(ChannelHandlerContext ctx, String modeName) {
        SimulatorMode.simulatorMode(modeName)
                     .onSuccess(newMode -> applyMode(ctx, newMode))
                     .onFailure(cause -> sendResponse(ctx,
                                                      BAD_REQUEST,
                                                      "{\"success\":false,\"error\":\"" + escapeJson(cause.message())
                                                      + "\"}"));
    }

    /**
     * Apply a new simulator mode with proper synchronization.
     */
    private void applyMode(ChannelHandlerContext ctx, SimulatorMode newMode) {
        String oldModeName;
        SimulatorConfig newConfig;
        // Synchronized update of mode and config
        synchronized (modeLock) {
            oldModeName = currentMode.name();
            currentMode = newMode;
            newConfig = newMode.applyTo(config);
            config = newConfig;
        }
        // These operations are idempotent and can be outside the lock
        chaosController.setEnabled(newMode.chaosEnabled());
        applyLoadGeneratorSettings(newConfig);
        addEvent("MODE_CHANGED", "Simulator mode changed from " + oldModeName + " to " + newMode.displayName());
        sendResponse(ctx,
                     OK,
                     String.format("{\"success\":true,\"previousMode\":\"%s\",\"currentMode\":\"%s\"}",
                                   oldModeName,
                                   newMode.name()));
    }

    // ==================== Simulated Order API Handlers ====================
    // These handlers return simulated responses for load testing when no slices are loaded
    /**
     * Handle order placement - returns simulated response.
     */
    private void handlePlaceOrder(ChannelHandlerContext ctx, FullHttpRequest request) {
        applySimulation("place-order")
                       .onSuccess(_ -> sendPlaceOrderSuccess(ctx))
                       .onFailure(cause -> sendErrorResponse(ctx, INTERNAL_SERVER_ERROR, cause));
    }

    private void sendPlaceOrderSuccess(ChannelHandlerContext ctx) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        var orderId = String.format("ORD-%08d", random.nextInt(100_000_000));
        DataGenerator.OrderIdGenerator.trackOrderId(orderId);
        var total = 10.00 + random.nextDouble() * 990.00;
        var json = String.format("{\"success\":true,\"orderId\":\"%s\",\"status\":\"CONFIRMED\",\"total\":\"USD %.2f\"}",
                                 orderId,
                                 total);
        sendResponse(ctx, OK, json);
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Cause cause) {
        sendResponse(ctx, status, "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
    }

    /**
     * Handle get order status - returns simulated response.
     */
    private void handleGetOrderStatus(ChannelHandlerContext ctx, String orderId) {
        applySimulation("get-order-status")
                       .onSuccess(_ -> sendOrderStatusSuccess(ctx, orderId))
                       .onFailure(cause -> sendErrorResponse(ctx, NOT_FOUND, cause));
    }

    private void sendOrderStatusSuccess(ChannelHandlerContext ctx, String orderId) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        var statuses = List.of("CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED");
        var status = statuses.get(random.nextInt(statuses.size()));
        var total = 10.00 + random.nextDouble() * 990.00;
        var itemCount = 1 + random.nextInt(5);
        var json = String.format("{\"success\":true,\"orderId\":\"%s\",\"status\":\"%s\",\"total\":\"USD %.2f\",\"itemCount\":%d}",
                                 orderId,
                                 status,
                                 total,
                                 itemCount);
        sendResponse(ctx, OK, json);
    }

    /**
     * Handle cancel order - returns simulated response.
     */
    private void handleCancelOrder(ChannelHandlerContext ctx, String orderId, FullHttpRequest request) {
        var body = request.content()
                          .toString(StandardCharsets.UTF_8);
        var reason = extractString(body, "reason", "User requested cancellation");
        applySimulation("cancel-order")
                       .onSuccess(_ -> sendCancelOrderSuccess(ctx, orderId, reason))
                       .onFailure(cause -> sendErrorResponse(ctx, BAD_REQUEST, cause));
    }

    private void sendCancelOrderSuccess(ChannelHandlerContext ctx, String orderId, String reason) {
        var json = String.format("{\"success\":true,\"orderId\":\"%s\",\"status\":\"CANCELLED\",\"reason\":\"%s\"}",
                                 orderId,
                                 escapeJson(reason));
        sendResponse(ctx, OK, json);
    }

    /**
     * Handle check stock - returns simulated response.
     */
    private void handleCheckStock(ChannelHandlerContext ctx, String productId) {
        applySimulation("inventory-service")
                       .onSuccess(_ -> sendCheckStockSuccess(ctx, productId))
                       .onFailure(cause -> sendErrorResponse(ctx, NOT_FOUND, cause));
    }

    private void sendCheckStockSuccess(ChannelHandlerContext ctx, String productId) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        var available = random.nextInt(1000);
        var json = String.format("{\"success\":true,\"productId\":\"%s\",\"available\":%d,\"sufficient\":%b}",
                                 productId,
                                 available,
                                 available > 0);
        sendResponse(ctx, OK, json);
    }

    /**
     * Handle get price - returns simulated response.
     */
    private void handleGetPrice(ChannelHandlerContext ctx, String productId) {
        applySimulation("pricing-service")
                       .onSuccess(_ -> sendGetPriceSuccess(ctx, productId))
                       .onFailure(cause -> sendErrorResponse(ctx, NOT_FOUND, cause));
    }

    private void sendGetPriceSuccess(ChannelHandlerContext ctx, String productId) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        var price = 5.00 + random.nextDouble() * 495.00;
        var json = String.format("{\"success\":true,\"productId\":\"%s\",\"price\":\"USD %.2f\"}", productId, price);
        sendResponse(ctx, OK, json);
    }

    /**
     * Apply backend simulation for a slice based on current config.
     */
    private Promise<Unit> applySimulation(String sliceName) {
        var sliceConfig = config.sliceConfig(sliceName);
        return sliceConfig.buildSimulation()
                          .apply();
    }

    /**
     * Apply load generator settings from config.
     * Centralized method to avoid duplication across handlers.
     */
    private void applyLoadGeneratorSettings(SimulatorConfig cfg) {
        for (var entryPoint : loadGenerator.entryPoints()) {
            var rate = cfg.loadGeneratorEnabled()
                       ? cfg.effectiveRate(entryPoint)
                       : 0;
            loadGenerator.setRate(entryPoint, rate);
        }
    }

    // ==================== Deployment API Handlers ====================
    // These handlers proxy to the actual cluster nodes for real slice management
    /**
     * Handle POST /api/deploy - deploy a slice via blueprint.
     * Proxies to leader node's ManagementServer.
     * Body: {"artifact": "group:id:version", "instances": 1}
     */
    private void handleDeploy(ChannelHandlerContext ctx, FullHttpRequest request) {
        proxyToLeader(ctx, "/deploy", request);
    }

    /**
     * Handle POST /api/blueprint - apply a complete blueprint.
     * Proxies to leader node's ManagementServer.
     */
    private void handleBlueprint(ChannelHandlerContext ctx, FullHttpRequest request) {
        proxyToLeader(ctx, "/blueprint", request);
    }

    /**
     * Handle POST /api/undeploy - undeploy a slice.
     * Proxies to leader node's ManagementServer.
     * Body: {"artifact": "group:id:version"}
     */
    private void handleUndeploy(ChannelHandlerContext ctx, FullHttpRequest request) {
        proxyToLeader(ctx, "/undeploy", request);
    }

    /**
     * Handle GET /api/slices/status - get real slice status from cluster.
     * Proxies to leader node's ManagementServer.
     */
    private void handleSlicesStatus(ChannelHandlerContext ctx) {
        proxyGetToLeader(ctx, "/slices/status");
    }

    /**
     * Handle GET /api/cluster/metrics - get real cluster metrics.
     * Proxies to leader node's ManagementServer.
     */
    private void handleClusterMetrics(ChannelHandlerContext ctx) {
        proxyGetToLeader(ctx, "/metrics");
    }

    /**
     * Proxy a POST request to the leader node's ManagementServer.
     */
    private void proxyToLeader(ChannelHandlerContext ctx, String endpoint, FullHttpRequest request) {
        var leaderNode = findLeaderNode();
        if (leaderNode.isEmpty()) {
            sendResponse(ctx, SERVICE_UNAVAILABLE, "{\"error\": \"No leader available\"}");
            return;
        }
        var node = leaderNode.get();
        var mgmtPort = node.managementPort();
        var body = request.content()
                          .toString(StandardCharsets.UTF_8);
        proxyHttpPost("localhost", mgmtPort, endpoint, body)
                     .onSuccess(response -> {
                                    addEvent("DEPLOY", "Proxied " + endpoint + " to leader: " + response);
                                    sendResponse(ctx, OK, response);
                                })
                     .onFailure(cause -> {
                                    log.error("Failed to proxy to leader: {}",
                                              cause.message());
                                    sendResponse(ctx,
                                                 INTERNAL_SERVER_ERROR,
                                                 "{\"error\": \"" + escapeJson(cause.message()) + "\"}");
                                });
    }

    /**
     * Proxy a GET request to the leader node's ManagementServer.
     */
    private void proxyGetToLeader(ChannelHandlerContext ctx, String endpoint) {
        var leaderNode = findLeaderNode();
        if (leaderNode.isEmpty()) {
            sendResponse(ctx, SERVICE_UNAVAILABLE, "{\"error\": \"No leader available\"}");
            return;
        }
        var node = leaderNode.get();
        var mgmtPort = node.managementPort();
        proxyHttpGet("localhost", mgmtPort, endpoint)
                    .onSuccess(response -> sendResponse(ctx, OK, response))
                    .onFailure(cause -> {
                                   log.error("Failed to proxy GET to leader: {}",
                                             cause.message());
                                   sendResponse(ctx,
                                                INTERNAL_SERVER_ERROR,
                                                "{\"error\": \"" + escapeJson(cause.message()) + "\"}");
                               });
    }

    /**
     * Find the current leader node.
     */
    private java.util.Optional<org.pragmatica.aether.node.AetherNode> findLeaderNode() {
        return cluster.allNodes()
                      .stream()
                      .filter(org.pragmatica.aether.node.AetherNode::isLeader)
                      .findFirst();
    }

    /**
     * Execute HTTP POST to a node's ManagementServer.
     */
    private Promise<String> proxyHttpPost(String host, int port, String path, String body) {
        return Promise.promise(promise -> {
                                   try{
                                       var client = java.net.http.HttpClient.newBuilder()
                                                        .connectTimeout(Duration.ofSeconds(5))
                                                        .build();
                                       var request = java.net.http.HttpRequest.newBuilder()
                                                         .uri(java.net.URI.create("http://" + host + ":" + port + path))
                                                         .header("Content-Type", "application/json")
                                                         .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                                                         .timeout(Duration.ofSeconds(30))
                                                         .build();
                                       client.sendAsync(request,
                                                        java.net.http.HttpResponse.BodyHandlers.ofString())
                                             .thenAccept(response -> {
                                                             if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                                                 promise.succeed(response.body());
                                                             } else {
                                                                 promise.fail(Causes.cause("HTTP " + response.statusCode()
                                                                                           + ": " + response.body()));
                                                             }
                                                         })
                                             .exceptionally(e -> {
                                           promise.fail(Causes.fromThrowable(e));
                                           return null;
                                       });
                                   } catch (Exception e) {
                                       promise.fail(Causes.fromThrowable(e));
                                   }
                               });
    }

    /**
     * Execute HTTP GET to a node's ManagementServer.
     */
    private Promise<String> proxyHttpGet(String host, int port, String path) {
        return Promise.promise(promise -> {
                                   try{
                                       var client = java.net.http.HttpClient.newBuilder()
                                                        .connectTimeout(Duration.ofSeconds(5))
                                                        .build();
                                       var request = java.net.http.HttpRequest.newBuilder()
                                                         .uri(java.net.URI.create("http://" + host + ":" + port + path))
                                                         .GET()
                                                         .timeout(Duration.ofSeconds(30))
                                                         .build();
                                       client.sendAsync(request,
                                                        java.net.http.HttpResponse.BodyHandlers.ofString())
                                             .thenAccept(response -> {
                                                             if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                                                 promise.succeed(response.body());
                                                             } else {
                                                                 promise.fail(Causes.cause("HTTP " + response.statusCode()
                                                                                           + ": " + response.body()));
                                                             }
                                                         })
                                             .exceptionally(e -> {
                                           promise.fail(Causes.fromThrowable(e));
                                           return null;
                                       });
                                   } catch (Exception e) {
                                       promise.fail(Causes.fromThrowable(e));
                                   }
                               });
    }

    // ==================== Repository API Handlers ====================
    /**
     * Handle GET /repository/** - retrieve artifact from DHT.
     */
    private void handleRepositoryGet(ChannelHandlerContext ctx, String path) {
        var nodes = cluster.allNodes();
        if (nodes.isEmpty()) {
            sendResponse(ctx, SERVICE_UNAVAILABLE, "{\"error\": \"No nodes available\"}");
            return;
        }
        var node = nodes.getFirst();
        node.mavenProtocolHandler()
            .handleGet(path)
            .onSuccess(response -> sendRepositoryGetResponse(ctx, response))
            .onFailure(cause -> sendResponse(ctx,
                                             INTERNAL_SERVER_ERROR,
                                             "{\"error\": \"" + escapeJson(cause.message()) + "\"}"));
    }

    private void sendRepositoryGetResponse(ChannelHandlerContext ctx,
                                           org.pragmatica.aether.infra.artifact.MavenProtocolHandler.MavenResponse response) {
        if (response.statusCode() == 200) {
            var content = Unpooled.wrappedBuffer(response.content());
            var httpResponse = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
            httpResponse.headers()
                        .set(HttpHeaderNames.CONTENT_TYPE,
                             response.contentType());
            httpResponse.headers()
                        .set(HttpHeaderNames.CONTENT_LENGTH,
                             response.content().length);
            httpResponse.headers()
                        .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            ctx.writeAndFlush(httpResponse)
               .addListener(ChannelFutureListener.CLOSE);
        } else if (response.statusCode() == 404) {
            sendResponse(ctx, NOT_FOUND, "{\"error\": \"Artifact not found\"}");
        } else {
            sendResponse(ctx,
                         HttpResponseStatus.valueOf(response.statusCode()),
                         new String(response.content(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Handle PUT/POST /repository/** - store artifact in DHT.
     */
    private void handleRepositoryPut(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
        var nodes = cluster.allNodes();
        if (nodes.isEmpty()) {
            sendResponse(ctx, SERVICE_UNAVAILABLE, "{\"error\": \"No nodes available\"}");
            return;
        }
        var node = nodes.getFirst();
        var byteBuf = request.content();
        var content = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(content);
        node.mavenProtocolHandler()
            .handlePut(path, content)
            .onSuccess(_ -> sendRepositoryPutSuccess(ctx, path, content.length))
            .onFailure(cause -> sendResponse(ctx,
                                             INTERNAL_SERVER_ERROR,
                                             "{\"error\": \"" + escapeJson(cause.message()) + "\"}"));
    }

    private void sendRepositoryPutSuccess(ChannelHandlerContext ctx, String path, int contentLength) {
        var json = String.format("{\"success\":true,\"path\":\"%s\",\"size\":%d}", escapeJson(path), contentLength);
        sendResponse(ctx, OK, json);
        addEvent("ARTIFACT_DEPLOYED", "Deployed " + path + " (" + contentLength + " bytes)");
    }

    public void addEvent(String type, String message) {
        var event = new ForgeEvent(Instant.now()
                                          .toString(),
                                   type,
                                   message);
        events.addLast(event);
        // Trim in a thread-safe manner - over-deletion is acceptable
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
        log.info("[EVENT] {}: {}", type, message);
    }

    private long uptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        var content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HTTP_1_1, status, content);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers()
                .set(HttpHeaderNames.CONTENT_LENGTH,
                     content.readableBytes());
        response.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, DELETE, OPTIONS");
        response.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private String toJson(StatusResponse response) {
        var nodes = new StringBuilder("[");
        var first = true;
        for (var node : response.cluster.nodes()) {
            if (!first) nodes.append(",");
            first = false;
            nodes.append("{")
                 .append("\"id\":\"")
                 .append(node.id())
                 .append("\",")
                 .append("\"port\":")
                 .append(node.port())
                 .append(",")
                 .append("\"state\":\"")
                 .append(node.state())
                 .append("\",")
                 .append("\"isLeader\":")
                 .append(node.isLeader())
                 .append("}");
        }
        nodes.append("]");
        return "{" + "\"cluster\":{" + "\"nodes\":" + nodes + "," + "\"leaderId\":\"" + response.cluster.leaderId()
               + "\"," + "\"nodeCount\":" + response.cluster.nodes()
                                                    .size() + "}," + "\"metrics\":{" + "\"requestsPerSecond\":" + String.format("%.1f",
                                                                                                                                response.metrics.requestsPerSecond())
               + "," + "\"successRate\":" + String.format("%.2f", response.metrics.successRate()) + ","
               + "\"avgLatencyMs\":" + String.format("%.2f", response.metrics.avgLatencyMs()) + ","
               + "\"totalSuccess\":" + response.metrics.totalSuccess() + "," + "\"totalFailures\":" + response.metrics.totalFailures()
               + "}," + "\"load\":{" + "\"currentRate\":" + response.load.currentRate + "," + "\"targetRate\":" + response.load.targetRate
               + "," + "\"running\":" + response.load.running + "}," + "\"uptimeSeconds\":" + response.uptimeSeconds
               + "," + "\"sliceCount\":" + response.sliceCount + "}";
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
        var matcher = java.util.regex.Pattern.compile(pattern)
                          .matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return defaultValue;
    }

    private long extractLong(String json, String key, long defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        var matcher = java.util.regex.Pattern.compile(pattern)
                          .matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return defaultValue;
    }

    private String extractString(String json, String key, String defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var matcher = java.util.regex.Pattern.compile(pattern)
                          .matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    private boolean extractBoolean(String json, String key, boolean defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        var matcher = java.util.regex.Pattern.compile(pattern)
                          .matcher(json);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return defaultValue;
    }

    private double extractDouble(String json, String key, double defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*([\\d.]+)";
        var matcher = java.util.regex.Pattern.compile(pattern)
                          .matcher(json);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return defaultValue;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in API handler: {}", cause.getMessage());
        ctx.close();
    }

    private record StatusResponse(ClusterStatus cluster,
                                  MetricsSnapshot metrics,
                                  LoadStatus load,
                                  long uptimeSeconds,
                                  int sliceCount) {}

    private record LoadStatus(int currentRate, int targetRate, boolean running) {}

    private record ForgeEvent(String timestamp, String type, String message) {}
}
