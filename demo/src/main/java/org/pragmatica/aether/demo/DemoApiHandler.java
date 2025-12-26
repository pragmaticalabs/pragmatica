package org.pragmatica.aether.demo;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.pragmatica.aether.demo.DemoCluster.ClusterStatus;
import org.pragmatica.aether.demo.DemoMetrics.MetricsSnapshot;
import org.pragmatica.aether.demo.simulator.BackendSimulation;
import org.pragmatica.aether.demo.simulator.SimulatorConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.demo.order.inventory.CheckStockRequest;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice;
import org.pragmatica.aether.demo.order.inventory.StockAvailability;
import org.pragmatica.aether.demo.order.pricing.GetPriceRequest;
import org.pragmatica.aether.demo.order.pricing.ProductPrice;
import org.pragmatica.aether.demo.order.usecase.cancelorder.CancelOrderRequest;
import org.pragmatica.aether.demo.order.usecase.cancelorder.CancelOrderResponse;
import org.pragmatica.aether.demo.order.usecase.getorderstatus.GetOrderStatusRequest;
import org.pragmatica.aether.demo.order.usecase.getorderstatus.GetOrderStatusResponse;
import org.pragmatica.aether.demo.order.usecase.placeorder.PlaceOrderRequest;
import org.pragmatica.aether.demo.order.usecase.placeorder.PlaceOrderRequest.OrderItemRequest;
import org.pragmatica.aether.demo.order.usecase.placeorder.PlaceOrderResponse;
import org.pragmatica.aether.slice.MethodName;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handles REST API requests for the demo dashboard.
 * Provides endpoints for cluster status, node management, and load control.
 */
