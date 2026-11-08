// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;

/// #2157 part a (P9a): the caller's principal is NOT propagated across an inter-slice invoke, and nothing tested it.
///
/// Each behaviour has two tests beside each other:
/// - an ENABLED tripwire that asserts TODAY's behaviour and fails with "#2157: propagation landed; flip this
///   tripwire to the real assertion" when P9b lands, and
/// - the REAL assertion, `@Disabled` until then. Flipping means deleting the tripwire and enabling its twin.
///
/// What is observed, per hop:
/// - WIRE: the components of [InvokeRequest].
/// - REMOTE: the sender runs inside an edge-style scope (a bound [SecurityContextHolder] and an [InvocationContext]
///   carrying a principal string, which is what `AppHttpServer.dispatchScoped` binds). The [InvokeRequest] it hands the
///   transport is delivered to a second node's [InvocationHandler]; the slice there records what it can see.
/// - LOCAL: the same sender scope, the target slice registered on the sender itself.
/// - ASYNC: a [SecurityContextHolder] binding read from a continuation that runs after, or outside, the binding scope.
class PrincipalPropagationTripwireTest {
    private static final String LANDED = "#2157: propagation landed; flip this tripwire to the real assertion";
    private static final NodeId SENDER = new NodeId("sender-node");
    private static final NodeId RECEIVER = new NodeId("receiver-node");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:downstream:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();
    private static final SecurityContext ALICE = SecurityContext.securityContext("alice", Set.of(Role.SERVICE)).unwrap();
    private static final String ALICE_NAME = ALICE.principal().value();
    private static final long TIMEOUT_MS = 2_000L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final long WAIT_SECONDS = 5L;

    private final RecordingNetwork network = new RecordingNetwork();
    private SliceInvoker invoker;

    @AfterEach
    void tearDown() {
        if (invoker != null) {
            invoker.stop().await();
        }
    }

    // ---- WIRE -----------------------------------------------------------------------------------------------

