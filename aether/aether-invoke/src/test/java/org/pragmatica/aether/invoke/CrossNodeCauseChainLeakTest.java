// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2101: a chained error raised in a slice on node B, invoked from node A's HTTP route, must not show its inner
/// detail in A's client body, and B's log must carry it. Real `InvocationHandler` on B and real `SliceInvoker` on A,
/// joined by a loopback network that only moves the messages (no stub between the handler's reply and the invoker's
/// decode); the HTTP rendering is the real `SliceRouter`. The detail travels node to node as the callee's wire text,
/// so the callee renders it.
class CrossNodeCauseChainLeakTest {
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("processRequest").unwrap();
    private static final String HIDDEN = "SENTINEL-deep-origin-detail";
    private static final String VISIBLE = "top-level-slice-failure";

    record Chained(String message, Cause origin) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(origin);
        }
    }

    /// Moves messages between the two nodes' real handlers/invokers; delivery is the only thing it does.
    private static final class Loopback extends StubClusterNetwork {
        private final Map<NodeId, InvocationHandler> handlers = new ConcurrentHashMap<>();
        private volatile SliceInvoker invokerA;

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            switch (message) {
                case InvokeRequest request -> handlers.get(nodeId).onInvokeRequest(request);
                case InvokeResponse response -> invokerA.onInvokeResponse(response);
                default -> {}
            }

            return unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            return send(nodeId, message);
        }
    }

    private static SliceBridge failingBridge(Cause failure) {
        return new SliceBridge() {
            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                return failure.promise();
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<byte[]> encode(Object input) {
                return Promise.success(new byte[0]);
            }

            @Override
            public ClassLoader classLoader() {
                return CrossNodeCauseChainLeakTest.class.getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(METHOD.name());
            }
        };
    }

    private static Runnable capture(Class<?> owner, List<String> sink) {
        var name = owner.getName();
        var context = (LoggerContext) LogManager.getContext(false);
        var loggerConfig = context.getConfiguration().getLoggerConfig(name);
        var appender = new AbstractAppender("capture-" + owner.getSimpleName(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (name.equals(event.getLoggerName())) {
                    synchronized (sink) {
                        sink.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
                    }
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.ALL, null);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        };
    }

    /// The failure A's HTTP route sees for a call whose callee B failed with `calleeFailure`, rendered by the real router.
    private static String clientBodyOnA(Cause calleeFailure, List<String> calleeLog) {
        var network = new Loopback();
        var handlerA = InvocationHandler.invocationHandler(NODE_A, network);
        var handlerB = InvocationHandler.invocationHandler(NODE_B, network);
        var registry = EndpointRegistry.endpointRegistry();

        handlerA.registerSlice(ARTIFACT, failingBridge(calleeFailure));
        handlerB.registerSlice(ARTIFACT, failingBridge(calleeFailure));
        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(NODE_B));
        network.handlers.put(NODE_B, handlerB);

        var invokerA = SliceInvoker.sliceInvoker(NODE_A,
                                                 network,
                                                 registry,
                                                 handlerA,
                                                 new StubSerializer(),
                                                 new StubDeserializer(),
                                                 30_000L,
                                                 60_000L,
                                                 new StubDeploymentManager());
        var detach = capture(InvocationHandlerImpl.class, calleeLog);

        network.invokerA = invokerA;
        try {
            Route<String> route = Route.route(HttpMethod.GET,
                                              "/call",
                                              ctx -> invokerA.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}),
                                              CommonContentType.APPLICATION_JSON,
                                              List.of(),
                                              "call");
            RouteSource source = () -> Stream.of(route);
            ErrorMapper mapper = cause -> cause instanceof HttpError he
                                          ? he
                                          : HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
            var request = HttpRequestContext.httpRequestContext("/call", "GET", Map.of(), Map.of(), "req_2101");
            var response = SliceRouter.sliceRouter(source, mapper, JsonMapper.defaultJsonMapper())
                                      .handle(request)
                                      .await(TimeSpan.timeSpan(10).seconds())
                                      .unwrap();

            return new String(response.body(), StandardCharsets.UTF_8);
        } finally {
            detach.run();
            invokerA.stop().await();
        }
    }

    private static HttpError chainedHttpError() {
        return HttpError.httpError(HttpStatus.BAD_GATEWAY,
                                   HttpError.httpError(HttpStatus.CONFLICT, new Chained(VISIBLE, Causes.cause(HIDDEN))));
    }

    @Test
    void calleeChain_control_theChainWalkerSeesTheSentinel() {
        assertThat(chainedHttpError().message()).contains(VISIBLE).contains(HIDDEN);
    }

    @Test
    void chainedErrorOnNodeB_isAbsentFromNodeAClientBody() {
        var calleeLog = new ArrayList<String>();
        var body = clientBodyOnA(chainedHttpError(), calleeLog);

        assertThat(body).as("the call must have failed through the remote path, not vanished").contains(VISIBLE);
        assertThat(body).doesNotContain(HIDDEN);
    }

    @Test
    void chainedErrorOnNodeB_isInNodeBLog_withBothIdsToJoinTheNodes() {
        var calleeLog = new ArrayList<String>();

        clientBodyOnA(chainedHttpError(), calleeLog);

        synchronized (calleeLog) {
            assertThat(String.join("\n", calleeLog)).contains(HIDDEN).containsPattern("requestId=\\S+\\] Failed to complete invocation \\[[^\\]]+\\]");
        }
    }
}