@Sharable
public final class DemoApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(DemoApiHandler.class);

    // Slice artifacts and method names
    private static final Artifact PLACE_ORDER_ARTIFACT =
        Artifact.artifact("org.pragmatica-lite.aether.demo:place-order:0.1.0").unwrap();
    private static final MethodName PLACE_ORDER_METHOD = MethodName.methodName("placeOrder").unwrap();

    private static final Artifact GET_ORDER_STATUS_ARTIFACT =
        Artifact.artifact("org.pragmatica-lite.aether.demo:get-order-status:0.1.0").unwrap();
    private static final MethodName GET_ORDER_STATUS_METHOD = MethodName.methodName("getOrderStatus").unwrap();

    private static final Artifact CANCEL_ORDER_ARTIFACT =
        Artifact.artifact("org.pragmatica-lite.aether.demo:cancel-order:0.1.0").unwrap();
    private static final MethodName CANCEL_ORDER_METHOD = MethodName.methodName("cancelOrder").unwrap();

    private static final Artifact INVENTORY_ARTIFACT =
        Artifact.artifact("org.pragmatica-lite.aether.demo:inventory-service:0.1.0").unwrap();
    private static final MethodName CHECK_STOCK_METHOD = MethodName.methodName("checkStock").unwrap();

    private static final Artifact PRICING_ARTIFACT =
        Artifact.artifact("org.pragmatica-lite.aether.demo:pricing-service:0.1.0").unwrap();
    private static final MethodName GET_PRICE_METHOD = MethodName.methodName("getPrice").unwrap();

    private final DemoCluster cluster;
    private final LoadGenerator loadGenerator;
    private final DemoMetrics metrics;
    private final LocalSliceInvoker sliceInvoker;
    private final List<DemoEvent> events = new CopyOnWriteArrayList<>();
    private final long startTime = System.currentTimeMillis();
    private volatile SimulatorConfig config = SimulatorConfig.defaultConfig();

    private DemoApiHandler(DemoCluster cluster, LoadGenerator loadGenerator, DemoMetrics metrics, LocalSliceInvoker sliceInvoker) {
        this.cluster = cluster;
        this.loadGenerator = loadGenerator;
        this.metrics = metrics;
        this.sliceInvoker = sliceInvoker;
    }

    public static DemoApiHandler demoApiHandler(DemoCluster cluster, LoadGenerator loadGenerator, DemoMetrics metrics, LocalSliceInvoker sliceInvoker) {
        return new DemoApiHandler(cluster, loadGenerator, metrics, sliceInvoker);
    }

    public int sliceCount() {
        return sliceInvoker.sliceCount();
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
            } else if (path.equals("/api/node-metrics") && method == HttpMethod.GET) {
                handleNodeMetrics(ctx);
            } else if (path.equals("/api/orders") && method == HttpMethod.POST) {
                handlePlaceOrder(ctx, request);
            } else if (path.startsWith("/api/orders/") && method == HttpMethod.GET) {
                handleGetOrderStatus(ctx, path.substring("/api/orders/".length()));
            } else if (path.startsWith("/api/orders/") && method == HttpMethod.DELETE) {
                handleCancelOrder(ctx, path.substring("/api/orders/".length()), request);
            } else if (path.startsWith("/api/inventory/") && method == HttpMethod.GET) {
                handleCheckStock(ctx, path.substring("/api/inventory/".length()));
            } else if (path.startsWith("/api/pricing/") && method == HttpMethod.GET) {
                handleGetPrice(ctx, path.substring("/api/pricing/".length()));
            } else if (path.equals("/api/simulator/metrics") && method == HttpMethod.GET) {
                handleSimulatorMetrics(ctx);
            } else if (path.startsWith("/api/simulator/rate/") && method == HttpMethod.POST) {
                handleSimulatorRate(ctx, path.substring("/api/simulator/rate/".length()), request);
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

        var response = new StatusResponse(clusterStatus, metricsSnapshot, loadStatus, uptimeSeconds(), sliceInvoker.sliceCount());
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

    private void handleNodeMetrics(ChannelHandlerContext ctx) {
        var nodeMetrics = cluster.nodeMetrics();
        var json = new StringBuilder("[");
        var first = true;
        for (var m : nodeMetrics) {
            if (!first) json.append(",");
            first = false;
            json.append("{")
                .append("\"nodeId\":\"").append(m.nodeId()).append("\",")
                .append("\"isLeader\":").append(m.isLeader()).append(",")
                .append("\"cpuUsage\":").append(String.format("%.3f", m.cpuUsage())).append(",")
                .append("\"heapUsedMb\":").append(m.heapUsedMb()).append(",")
                .append("\"heapMaxMb\":").append(m.heapMaxMb())
                .append("}");
        }
        json.append("]");
        sendResponse(ctx, OK, json.toString());
    }

    /**
     * Handle simulator metrics request - returns per-entry-point metrics.
     */
    private void handleSimulatorMetrics(ChannelHandlerContext ctx) {
        var snapshots = loadGenerator.entryPointMetrics().snapshot();
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
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);
            var rate = extractInt(body, "rate", -1);

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
            json.append("{\"name\":\"").append(ep).append("\",\"rate\":").append(rate).append("}");
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
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);
            var newConfig = SimulatorConfig.parseJson(body);
            config = newConfig;

            // Apply entry point rates to load generator
            for (var entryPoint : loadGenerator.entryPoints()) {
                var rate = config.effectiveRate(entryPoint);
                loadGenerator.setRate(entryPoint, rate);
            }

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
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("\"multiplier\"\\s*:\\s*([\\d.]+)");
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                var multiplier = Double.parseDouble(matcher.group(1));
                config = config.withGlobalMultiplier(multiplier);

                // Apply new rates
                for (var entryPoint : loadGenerator.entryPoints()) {
                    var rate = config.effectiveRate(entryPoint);
                    loadGenerator.setRate(entryPoint, rate);
                }

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
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("\"enabled\"\\s*:\\s*(true|false)");
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                var enabled = Boolean.parseBoolean(matcher.group(1));
                config = config.withLoadGeneratorEnabled(enabled);

                if (!enabled) {
                    // Set all rates to 0
                    for (var entryPoint : loadGenerator.entryPoints()) {
                        loadGenerator.setRate(entryPoint, 0);
                    }
                } else {
                    // Restore rates from config
                    for (var entryPoint : loadGenerator.entryPoints()) {
                        var rate = config.effectiveRate(entryPoint);
                        loadGenerator.setRate(entryPoint, rate);
                    }
                }

                addEvent("LOAD_GENERATOR", "Load generator " + (enabled ? "enabled" : "disabled"));
                sendResponse(ctx, OK, "{\"success\":true,\"enabled\":" + enabled + "}");
            } else {
                sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"Missing enabled\"}");
            }
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle get inventory mode.
     */
    private void handleGetInventoryMode(ChannelHandlerContext ctx) {
        var infinite = InventoryServiceSlice.isInfiniteMode();
        sendResponse(ctx, OK, "{\"mode\":\"" + (infinite ? "infinite" : "realistic") + "\"}");
    }

    /**
     * Handle set inventory mode.
     * Body: {"mode": "infinite"} or {"mode": "realistic"}
     */
    private void handleSetInventoryMode(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("\"mode\"\\s*:\\s*\"(\\w+)\"");
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                var mode = matcher.group(1);
                var infinite = "infinite".equalsIgnoreCase(mode);
                InventoryServiceSlice.setInfiniteMode(infinite);
                addEvent("INVENTORY_MODE", "Inventory mode set to " + (infinite ? "infinite" : "realistic"));
                sendResponse(ctx, OK, "{\"success\":true,\"mode\":\"" + (infinite ? "infinite" : "realistic") + "\"}");
            } else {
                sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"Missing mode\"}");
            }
        } catch (Exception e) {
            sendResponse(ctx, BAD_REQUEST, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle inventory metrics request.
     */
    private void handleInventoryMetrics(ChannelHandlerContext ctx) {
        var metrics = InventoryServiceSlice.getMetrics();
        var json = String.format(
            "{\"totalReservations\":%d,\"totalReleases\":%d,\"stockOuts\":%d,\"infiniteMode\":%b,\"refillRate\":%d}",
            metrics.totalReservations(), metrics.totalReleases(), metrics.stockOuts(),
            metrics.infiniteMode(), metrics.refillRate()
        );
        sendResponse(ctx, OK, json);
    }

    /**
     * Handle inventory stock levels request.
     */
    private void handleInventoryStock(ChannelHandlerContext ctx) {
        var levels = InventoryServiceSlice.getStockLevels();
        var json = new StringBuilder("{\"stock\":{");
        var first = true;
        for (var entry : levels.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
        }
        json.append("}}");
        sendResponse(ctx, OK, json.toString());
    }

    /**
     * Handle inventory reset.
     */
    private void handleInventoryReset(ChannelHandlerContext ctx) {
        InventoryServiceSlice.resetStock();
        addEvent("INVENTORY_RESET", "Inventory stock reset to initial levels");
        sendResponse(ctx, OK, "{\"success\":true}");
    }

    /**
     * Handle order placement requests by invoking PlaceOrderSlice.
     */
    private void handlePlaceOrder(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var body = request.content().toString(StandardCharsets.UTF_8);

            // Parse order request from body or generate a random one
            var orderRequest = parseOrderRequest(body);

            applySimulation("place-order")
                .flatMap(_ -> sliceInvoker.invokeAndWait(PLACE_ORDER_ARTIFACT, PLACE_ORDER_METHOD, orderRequest, PlaceOrderResponse.class))
                .onSuccess(response -> {
                    var total = response.total();
                    var totalStr = total.currency() + " " + total.amount().toPlainString();
                    var json = "{\"success\":true,\"orderId\":\"" + response.orderId().value() + "\"," +
                               "\"status\":\"" + response.status() + "\"," +
                               "\"total\":\"" + totalStr + "\"}";
                    sendResponse(ctx, OK, json);
                })
                .onFailure(cause -> {
                    sendResponse(ctx, INTERNAL_SERVER_ERROR,
                        "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
                });
        } catch (Exception e) {
            log.error("Error processing order: {}", e.getMessage());
            sendResponse(ctx, INTERNAL_SERVER_ERROR,
                "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Handle get order status requests by invoking GetOrderStatusSlice.
     */
    private void handleGetOrderStatus(ChannelHandlerContext ctx, String orderId) {
        var request = new GetOrderStatusRequest(orderId);

        applySimulation("get-order-status")
            .flatMap(_ -> sliceInvoker.invokeAndWait(GET_ORDER_STATUS_ARTIFACT, GET_ORDER_STATUS_METHOD, request, GetOrderStatusResponse.class))
            .onSuccess(response -> {
                var json = String.format(
                    "{\"success\":true,\"orderId\":\"%s\",\"status\":\"%s\",\"total\":\"%s %s\",\"itemCount\":%d}",
                    response.orderId().value(),
                    response.status(),
                    response.total().currency(),
                    response.total().amount().toPlainString(),
                    response.items().size()
                );
                sendResponse(ctx, OK, json);
            })
            .onFailure(cause -> {
                sendResponse(ctx, NOT_FOUND,
                    "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
            });
    }

    /**
     * Handle cancel order requests by invoking CancelOrderSlice.
     */
    private void handleCancelOrder(ChannelHandlerContext ctx, String orderId, FullHttpRequest request) {
        var body = request.content().toString(StandardCharsets.UTF_8);
        var reason = extractString(body, "reason", "User requested cancellation");

        var cancelRequest = new CancelOrderRequest(orderId, reason);

        applySimulation("cancel-order")
            .flatMap(_ -> sliceInvoker.invokeAndWait(CANCEL_ORDER_ARTIFACT, CANCEL_ORDER_METHOD, cancelRequest, CancelOrderResponse.class))
            .onSuccess(response -> {
                var json = String.format(
                    "{\"success\":true,\"orderId\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\"}",
                    response.orderId().value(),
                    response.status(),
                    escapeJson(response.reason())
                );
                sendResponse(ctx, OK, json);
            })
            .onFailure(cause -> {
                sendResponse(ctx, BAD_REQUEST,
                    "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
            });
    }

    /**
     * Handle check stock requests by invoking InventoryServiceSlice.
     */
    private void handleCheckStock(ChannelHandlerContext ctx, String productId) {
        var request = new CheckStockRequest(productId, 1);

        applySimulation("inventory-service")
            .flatMap(_ -> sliceInvoker.invokeAndWait(INVENTORY_ARTIFACT, CHECK_STOCK_METHOD, request, StockAvailability.class))
            .onSuccess(response -> {
                var json = String.format(
                    "{\"success\":true,\"productId\":\"%s\",\"available\":%d,\"sufficient\":%b}",
                    response.productId(),
                    response.available(),
                    response.sufficient()
                );
                sendResponse(ctx, OK, json);
            })
            .onFailure(cause -> {
                sendResponse(ctx, NOT_FOUND,
                    "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
            });
    }

    /**
     * Handle get price requests by invoking PricingServiceSlice.
     */
    private void handleGetPrice(ChannelHandlerContext ctx, String productId) {
        var request = new GetPriceRequest(productId);

        applySimulation("pricing-service")
            .flatMap(_ -> sliceInvoker.invokeAndWait(PRICING_ARTIFACT, GET_PRICE_METHOD, request, ProductPrice.class))
            .onSuccess(response -> {
                var json = String.format(
                    "{\"success\":true,\"productId\":\"%s\",\"price\":\"%s %s\"}",
                    response.productId(),
                    response.unitPrice().currency(),
                    response.unitPrice().amount().toPlainString()
                );
                sendResponse(ctx, OK, json);
            })
            .onFailure(cause -> {
                sendResponse(ctx, NOT_FOUND,
                    "{\"success\":false,\"error\":\"" + escapeJson(cause.message()) + "\"}");
            });
    }

    /**
     * Apply backend simulation for a slice based on current config.
     */
    private Promise<Unit> applySimulation(String sliceName) {
        var sliceConfig = config.sliceConfig(sliceName);
        return sliceConfig.buildSimulation().apply();
    }

    private PlaceOrderRequest parseOrderRequest(String body) {
        // For demo load testing, generate realistic orders
        // Each order has 1-3 items from the known product catalog
        var random = java.util.concurrent.ThreadLocalRandom.current();
        var products = List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789");
        // CustomerId requires 8 digits: CUST-12345678
        var customerId = String.format("CUST-%08d", random.nextInt(100_000_000));

        var itemCount = 1 + random.nextInt(3);
        var items = new ArrayList<OrderItemRequest>();
        for (int i = 0; i < itemCount; i++) {
            var productId = products.get(random.nextInt(products.size()));
            var quantity = 1 + random.nextInt(3);  // Keep quantity low
            items.add(new OrderItemRequest(productId, quantity));
        }

        // Optionally apply discount (20% chance)
        var discountCode = random.nextInt(5) == 0 ? "SAVE10" : null;

        return new PlaceOrderRequest(customerId, items, discountCode);
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
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, DELETE, OPTIONS");
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
               "\"uptimeSeconds\":" + response.uptimeSeconds + "," +
               "\"sliceCount\":" + response.sliceCount +
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

    private String extractString(String json, String key, String defaultValue) {
        var pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in API handler: {}", cause.getMessage());
        ctx.close();
    }

    private record StatusResponse(ClusterStatus cluster, MetricsSnapshot metrics, LoadStatus load, long uptimeSeconds, int sliceCount) {}
    private record LoadStatus(int currentRate, int targetRate, boolean running) {}
    private record DemoEvent(String timestamp, String type, String message) {}
}
