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
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.CallerIdentity;
import org.pragmatica.aether.slice.CallerRequest;
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

/// #2157: the caller's identity across an inter-slice invoke.
///
/// Part a (P9a) landed enabled tripwires on the context-free behaviour with `@Disabled` twins; part b (P9b) flipped them:
/// the caller's SecurityContext, exactly as the edge validated it, is carried on [InvokeRequest] (`caller`) and reaches
/// the receiving slice through [SliceBridge#invokeAsCaller] as an explicit parameter, never through
/// SecurityContextHolder or a ScopedValue. The sender takes it from the explicit argument (a [CallerRequest] the
/// generated proxy builds), never from ambient state. Three kinds of test:
/// - Landed assertions (the former twins): the WIRE shape, the REMOTE value on the wire, delivery at the receiver.
/// - Permanent pins (own failure messages): ambient state at the receiver stays empty even when an identity arrives;
///   a context-free call puts no identity on the wire even from inside an edge scope; the local hop; plain
///   continuations.
/// - ENABLED hazard tripwires (no twin, own message): an executor hop does not carry the holder; red only if an unruled
///   second carrier appears.
///
/// What is observed, per hop:
/// - WIRE: the component list of [InvokeRequest].
/// - REMOTE: the sender runs inside an edge-style scope (a bound [SecurityContextHolder] and an [InvocationContext]
///   carrying a principal string, which is what `AppHttpServer.dispatchScoped` binds). The [InvokeRequest] it hands the
///   transport is inspected, then delivered to a second node's [InvocationHandler].
/// - LOCAL: the same sender scope, the target slice registered on the sender itself.
/// - ASYNC: a [SecurityContextHolder] binding read after a plain continuation (permanent pins) and after a
///   [ContextPropagation] executor-hop restore (hazard tripwires).
class PrincipalPropagationTripwireTest {
    private static final String HOLDER_CHANGED = "#2157: the ambient SecurityContextHolder now survives an executor hop; the ruled carrier is the explicit parameter, so revisit the async-hazard notes";
    private static final NodeId SENDER = new NodeId("sender-node");
    private static final NodeId RECEIVER = new NodeId("receiver-node");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:downstream:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();
    private static final SecurityContext ALICE = SecurityContext.securityContext("alice", Set.of(Role.SERVICE)).unwrap();
    private static final String ALICE_KEY = "alice";
    private static final String ALICE_NAME = ALICE.principal().value();
    private static final long TIMEOUT_MS = 2_000L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final long WAIT_SECONDS = 5L;

    private final RecordingNetwork network = new RecordingNetwork();
    private SliceInvoker invoker;
    private RecordingBridge senderBridge;

    @AfterEach
    void tearDown() {
        if (invoker != null) {
            invoker.stop().await();
        }
    }

    // ---- WIRE -----------------------------------------------------------------------------------------------

    @Test
    void invokeRequest_declaresASecurityContextComponent() {
        assertThat(componentSignatures()).as("InvokeRequest must carry the caller's SecurityContext")
                                         .contains("caller:" + Option.class.getName() + "<" + SecurityContext.class.getName() + ">");
    }

    /// A request built by a factory that takes no caller (every invoke that is not made on a caller's behalf) carries no
    /// identity, never a stand-in one.
    @Test
    void invokeRequest_factoriesWithoutACaller_carryNoIdentity() {
        var payload = new byte[]{1};
        var tenArg = InvokeRequest.invokeRequest(SENDER, "c", "r", ARTIFACT, METHOD, payload, true, 1, 0, false);
        var sevenArg = InvokeRequest.invokeRequest(SENDER, "c", "r", ARTIFACT, METHOD, payload, true);

        assertThat(tenArg.caller().isEmpty()).as("10-arg factory").isTrue();
        assertThat(sevenArg.caller().isEmpty()).as("7-arg factory").isTrue();
    }

    private static List<String> componentSignatures() {
        return Arrays.stream(InvokeRequest.class.getRecordComponents())
                     .map(component -> component.getName() + ":" + component.getGenericType().getTypeName())
                     .toList();
    }

    private static List<Object> componentValues(InvokeRequest request) {
        return Arrays.stream(InvokeRequest.class.getRecordComponents())
                     .map(component -> valueOf(component, request))
                     .toList();
    }