    @Test
    void invokeRequest_carriesNoSecurityContext_today() {
        assertThat(securityComponents()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when InvokeRequest carries the SecurityContext")
    @Test
    void invokeRequest_carriesTheSecurityContext() {
        assertThat(securityComponents()).as("InvokeRequest must carry the caller's SecurityContext").isNotEmpty();
    }

    private static List<String> securityComponents() {
        return Arrays.stream(InvokeRequest.class.getRecordComponents())
                     .filter(PrincipalPropagationTripwireTest::isSecurityComponent)
                     .map(RecordComponent::getName)
                     .toList();
    }

    private static boolean isSecurityComponent(RecordComponent component) {
        return component.getType() == SecurityContext.class
               || component.getName().toLowerCase().contains("principal")
               || component.getName().toLowerCase().contains("security");
    }

    // ---- REMOTE hop -----------------------------------------------------------------------------------------

    @Test
    void remoteInvoke_downstreamSliceSeesNoPrincipal_today() throws Exception {
        var seen = remoteHopSeenByDownstream();

        assertThat(seen.securityContext()).as(LANDED).isEmpty();
        assertThat(seen.tracePrincipal()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when the receiving node injects the SecurityContext carried on InvokeRequest")
    @Test
    void remoteInvoke_downstreamSliceSeesTheCallersPrincipal() throws Exception {
        var seen = remoteHopSeenByDownstream();

        assertThat(seen.securityContext().map(SecurityContext::principal).map(p -> p.value())).contains(ALICE_NAME);
        assertThat(seen.tracePrincipal()).contains(ALICE_NAME);
    }

    private Seen remoteHopSeenByDownstream() throws Exception {
        var request = senderRequestFromAliceScope();
        var receiverBridge = new RecordingBridge();
        var receiver = InvocationHandler.invocationHandler(RECEIVER, new RecordingNetwork());

        receiver.registerSlice(ARTIFACT, receiverBridge);
        receiver.onInvokeRequest(request);

        return receiverBridge.awaitSeen();
    }

    /// The sender is on [#SENDER]; its only endpoint for the method is on [#RECEIVER], so the call goes over the wire.
    private InvokeRequest senderRequestFromAliceScope() {
        var handler = InvocationHandler.invocationHandler(SENDER, network);

        handler.registerSlice(ARTIFACT, new RecordingBridge());
        var registry = EndpointRegistry.endpointRegistry();

        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(RECEIVER));
        invoker = SliceInvoker.sliceInvoker(SENDER,
                                            network,
                                            registry,
                                            handler,
                                            new StubSerializer(),
                                            new StubDeserializer(),
                                            TIMEOUT_MS,
                                            CLEANUP_INTERVAL_MS,
                                            0,
                                            new StubDeploymentManager());
        inAliceScope(() -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}));

        return network.awaitRequest();
    }

    // ---- LOCAL call -----------------------------------------------------------------------------------------

    /// Today a LOCAL inter-slice call keeps the SecurityContextHolder binding only because the whole chain runs
    /// synchronously on the caller's thread (the encode/invoke/decode steps are already-resolved promises). This is
    /// the one hop where the principal survives, and it does so by accident of threading, not by design.
    @Test
    void localInvoke_downstreamSliceSeesTheBindingOnlyBecauseTheChainIsSynchronous_today() throws Exception {
        var bridge = new RecordingBridge();

        startLocalInvoker(bridge);
        inAliceScope(() -> invoker.invokeLocal(ARTIFACT, METHOD, "request", new TypeToken<String>() {}));
        var seen = bridge.awaitSeen();

        assertThat(seen.securityContext().map(SecurityContext::principal).map(p -> p.value()))
            .as("local call: the holder binding is visible today (synchronous chain); if this fails the local hop regressed or propagation was reworked")
            .contains(ALICE_NAME);
        assertThat(seen.tracePrincipal()).as("local call: trace principal").contains(ALICE_NAME);
        assertThat(seen.thread()).as("it ran on the caller's own thread").isEqualTo(Thread.currentThread());
    }

    private void startLocalInvoker(RecordingBridge bridge) {
        var handler = InvocationHandler.invocationHandler(SENDER, network);

        handler.registerSlice(ARTIFACT, bridge);
        invoker = SliceInvoker.sliceInvoker(SENDER,
                                            network,
                                            EndpointRegistry.endpointRegistry(),
                                            handler,
                                            new StubSerializer(),
                                            new StubDeserializer(),
                                            TIMEOUT_MS,
                                            CLEANUP_INTERVAL_MS,
                                            0,
                                            new StubDeploymentManager());
    }

    // ---- ASYNC continuation (the suspected Terra/runAsync binding hazard) ------------------------------------

    /// The hazard behind Terra's `TerraHttpRoutes.invoke`: a ScopedValue binding lives only while its scope is on the
    /// stack. A continuation attached inside the scope but resolved after the scope returned runs outside it.
    @Test
    void asyncContinuation_afterBindingScopeEnded_readsEmptyHolder_today() {
        var gate = Promise.<Unit>promise();
        var seen = ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                              .call(() -> gate.map(_ -> SecurityContextHolder.currentContext()));

        gate.succeed(Unit.unit());

        assertThat(seen.await().unwrap()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when the generated code passes the edge-captured context instead of reading the holder")
    @Test
    void asyncContinuation_afterBindingScopeEnded_readsTheCallersContext() {
        var gate = Promise.<Unit>promise();
        var seen = ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                              .call(() -> gate.map(_ -> SecurityContextHolder.currentContext()));

        gate.succeed(Unit.unit());

        assertThat(seen.await().unwrap()).contains(ALICE);
    }

    /// Same hazard while the scope is still on the stack: the continuation runs on whichever thread resolves the
    /// promise, and a ScopedValue binding is not inherited by a plain thread.
    @Test
    void asyncContinuation_resolvedOnAnotherThread_readsEmptyHolder_today() throws Exception {
        var gate = Promise.<Unit>promise();
        var seen = new AtomicReference<Promise<Option<SecurityContext>>>();

        ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                   .run(() -> seen.set(gate.map(_ -> SecurityContextHolder.currentContext())));
        var resolver = Thread.ofPlatform().start(() -> gate.succeed(Unit.unit()));

        resolver.join();
        assertThat(seen.get().await().unwrap()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when the context is carried across runAsync / registered as a ContextPropagation")
    @Test
    void asyncContinuation_resolvedOnAnotherThread_readsTheCallersContext() throws Exception {
        var gate = Promise.<Unit>promise();
        var seen = new AtomicReference<Promise<Option<SecurityContext>>>();

        ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                   .run(() -> seen.set(gate.map(_ -> SecurityContextHolder.currentContext())));
        var resolver = Thread.ofPlatform().start(() -> gate.succeed(Unit.unit()));

        resolver.join();
        assertThat(seen.get().await().unwrap()).contains(ALICE);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /// What the edge binds before dispatching into a slice (`AppHttpServer.dispatchScoped`).
    private static void inAliceScope(Runnable action) {
        ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                   .run(() -> InvocationContext.runWithContext("req-2157", ALICE_NAME, SENDER.id(), 0, true, action));
    }

    private record Seen(Option<SecurityContext> securityContext, Option<String> tracePrincipal, Thread thread) {}

    private static final class RecordingBridge implements SliceBridge {
        private final AtomicReference<Seen> seen = new AtomicReference<>();
        private final CountDownLatch invoked = new CountDownLatch(1);

        Seen awaitSeen() throws InterruptedException {
            assertThat(invoked.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the downstream slice was invoked").isTrue();

            return seen.get();
        }

        @Override
        public Promise<byte[]> invoke(String methodName, byte[] input) {
            seen.set(new Seen(SecurityContextHolder.currentContext(), InvocationContext.currentPrincipal(), Thread.currentThread()));
            invoked.countDown();

            return Promise.success(new byte[0]);
        }

        @Override
        public Promise<byte[]> encode(Object input) {
            return Promise.success(new byte[0]);
        }

        @Override
        public Promise<Object> decode(byte[] bytes) {
            return Promise.success("response");
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

    private static final class RecordingNetwork extends StubClusterNetwork {
        private final List<InvokeRequest> requests = new CopyOnWriteArrayList<>();

        InvokeRequest awaitRequest() {
            var limit = System.currentTimeMillis() + WAIT_SECONDS * 1_000L;

            while (requests.isEmpty() && System.currentTimeMillis() < limit) {
                Thread.onSpinWait();
            }

            assertThat(requests).as("the sender handed an InvokeRequest to the transport").isNotEmpty();

            return requests.getFirst();
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest request) {
                requests.add(request);
            }

            return Promise.success(new WriteOutcome.Sent(nodeId));
        }
    }
}
