// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public interface InvocationHandler {
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onInvokeRequest(InvokeRequest request);

    @SuppressWarnings("JBCT-RET-01")
    void registerSlice(Artifact artifact, SliceBridge bridge);

    @SuppressWarnings("JBCT-RET-01")
    void unregisterSlice(Artifact artifact);

    Option<SliceBridge> localSlice(Artifact artifact);
    Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader);
    Option<InvocationMetricsCollector> metricsCollector();
    TimeSpan DEFAULT_INVOCATION_TIMEOUT = timeSpan(15).seconds();

    /// Bind the write-side registrar so a loaded slice's per-method observability cells (#277 increment
    /// 2) are registered at registerSlice and dropped at unregisterSlice. Default no-op keeps stubs and
    /// observability-off nodes at passthrough.
    default Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
        return Unit.unit();
    }

    static InvocationHandler invocationHandler(NodeId self, ClusterNetwork network) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.none(),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.none(),
                                         Option.none(),
                                         Option.none());
    }

    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.none(),
                                         Option.none(),
                                         Option.none());
    }

    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               HttpRoutePublisher httpRoutePublisher) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.option(serializer),
                                         Option.option(deserializer),
                                         Option.option(httpRoutePublisher));
    }

    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               TimeSpan invocationTimeout) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         invocationTimeout,
                                         Option.none(),
                                         Option.none(),
                                         Option.none());
    }

    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               TimeSpan invocationTimeout,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               HttpRoutePublisher httpRoutePublisher) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         invocationTimeout,
                                         Option.option(serializer),
                                         Option.option(deserializer),
                                         Option.option(httpRoutePublisher));
    }
}

class InvocationHandlerImpl implements InvocationHandler {
    private static final Logger log = LoggerFactory.getLogger(InvocationHandlerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final Option<InvocationMetricsCollector> metricsCollector;
    private final TimeSpan invocationTimeout;
    private final Option<Serializer> serializer;
    private final Option<Deserializer> deserializer;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final Map<Artifact, SliceBridge> localSlices = new ConcurrentHashMap<>();
    private final Map<ClassLoader, SliceBridge> classLoaderBridges = new ConcurrentHashMap<>();
    private volatile ObservabilityCellRegistrar cellRegistrar = ObservabilityCellRegistrar.NOOP;

    InvocationHandlerImpl(NodeId self,
                          ClusterNetwork network,
                          Option<InvocationMetricsCollector> metricsCollector,
                          TimeSpan invocationTimeout,
                          Option<Serializer> serializer,
                          Option<Deserializer> deserializer,
                          Option<HttpRoutePublisher> httpRoutePublisher) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.invocationTimeout = invocationTimeout;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.httpRoutePublisher = httpRoutePublisher;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
        this.cellRegistrar = registrar;

        return Unit.unit();
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void registerSlice(Artifact artifact, SliceBridge bridge) {
        localSlices.put(artifact, bridge);
        classLoaderBridges.put(bridge.classLoader(), bridge);
        bridge.observabilityCells().forEach(cellRegistrar::register);
        log.debug("Registered slice for invocation: {}", artifact);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void unregisterSlice(Artifact artifact) {
        var bridge = localSlices.remove(artifact);

        if (bridge != null) {
            classLoaderBridges.remove(bridge.classLoader());
            bridge.observabilityCells().forEach(cellRegistrar::deregister);
        }

        log.debug("Unregistered slice from invocation: {}", artifact);
    }

    @Override
    public Option<SliceBridge> localSlice(Artifact artifact) {
        return Option.option(localSlices.get(artifact));
    }

    @Override
    public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
        return Option.option(classLoaderBridges.get(classLoader));
    }

    @Override
    public Option<InvocationMetricsCollector> metricsCollector() {
        return metricsCollector;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onInvokeRequest(InvokeRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Received InvokeRequest [{}]: {}.{}",
                      request.requestId(),
                      request.correlationId(),
                      request.targetSlice(),
                      request.method());
        }

        InvocationContext.runWithContext(request.requestId(),
                                         null,
                                         null,
                                         request.depth(),
                                         request.sampled(),
                                         () -> processInvokeRequest(request));
    }