    private static Object valueOf(RecordComponent component, InvokeRequest request) {
        try {
            return component.getAccessor().invoke(request);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- REMOTE hop -----------------------------------------------------------------------------------------
    //
    // Ruling for the landing: the SecurityContext rides on InvokeRequest and reaches the slice as an explicit
    // parameter, NOT through SecurityContextHolder or a ScopedValue. So the tripwire and its twin observe the
    // request the sender hands the transport (the carrier); the receiving side's ambient state is a permanent pin.

    @Test
    void remoteInvoke_requestOnTheWireCarriesTheCallersContext() {
        assertThat(senderRequestFromAliceScope(callerRequest(ALICE)).caller()).isEqualTo(Option.some(ALICE));
        assertThat(senderBridge.encoded()).as("only the business request is encoded as the payload").containsExactly("request");
    }

    /// The sender never reads ambient state: a call made without an explicit caller, from inside a fully bound edge
    /// scope, puts no identity on the wire in any form. No twin.
    @Test
    void remoteInvoke_contextFreeCall_putsNoIdentityOnTheWire_permanentPin() {
        var request = senderRequestFromAliceScope("request");
        var values = componentValues(request);

        assertThat(request.caller()).as("a context-free call carries no caller").isEqualTo(Option.none());
        assertThat(values).as("a SecurityContext value is on the wire").noneMatch(SecurityContext.class::isInstance);
        assertThat(values.stream().filter(value -> !(value instanceof byte[])).map(String::valueOf))
            .as("the caller's identity is on the wire under another form")
            .noneMatch(text -> text.toLowerCase().contains(ALICE_KEY));
    }

    /// "No identity" travels as empty, never as a stand-in principal or a default role.
    @Test
    void remoteInvoke_unauthenticatedCaller_putsNoIdentityOnTheWire() {
        assertThat(senderRequestFromAliceScope(callerRequest(SecurityContext.securityContext())).caller()).isEqualTo(Option.none());
    }

    /// The receiving node hands the caller to the slice through the explicit-parameter path, unchanged.
    @Test
    void remoteInvoke_receiverDeliversTheCallerToTheSlice() throws Exception {
        assertThat(remoteHopSeenByDownstream(callerRequest(ALICE)).caller()).isEqualTo(Option.some(ALICE));
    }

    /// A forged context (ADMIN roles under a principal of the sender's choosing) is identity data only: the receiver
    /// passes it to the slice as such and binds nothing that any authorization reads. No route check runs on this path.
    @Test
    void remoteInvoke_forgedCaller_reachesOnlyTheExplicitParameter() throws Exception {
        var forged = SecurityContext.securityContext("mallory", Set.of(Role.ADMIN)).unwrap();
        var receiverBridge = new RecordingBridge();
        var receiver = InvocationHandler.invocationHandler(RECEIVER, new RecordingNetwork());

        receiver.registerSlice(ARTIFACT, receiverBridge);
        receiver.onInvokeRequest(InvokeRequest.invokeRequest(SENDER, "c-forged", "r-forged", ARTIFACT, METHOD, new byte[0], false, 1, 1, false, Option.some(forged)));
        var seen = receiverBridge.awaitSeen();

        assertThat(seen.caller()).isEqualTo(Option.some(forged));
        assertThat(seen.securityContext().toOptional()).as("a forged caller must not become the ambient authorization context").isEmpty();
        assertThat(seen.tracePrincipal().toOptional()).as("a forged caller must not become the trace principal").isEmpty();
    }

    /// The fire-and-forget path carries the caller the same way, and encodes only the business payload.
    @Test
    void remoteFireAndForget_carriesTheCaller() {
        startRemoteInvoker();
        inAliceScope(() -> invoker.invoke(ARTIFACT, METHOD, callerRequest(ALICE)));

        assertThat(network.awaitRequest().caller()).isEqualTo(Option.some(ALICE));
        assertThat(senderBridge.encoded()).as("only the business request is encoded as the payload").containsExactly("request");
    }

    /// The awaiting-completion path (#1723) carries the caller the same way.
    @Test
    void remoteAwaitingCompletion_carriesTheCaller() {
        startRemoteInvoker();
        invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, callerRequest(ALICE));

        assertThat(network.awaitRequest().caller()).isEqualTo(Option.some(ALICE));
        assertThat(senderBridge.encoded()).as("only the business request is encoded as the payload").containsExactly("request");
    }

    /// Affinity is resolved from the business request, never from its [CallerRequest] wrapper.
    @Test
    void remoteInvoke_affinityResolverSeesTheBusinessRequest() {
        var resolved = new AtomicReference<Object>();

        startRemoteInvoker();
        invoker.registerAffinityResolver(ARTIFACT, METHOD, request -> recordResolved(resolved, request));
        invoker.invoke(ARTIFACT, METHOD, callerRequest(ALICE), new TypeToken<String>() {});
        network.awaitRequest();

        assertThat(resolved.get()).isEqualTo("request");
    }

    private static Option<NodeId> recordResolved(AtomicReference<Object> resolved, Object request) {
        resolved.set(request);

        return Option.none();
    }

    /// The ambient state at the receiver stays empty by design under the ruling (no holder or ScopedValue carrier). A
    /// non-empty value here means a second, unruled carrier appeared. No twin.
    @Test
    void remoteInvoke_ambientContextDoesNotReachDownstream_permanentPin() throws Exception {
        var seen = remoteHopSeenByDownstream(callerRequest(ALICE));

        assertThat(seen.securityContext().toOptional()).as("ambient holder at the receiver: an unruled carrier appeared").isEmpty();
        assertThat(seen.tracePrincipal().toOptional()).as("ambient trace principal at the receiver: an unruled carrier appeared").isEmpty();
    }

    private Seen remoteHopSeenByDownstream(Object call) throws Exception {
        var request = senderRequestFromAliceScope(call);
        var receiverBridge = new RecordingBridge();
        var receiver = InvocationHandler.invocationHandler(RECEIVER, new RecordingNetwork());

        receiver.registerSlice(ARTIFACT, receiverBridge);
        receiver.onInvokeRequest(request);

        return receiverBridge.awaitSeen();
    }

    /// The sender is on [#SENDER]; its only endpoint for the method is on [#RECEIVER], so the call goes over the wire.
    private InvokeRequest senderRequestFromAliceScope(Object call) {
        startRemoteInvoker();
        inAliceScope(() -> invoker.invoke(ARTIFACT, METHOD, call, new TypeToken<String>() {}));

        return network.awaitRequest();
    }

    private void startRemoteInvoker() {
        var handler = InvocationHandler.invocationHandler(SENDER, network);

        senderBridge = new RecordingBridge();
        handler.registerSlice(ARTIFACT, senderBridge);
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

    @Test
    void localInvoke_theCallerReachesTheSliceThroughTheExplicitParameterPath() throws Exception {
        var bridge = new RecordingBridge();

        startLocalInvoker(bridge);
        inAliceScope(() -> invoker.invokeLocal(ARTIFACT, METHOD, callerRequest(ALICE), new TypeToken<String>() {}));

        assertThat(bridge.awaitSeen().caller()).isEqualTo(Option.some(ALICE));
        assertThat(bridge.encoded()).as("only the business request is encoded as the payload").containsExactly("request");
    }

    private static CallerRequest callerRequest(SecurityContext caller) {
        return CallerRequest.callerRequest("request", SecurityContext.callerIdentity(caller));
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
    ///   The ruled carrier for #2157 is the explicit parameter on [InvokeRequest], not the holder, so no twin exists:
    ///   these are enabled hazard tripwires that go red only if [AetherContextPropagation] starts carrying the
    ///   [SecurityContext] (an unruled second carrier). The control assertion proves the snapshot did restore the trace
    ///   identity, so the empty holder is the SPI's omission and not a broken harness.
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
        assertThat(restored.securityContext().toOptional()).as(HOLDER_CHANGED).isEmpty();
    }

    @Test
    void executorHop_restoredOnAnotherThread_dropsSecurityContext_today() {
        var restored = restoreOnAnotherThread(captureInAliceScope());

        assertThat(restored.tracePrincipal().toOptional()).as("control: the snapshot restored the trace identity").contains(ALICE_NAME);
        assertThat(restored.securityContext().toOptional()).as(HOLDER_CHANGED).isEmpty();
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

    private record Seen(Option<SecurityContext> securityContext,
                        Option<String> tracePrincipal,
                        Thread thread,
                        Option<CallerIdentity> caller) {}

    private static final class RecordingBridge implements SliceBridge {
        private final AtomicReference<Seen> seen = new AtomicReference<>();
        private final CountDownLatch invoked = new CountDownLatch(1);
        private final List<Object> encoded = new CopyOnWriteArrayList<>();

        List<Object> encoded() {
            return encoded;
        }

        Seen awaitSeen() throws InterruptedException {
            assertThat(invoked.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the downstream slice was invoked").isTrue();

            return seen.get();
        }

        @Override
        public Promise<byte[]> invoke(String methodName, byte[] input) {
            return invokeAsCaller(methodName, input, Option.none());
        }

        @Override
        public Promise<byte[]> invokeAsCaller(String methodName, byte[] input, Option<CallerIdentity> caller) {
            seen.set(new Seen(SecurityContextHolder.currentContext(), InvocationContext.currentPrincipal(), Thread.currentThread(), caller));
            invoked.countDown();

            return Promise.success(new byte[0]);
        }

        @Override
        public Promise<byte[]> encode(Object input) {
            encoded.add(input);

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
