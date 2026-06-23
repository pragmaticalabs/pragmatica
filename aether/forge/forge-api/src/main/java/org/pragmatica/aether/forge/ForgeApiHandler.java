// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.ForgeEvent;
import org.pragmatica.aether.forge.api.ForgeRouter;
import org.pragmatica.aether.forge.api.SimulatorRoutes.InventoryState;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.simulator.ChaosController;
import org.pragmatica.aether.forge.simulator.ChaosEvent;
import org.pragmatica.aether.forge.simulator.SimulatorConfig;
import org.pragmatica.aether.forge.simulator.SimulatorMode;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.ResponseSerializer;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.RequestContext.RequestContextImpl;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public final class ForgeApiHandler {
    private static final Logger log = LoggerFactory.getLogger(ForgeApiHandler.class);
    private static final int MAX_EVENTS = 100;

    private final RequestRouter router;
    private final JsonCodec jsonCodec;
    private final Deque<ForgeEvent> events;
    private final long startTime;
    private final AtomicLong requestCounter = new AtomicLong();
    private final Object modeLock = new Object();
    private volatile SimulatorConfig config = SimulatorConfig.simulatorConfig();
    private volatile SimulatorMode currentMode = SimulatorMode.DEVELOPMENT;
    private final ChaosController chaosController;
    private final InventoryState inventoryState;

    private ForgeApiHandler(EmberCluster cluster,
                            ForgeMetrics metrics,
                            ConfigurableLoadRunner configurableLoadRunner,
                            ChaosController chaosController,
                            InventoryState inventoryState,
                            Deque<ForgeEvent> events,
                            long startTime) {
        this.chaosController = chaosController;
        this.inventoryState = inventoryState;
        this.events = events;
        this.startTime = startTime;
        this.jsonCodec = JsonCodecAdapter.defaultCodec();
        this.router = ForgeRouter.forgeRouter(cluster,
                                              configurableLoadRunner,
                                              chaosController,
                                              this::getConfig,
                                              inventoryState,
                                              metrics,
                                              events,
                                              startTime,
                                              this::logEvent);
    }

    public static ForgeApiHandler forgeApiHandler(EmberCluster cluster,
                                                  ForgeMetrics metrics,
                                                  ConfigurableLoadRunner configurableLoadRunner) {
        var chaosController = ChaosController.chaosController(event -> executeChaosEvent(cluster, event));
        var inventoryState = InventoryState.inventoryState();
        var events = new ConcurrentLinkedDeque<ForgeEvent>();
        var startTime = System.currentTimeMillis();

        return new ForgeApiHandler(cluster,
                                   metrics,
                                   configurableLoadRunner,
                                   chaosController,
                                   inventoryState,
                                   events,
                                   startTime);
    }

    private static void executeChaosEvent(EmberCluster cluster, ChaosEvent event) {
        switch (event) {
            case ChaosEvent.NodeKill kill -> cluster.killNode(kill.nodeId(), false);
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

    public void handle(HttpRequest request, ResponseWriter response) {
        var path = request.path();

        log.debug("API request: {} {}", request.method(), path);
        try {
            var method = convertMethod(request.method());

            router.findRoute(method, path).onEmpty(() -> sendNotFound(response, path)).onPresent(route -> handleRoute(request,
                                                                                                                      response,
                                                                                                                      route,
                                                                                                                      path));
        } catch (Exception e) {
            log.error("Error handling API request: {}", e.getMessage(), e);
            sendError(response, HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void handleRoute(HttpRequest request, ResponseWriter response, Route<?> route, String path) {
        var requestId = "forge-" + requestCounter.incrementAndGet();
        var nettyRequest = createNettyRequest(request, route.path());
        var context = RequestContextImpl.requestContext(nettyRequest, route, jsonCodec, requestId);

        route.handler().handle(context).onSuccess(result -> sendSuccessResponse(response, route, result)).onFailure(cause -> sendError(response,
                                                                                                                                       HttpStatus.BAD_REQUEST,
                                                                                                                                       cause.message()));
    }

    private DefaultFullHttpRequest createNettyRequest(HttpRequest request, String routePath) {
        var method = switch (request.method()) {
            case GET -> HttpMethod.GET;
            case POST -> HttpMethod.POST;
            case PUT -> HttpMethod.PUT;
            case DELETE -> HttpMethod.DELETE;
            case PATCH -> HttpMethod.PATCH;
            case HEAD -> HttpMethod.HEAD;
            case OPTIONS -> HttpMethod.OPTIONS;
            case TRACE -> HttpMethod.TRACE;
            case CONNECT -> HttpMethod.CONNECT;
        };
        var uri = request.path();
        var queryString = buildQueryString(request.queryParams().asMap());

        if (!queryString.isEmpty()) {
            uri = uri + "?" + queryString;
        }

        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                                                      method,
                                                      uri,
                                                      Unpooled.wrappedBuffer(request.body()));

        for (var entry : request.headers().asMap().entrySet()) {
            for (var value : entry.getValue()) {
                nettyRequest.headers().add(entry.getKey(), value);
            }
        }

        return nettyRequest;
    }

    private String buildQueryString(java.util.Map<String, java.util.List<String>> params) {
        if (params.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();

        for (var entry : params.entrySet()) {
            for (var value : entry.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append("&");
                }

                sb.append(entry.getKey()).append("=").append(value);
            }
        }

        return sb.toString();
    }

    private void sendSuccessResponse(ResponseWriter response, Route<?> route, Object result) {
        var serverContentType = toServerContentType(route.contentType());

        ResponseSerializer.serialize(result, route.contentType(), jsonCodec)
                          .onSuccess(bytes -> response.write(HttpStatus.OK, bytes, serverContentType))
                          .onFailure(cause -> sendError(response, HttpStatus.INTERNAL_SERVER_ERROR, cause.message()));
    }

    private org.pragmatica.http.ContentType toServerContentType(org.pragmatica.http.ContentType routingContentType) {
        return switch (routingContentType.category()) {
            case JSON -> CommonContentType.APPLICATION_JSON;
            case TEXT -> CommonContentType.TEXT_PLAIN;
            case HTML -> CommonContentType.TEXT_HTML;
            case BINARY -> CommonContentType.APPLICATION_OCTET_STREAM;
            default -> CommonContentType.APPLICATION_OCTET_STREAM;
        };
    }

    private org.pragmatica.http.HttpMethod convertMethod(org.pragmatica.http.HttpMethod method) {
        return switch (method) {
            case GET -> org.pragmatica.http.HttpMethod.GET;
            case POST -> org.pragmatica.http.HttpMethod.POST;
            case PUT -> org.pragmatica.http.HttpMethod.PUT;
            case DELETE -> org.pragmatica.http.HttpMethod.DELETE;
            case PATCH -> org.pragmatica.http.HttpMethod.PATCH;
            case HEAD -> org.pragmatica.http.HttpMethod.HEAD;
            case OPTIONS -> org.pragmatica.http.HttpMethod.OPTIONS;
            case TRACE -> org.pragmatica.http.HttpMethod.TRACE;
            case CONNECT -> org.pragmatica.http.HttpMethod.CONNECT;
        };
    }

    private void sendNotFound(ResponseWriter response, String path) {
        log.debug("Route not found: {}", path);
        sendError(response, HttpStatus.NOT_FOUND, "Not found: " + path);
    }

    private void sendError(ResponseWriter response, HttpStatus status, String message) {
        var json = "{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}";

        response.write(status, json.getBytes(StandardCharsets.UTF_8), CommonContentType.APPLICATION_JSON);
    }

    public void addEvent(String type, String message) {
        var event = new ForgeEvent(Instant.now().toString(),
                                   type,
                                   "INFO",
                                   message);

        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }

        log.info("[EVENT] {}: {}", type, message);
    }

    public void addNodeEvent(String timestamp, String type, String severity, String message) {
        var event = new ForgeEvent(timestamp, type, severity, message);

        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    private String escapeJson(String str) {
        return Option.option(str)
                     .map(s -> s.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t"))
                     .or("");
    }
}
