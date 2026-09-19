// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;

/// #1295: a durable-topic delivery hands its [MessageContext] to the subscriber through
/// [SliceInvoker#invokeLocalWithContext]. The context lives only in-process — it is not part of any wire
/// message — so this path must reach the LOCAL bridge and nothing else. It resolves the slice exactly as
/// `invokeLocal` does and fails, rather than forwards, when the slice is not local.
class SliceInvokerMessageContextTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:orders:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("onPlacedWithContext").unwrap();
    private static final MessageContext CONTEXT = MessageContext.messageContext("2mKsuidMessageId",
                                                                                "org.example:order-events:1.0.0",
                                                                                0,
                                                                                7L);

    @Test
    void invokeLocalWithContext_handsEventBytesAndContextToTheLocalBridge() {
        var self = new NodeId("self");
        var network = new CountingNetwork();
        var handler = InvocationHandler.invocationHandler(self, network);
        var bridge = new RecordingBridge();

        handler.registerSlice(ARTIFACT, bridge);

        invoker(self, network, handler).invokeLocalWithContext(ARTIFACT, METHOD, new byte[]{9}, CONTEXT)
                                       .await()
                                       .onFailure(cause -> fail("a local slice must receive the delivery: " + cause.message()));

        assertThat(bridge.method.get()).isEqualTo(METHOD.name());
        assertThat(bridge.eventBytes.get()).containsExactly(9);
        assertThat(bridge.context.get()).isEqualTo(CONTEXT);
        assertThat(network.sends.get()).as("an in-process delivery sends nothing over the network").isZero();
    }

    /// THE locality pin (adopted from review rev1310, whose mutation M6 — a forwarding implementation —
    /// left the older negative test green): the slice IS local AND the method's only registered endpoint
    /// is REMOTE, so any implementation that consults endpoint selection would send. Delivery must stay
    /// in-process with zero sends; a forward would drop the context, which no wire message carries.
    @Test
    void invokeLocalWithContext_deliversInProcess_evenWhenTheOnlyEndpointIsRemote() {
        var self = new NodeId("self");
        var network = new CountingNetwork();
        var registry = EndpointRegistry.endpointRegistry();
        var handler = InvocationHandler.invocationHandler(self, network);
        var bridge = new RecordingBridge();

        handler.registerSlice(ARTIFACT, bridge);
        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(new NodeId("remote")));

        SliceInvoker.sliceInvoker(self,
                                  network,
                                  registry,
                                  handler,
                                  new StubSerializer(),
                                  new StubDeserializer(),
                                  new StubDeploymentManager())
                    .invokeLocalWithContext(ARTIFACT, METHOD, new byte[]{9}, CONTEXT)
                    .await()
                    .onFailure(cause -> fail("a local slice must receive the delivery in-process: " + cause.message()));

        assertThat(network.sends.get()).as("the context path never forwards, even to a registered remote endpoint")
                                       .isZero();
        assertThat(bridge.context.get()).isEqualTo(CONTEXT);
    }

    /// With the slice absent locally the delivery FAILS rather than being reported delivered. (This alone
    /// does not pin locality: a forwarding implementation also fails here, for want of an endpoint — the
    /// test above is the one that does.)
    @Test
    void invokeLocalWithContext_fails_whenTheSliceIsNotLocal() {
        var self = new NodeId("self");
        var network = new CountingNetwork();
        var handler = InvocationHandler.invocationHandler(self, network);

        invoker(self, network, handler).invokeLocalWithContext(ARTIFACT, METHOD, new byte[]{9}, CONTEXT)
                                       .await()
                                       .onSuccess(_ -> fail("a non-local slice must not be reported as delivered"));

        assertThat(network.sends.get()).as("the context path never forwards to another node").isZero();
    }

    private static SliceInvoker invoker(NodeId self, CountingNetwork network, InvocationHandler handler) {
        return SliceInvoker.sliceInvoker(self,
                                         network,
                                         EndpointRegistry.endpointRegistry(),
                                         handler,
                                         new StubSerializer(),
                                         new StubDeserializer(),
                                         new StubDeploymentManager());
    }

    private static final class CountingNetwork extends StubClusterNetwork {
        private final AtomicInteger sends = new AtomicInteger();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sends.incrementAndGet();
            return unit();
        }
    }

    private static final class RecordingBridge implements SliceBridge {
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<byte[]> eventBytes = new AtomicReference<>();
        private final AtomicReference<MessageContext> context = new AtomicReference<>();

        @Override
        public Promise<byte[]> invokeWithContext(String methodName, byte[] bytes, MessageContext messageContext) {
            method.set(methodName);
            eventBytes.set(bytes);
            context.set(messageContext);
            return Promise.success(new byte[0]);
        }

        @Override
        public Promise<byte[]> invoke(String methodName, byte[] input) {
            return Promise.success(new byte[0]);
        }

        /// Encodes successfully, so a forwarding implementation would get as far as the SEND — the
        /// locality pin then fails on the send count rather than on an unrelated encode refusal.
        @Override
        public Promise<byte[]> encode(Object input) {
            return Promise.success(new byte[0]);
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
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public List<String> methodNames() {
            return List.of(METHOD.name());
        }
    }
}