    private void processInvokeRequest(InvokeRequest request) {
        Option.option(localSlices.get(request.targetSlice())).onEmpty(() -> handleSliceNotFound(request)).onPresent(bridge -> invokeSliceMethod(request,
                                                                                                                                                bridge));
    }

    private void handleSliceNotFound(InvokeRequest request) {
        log.warn("[requestId={}] Slice not found for invocation: {}", request.requestId(), request.targetSlice());
        if (request.expectResponse()) {
            sendErrorResponse(request, "Slice not found: " + request.targetSlice());
        }
    }

    private void invokeSliceMethod(InvokeRequest request, SliceBridge bridge) {
        var startTime = System.nanoTime();
        var requestBytes = request.payload().length;

        metricsCollector.onPresent(mc -> mc.recordStart(request.targetSlice(), request.method()));
        ObservabilityCells.around(bridge,
                                  request.method().name(),
                                  () -> invokeWithHttpRouting(request, bridge)).timeout(invocationTimeout).onSuccess(data -> handleInvocationSuccess(request,
                                                                                                                                                     data,
                                                                                                                                                     startTime,
                                                                                                                                                     requestBytes)).onFailure(cause -> handleInvocationFailure(request,
                                                                                                                                                                                                               cause,
                                                                                                                                                                                                               startTime,
                                                                                                                                                                                                               requestBytes));
    }

    private Promise<byte[]> invokeWithHttpRouting(InvokeRequest request, SliceBridge bridge) {
        return bridge.invoke(request.method().name(),
                             request.payload());
    }

    private void handleInvocationSuccess(InvokeRequest request, byte[] responseData, long startTime, int requestBytes) {
        var durationNs = System.nanoTime() - startTime;
        var responseBytes = responseData.length;

        if (request.expectResponse()) {
            sendSuccessResponse(request, responseData);
        }

        log.debug("[requestId={}] Invocation completed in {}ms: {}.{}",
                  request.requestId(),
                  durationNs / 1_000_000,
                  request.targetSlice(),
                  request.method());
        metricsCollector.onPresent(mc -> recordSuccessMetrics(mc, request, durationNs, requestBytes, responseBytes));
    }

    private void recordSuccessMetrics(InvocationMetricsCollector mc,
                                      InvokeRequest request,
                                      long durationNs,
                                      int requestBytes,
                                      int responseBytes) {
        mc.recordComplete(request.targetSlice(), request.method());
        mc.recordSuccess(request.targetSlice(), request.method(), durationNs, requestBytes, responseBytes);
    }

    private void handleInvocationFailure(InvokeRequest request, Cause cause, long startTime, int requestBytes) {
        var durationNs = System.nanoTime() - startTime;
        var errorType = cause.getClass().getSimpleName();

        log.error("[requestId={}] Failed to complete invocation [{}]: {}",
                  request.requestId(),
                  request.correlationId(),
                  cause.message());
        if (request.expectResponse()) {
            sendErrorResponse(request, cause.message());
        }

        metricsCollector.onPresent(mc -> recordFailureMetrics(mc, request, durationNs, requestBytes, errorType));
    }

    private void recordFailureMetrics(InvocationMetricsCollector mc,
                                      InvokeRequest request,
                                      long durationNs,
                                      int requestBytes,
                                      String errorType) {
        mc.recordComplete(request.targetSlice(), request.method());
        mc.recordFailure(request.targetSlice(), request.method(), durationNs, requestBytes, errorType);
    }

    private void sendSuccessResponse(InvokeRequest request, byte[] payload) {
        var response = InvokeResponse.invokeResponse(self, request.correlationId(), request.requestId(), true, payload);

        network.send(request.sender(), response);
        log.debug("[requestId={}] Sent success response [{}]", request.requestId(), request.correlationId());
    }

    private void sendErrorResponse(InvokeRequest request, String errorMessage) {
        var response = InvokeResponse.invokeResponse(self,
                                                     request.correlationId(),
                                                     request.requestId(),
                                                     false,
                                                     errorMessage.getBytes(StandardCharsets.UTF_8));

        network.send(request.sender(), response);
        log.debug("[requestId={}] Sent error response [{}]: {}",
                  request.requestId(),
                  request.correlationId(),
                  errorMessage);
    }
}
