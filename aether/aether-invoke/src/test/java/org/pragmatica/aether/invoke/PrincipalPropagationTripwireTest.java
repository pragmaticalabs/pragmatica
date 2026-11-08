// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.pragmatica.lang.ContextPropagation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// #2157 part a (P9a): the caller's principal is NOT propagated across an inter-slice invoke, and nothing tested it.
///
/// Each behaviour that propagation can change has two tests beside each other (the guards marked "permanent pin" and the
/// local-hop guard have no twin and carry their own failure messages, not the LANDED text):
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
/// - ASYNC: a [SecurityContextHolder] binding read after a plain continuation (permanent pins, no twin) and after a
///   [ContextPropagation] executor-hop restore (tripwire + twin).
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

        assertThat(seen.securityContext().toOptional()).as(LANDED).isEmpty();
        assertThat(seen.tracePrincipal().toOptional()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when the receiving node injects the SecurityContext carried on InvokeRequest")
    @Test
    void remoteInvoke_downstreamSliceSeesTheCallersPrincipal() throws Exception {
        var seen = remoteHopSeenByDownstream();

        assertThat(principalOf(seen.securityContext())).contains(ALICE_NAME);
        assertThat(seen.tracePrincipal().toOptional()).contains(ALICE_NAME);
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

        assertThat(principalOf(seen.securityContext()))
            .as("local call: the holder binding is visible today (synchronous chain); if this fails the local hop regressed or propagation was reworked")
            .contains(ALICE_NAME);
        assertThat(seen.tracePrincipal().toOptional()).as("local call: trace principal").contains(ALICE_NAME);
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

    // ---- ASYNC boundaries (the suspected Terra/runAsync binding hazard) ---------------------------------------

    /// Two different async mechanisms, two different obligations:
    /// - A plain `Promise.map` continuation runs inline on whichever thread resolves the promise. It never passes
    ///   through [ContextPropagation], so no propagation fix can reach it. These are PERMANENT pins (no twin): a
    ///   non-empty holder there would mean the Promise continuation model changed.
    /// - An executor hop (`Promise.async`) captures a [ContextPropagation] snapshot and restores it on the worker.
    ///   That is where propagation can land: [AetherContextPropagation] would carry the [SecurityContext] next to the
    ///   trace identity it carries today. These have a tripwire + twin, and the tripwire's control assertion proves the
    ///   snapshot did restore the trace identity, so the empty holder is the SPI's omission and not a broken harness.
    private static final AetherContextPropagation PROPAGATION = new AetherContextPropagation();

    @Test
    void plainContinuation_afterBindingScopeEnded_readsEmptyHolder_permanentPin() {
        var gate = Promise.<Unit>promise();
        var seen = ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                              .call(() -> gate.map(_ -> SecurityContextHolder.currentContext()));

        gate.succeed(Unit.unit());

        assertThat(seen.await().unwrap().toOptional())
            .as("plain map continuation after the scope ended: a non-empty holder means the Promise continuation model changed")
            .isEmpty();
    }

    @Test
    void plainContinuation_resolvedOnAnotherThreadWhileScopeIsOpen_readsEmptyHolder_permanentPin() {
        var observed = resolvedOnAnotherThreadWhileScopeIsOpen();

        assertThat(observed.insideScope().toOptional()).as("control: the scope is open while the other thread resolves").contains(ALICE);
        assertThat(observed.continuation().await().unwrap().toOptional())
            .as("plain map continuation on a thread that does not inherit the binding: a non-empty holder means the Promise continuation model changed")
            .isEmpty();
    }

    @Test
    void executorHop_restoredAfterBindingScopeEnded_dropsSecurityContext_today() {
        var restored = restoreOnCallerThread(captureInAliceScope());

        assertThat(restored.tracePrincipal().toOptional()).as("control: the snapshot restored the trace identity").contains(ALICE_NAME);
        assertThat(restored.securityContext().toOptional()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when AetherContextPropagation carries the SecurityContext across executor hops")
    @Test
    void executorHop_restoredAfterBindingScopeEnded_carriesSecurityContext() {
        var restored = restoreOnCallerThread(captureInAliceScope());

        assertThat(restored.securityContext().toOptional()).contains(ALICE);
    }

    @Test
    void executorHop_restoredOnAnotherThread_dropsSecurityContext_today() {
        var restored = restoreOnAnotherThread(captureInAliceScope());

        assertThat(restored.tracePrincipal().toOptional()).as("control: the snapshot restored the trace identity").contains(ALICE_NAME);
        assertThat(restored.securityContext().toOptional()).as(LANDED).isEmpty();
    }

    @Disabled("#2157 part b: enable when AetherContextPropagation carries the SecurityContext across executor hops")
    @Test
    void executorHop_restoredOnAnotherThread_carriesSecurityContext() {
        var restored = restoreOnAnotherThread(captureInAliceScope());

        assertThat(restored.securityContext().toOptional()).contains(ALICE);
    }

    private record Observed(Option<SecurityContext> insideScope, Promise<Option<SecurityContext>> continuation) {}

    private record Restored(Option<SecurityContext> securityContext, Option<String> tracePrincipal) {}

    private static Observed resolvedOnAnotherThreadWhileScopeIsOpen() {
        var gate = Promise.<Unit>promise();
        var inside = new AtomicReference<Option<SecurityContext>>();
        var continuation = gate.map(_ -> SecurityContextHolder.currentContext());

        ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                   .run(() -> resolveOnAnotherThread(gate, inside));

        return new Observed(inside.get(), continuation);
    }

    private static void resolveOnAnotherThread(Promise<Unit> gate, AtomicReference<Option<SecurityContext>> inside) {
        inside.set(SecurityContextHolder.currentContext());
        runOnAnotherThreadAndJoin(() -> gate.succeed(Unit.unit()));
    }

    /// The snapshot `Promise.async` would take when called from the edge scope.
    private static Object captureInAliceScope() {
        var snapshot = new AtomicReference<Object>();

        inAliceScope(() -> snapshot.set(PROPAGATION.capture()));

        return snapshot.get();
    }

    private static Restored restoreOnCallerThread(Object snapshot) {
        var restored = new AtomicReference<Restored>();

        PROPAGATION.runWith(snapshot, () -> restored.set(observeRestored()));

        return restored.get();
    }

    private static Restored restoreOnAnotherThread(Object snapshot) {
        var restored = new AtomicReference<Restored>();

        runOnAnotherThreadAndJoin(() -> PROPAGATION.runWith(snapshot, () -> restored.set(observeRestored())));

        return restored.get();
    }

    private static Restored observeRestored() {
        return new Restored(SecurityContextHolder.currentContext(), InvocationContext.currentPrincipal());
    }

    private static void runOnAnotherThreadAndJoin(Runnable action) {
        var thread = Thread.ofPlatform().start(action);

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /// What the edge binds before dispatching into a slice (`AppHttpServer.dispatchScoped`).
    private static void inAliceScope(Runnable action) {
        ScopedValue.where(SecurityContextHolder.scopedValue(), ALICE)
                   .run(() -> InvocationContext.runWithContext("req-2157", ALICE_NAME, SENDER.id(), 0, true, action));
    }

    private static Optional<String> principalOf(Option<SecurityContext> context) {
        return context.map(SecurityContext::principal)
                      .map(principal -> principal.value())
                      .toOptional();
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
