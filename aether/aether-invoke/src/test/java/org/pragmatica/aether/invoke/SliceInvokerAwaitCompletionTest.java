// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1723: a REMOTE scheduled-task fire used to resolve the moment the request was handed to the transport, so a
/// message the transport lost, or a callee that failed, still read as a successful execution and was counted in the
/// task's history. `invokeAwaitingCompletion` asks the callee for a response and settles with it: success only when the
/// callee answered success, failure on a callee failure and on a response that never arrives.
class SliceInvokerAwaitCompletionTest {
    private static final NodeId SELF = new NodeId("leader");
    private static final NodeId HOST = new NodeId("host");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("heartbeat").unwrap();
    /// Short, so the dropped-message case settles quickly.
    private static final long TIMEOUT_MS = 300L;

    private final CapturingNetwork network = new CapturingNetwork();
    private SliceInvoker invoker;

    @BeforeEach
    void setUp() {
        var registry = EndpointRegistry.endpointRegistry();
        var handler = InvocationHandler.invocationHandler(SELF, network);
        var nodeCodec = FrameworkCodecs.frameworkCodecs();

        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(HOST));
        invoker = SliceInvoker.sliceInvoker(SELF, network, registry, handler, nodeCodec, nodeCodec, TIMEOUT_MS, 60_000L, new StubDeploymentManager());
    }

    @AfterEach
    void tearDown() {
        invoker.stop().await();
    }

    @Test
    @Timeout(30)
    void remoteFire_isNotCompleteWhenOnlyEnqueued_andSucceedsWhenTheCalleeAnswersSuccess() {
        var completion = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        assertThat(network.sent.get()).as("the request reached the transport").isNotNull();
        assertThat(network.sent.get().expectResponse()).as("it asks the callee for a response").isTrue();
        assertThat(completion.isResolved()).as("handing the request to the transport is not completion").isFalse();

        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, network.sent.get().correlationId(), "r", true, new byte[0]));

        assertThat(completion.await().isSuccess()).as("the callee answered success").isTrue();
    }

    @Test
    @Timeout(30)
    void remoteFire_failsWhenTheCalleeFails() {
        var completion = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST,
                                                               network.sent.get().correlationId(),
                                                               "r",
                                                               false,
                                                               "callee blew up".getBytes(StandardCharsets.UTF_8)));

        completion.await().onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a callee failure must fail the fire"))
                  .onFailure(cause -> assertThat(cause.message()).contains("callee blew up"));
    }

    /// A lost message (a transport that silently discards on a peer reset, as seen in #1677) produces no response at all.
    /// The fire must not read as executed, and must not read as FAILED either: with no response the outcome is UNKNOWN
    /// (the callee may have run it), a distinct typed cause the scheduler records as such.
    @Test
    @Timeout(30)
    void remoteFire_withNoResponseInTime_isAnUnknownOutcome_notAFailureAndNotASuccess() {
        invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit())
               .await()
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a fire whose request was lost must not read as executed"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(SliceInvokerError.CompletionUnknown.class));
    }

    /// A callee that answered with a failure is a failure, and a callee whose node departed is a failure too: only a
    /// missing response is unknown.
    @Test
    @Timeout(30)
    void remoteFire_calleeFailureAndDepartedNode_areFailures_notUnknown() {
        var failed = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, network.sent.get().correlationId(), "r", false, "boom".getBytes(StandardCharsets.UTF_8)));
        failed.await().onFailure(cause -> assertThat(cause).isNotInstanceOf(SliceInvokerError.CompletionUnknown.class));

        network.sent.set(null);
        var departed = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        invoker.onNodeDeparture(HOST);
        departed.await()
                .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a departed callee node is a failure"))
                .onFailure(cause -> assertThat(cause).isNotInstanceOf(SliceInvokerError.CompletionUnknown.class)
                                                     .satisfies(c -> assertThat(c.message()).contains("departed")));
    }

    /// #1723 (owner ruling N1): the response of a healthy task that runs longer than the invocation timeout arrives after
    /// the fire was reported UNKNOWN. It is the fire's real outcome, so it resolves the unknown instead of being dropped.
    @Test
    @Timeout(30)
    void lateSuccessResponse_resolvesTheUnknownOutcome_asCompleted() {
        var unknown = timedOutFire();

        assertThat(unknown.lateOutcome().isResolved()).as("nothing is known until the response arrives").isFalse();
        assertThat(impl().lateCompletionCount()).as("the timed-out call is retained for its late response").isEqualTo(1);

        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, network.sent.get().correlationId(), "r", true, new byte[0]));

        assertThat(unknown.lateOutcome().await().isSuccess()).as("the callee completed the fire").isTrue();
        assertThat(impl().lateCompletionCount()).as("a resolved call is no longer retained").isZero();
    }

    @Test
    @Timeout(30)
    void lateFailureResponse_resolvesTheUnknownOutcome_asFailed() {
        var unknown = timedOutFire();

        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST,
                                                               network.sent.get().correlationId(),
                                                               "r",
                                                               false,
                                                               "callee blew up late".getBytes(StandardCharsets.UTF_8)));

        unknown.lateOutcome()
               .await()
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a late failure response is a failure"))
               .onFailure(cause -> assertThat(cause.message()).contains("callee blew up late"));
    }

    /// v1873's probe, as a pin: three fires of a healthy long task, each answered late. The "unknown correlationId" WARN
    /// is a protocol-anomaly line and must not be written for a completion-awaited request.
    @Test
    @Timeout(30)
    void lateResponses_ofCompletionAwaitedFires_neverWarnAboutAnUnknownCorrelationId() throws Exception {
        var warnings = new CopyOnWriteArrayList<String>();
        var detach = LogCapture.warningsOf(Class.forName("org.pragmatica.aether.invoke.SliceInvokerImpl"), warnings);

        try {
            for (int fire = 0; fire < 3; fire++) {
                timedOutFire();
                invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, network.sent.get().correlationId(), "r", true, new byte[0]));
            }
            // Positive control for the capture and for the line itself: a response nobody asked for still warns.
            invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, "never-sent", "r", true, new byte[0]));

            assertThat(warnings.stream().filter(line -> line.contains("unknown correlationId")))
                .as("only the response nobody asked for warns: %s", warnings)
                .singleElement()
                .satisfies(line -> assertThat(line).contains("never-sent"));
        } finally {
            detach.run();
        }
    }

    /// The retention is bounded: one more timed-out fire than the capacity drops the OLDEST. Its late response is then
    /// discarded without a WARN and its outcome stays unknown; the newest is still resolved.
    @Test
    @Timeout(60)
    void retainedTimedOutFires_areBounded_theOldestIsDroppedAndItsLateResponseIsDiscardedQuietly() throws Exception {
        var warnings = new CopyOnWriteArrayList<String>();
        var detach = LogCapture.warningsOf(Class.forName("org.pragmatica.aether.invoke.SliceInvokerImpl"), warnings);

        try {
            var oldest = timedOutFire();
            var oldestId = network.sent.get().correlationId();

            network.all.clear();
            // Fired together, so the whole batch times out in one timeout rather than one timeout each.
            var batch = IntStream.range(0, SliceInvokerImpl.LATE_COMPLETION_CAPACITY)
                                 .mapToObj(_ -> invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit()))
                                 .toList();
            var unknowns = batch.stream()
                                .map(fire -> fire.await().fold(cause -> (SliceInvokerError.CompletionUnknown) cause, _ -> null))
                                .toList();

            assertThat(unknowns).as("premise: every fire of the batch timed out").doesNotContainNull();
            // Each retention is one map insert behind its fire's timeout; the oldest leaves with the last of them.
            Thread.sleep(500);
            assertThat(impl().lateCompletionCount()).isEqualTo(SliceInvokerImpl.LATE_COMPLETION_CAPACITY);

            invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, oldestId, "r", true, new byte[0]));
            network.all.forEach(request -> invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, request.correlationId(), "r", true, new byte[0])));

            assertThat(unknowns).as("every retained fire is resolved")
                                .allSatisfy(unknown -> assertThat(unknown.lateOutcome().await().isSuccess()).isTrue());
            assertThat(oldest.lateOutcome().isResolved()).as("the dropped fire stays unknown").isFalse();
            assertThat(warnings.stream().filter(line -> line.contains("unknown correlationId"))).isEmpty();
        } finally {
            detach.run();
        }
    }

    /// A retained fire older than the TTL is dropped by the cleanup sweep, and its late response is then discarded like
    /// one past the capacity: the fire stays unknown. A younger one is kept.
    @Test
    @Timeout(30)
    void retainedTimedOutFire_expiresAfterTheTtl_andItsLateResponseIsDiscarded() {
        var unknown = timedOutFire();
        var correlationId = network.sent.get().correlationId();
        var retainedAt = System.currentTimeMillis();

        assertThat(impl().lateCompletionCount()).as("premise: retained").isEqualTo(1);

        impl().expireLateCompletions(retainedAt + SliceInvokerImpl.LATE_COMPLETION_TTL_MS - 60_000L);
        assertThat(impl().lateCompletionCount()).as("younger than the TTL: kept").isEqualTo(1);

        impl().expireLateCompletions(retainedAt + SliceInvokerImpl.LATE_COMPLETION_TTL_MS + 60_000L);
        assertThat(impl().lateCompletionCount()).as("older than the TTL: dropped").isZero();

        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, correlationId, "r", true, new byte[0]));

        assertThat(unknown.lateOutcome().isResolved()).as("the expired fire stays unknown").isFalse();
    }

    /// A departed node sends no late response, so nothing is retained for it; the outcome stays unknown (it is not
    /// turned into a failure: the callee may have completed the fire before it left).
    @Test
    @Timeout(30)
    void retainedTimedOutFire_isDroppedWhenItsNodeDeparts_andStaysUnknown() {
        var unknown = timedOutFire();

        invoker.onNodeDeparture(HOST);

        assertThat(impl().lateCompletionCount()).isZero();
        assertThat(unknown.lateOutcome().isResolved()).isFalse();
    }

    /// A response that arrives IN TIME is not retained.
    @Test
    @Timeout(30)
    void fireAnsweredInTime_retainsNothing() {
        var completion = invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit());

        awaitSent();
        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, network.sent.get().correlationId(), "r", true, new byte[0]));

        assertThat(completion.await().isSuccess()).isTrue();
        assertThat(impl().lateCompletionCount()).isZero();
    }

    /// Fires once, lets it time out, and returns its unknown outcome. `network.sent` holds that fire's request.
    private SliceInvokerError.CompletionUnknown timedOutFire() {
        var found = new AtomicReference<SliceInvokerError.CompletionUnknown>();
        var retainedBefore = impl().lateCompletionCount();

        network.sent.set(null);
        invoker.invokeAwaitingCompletion(ARTIFACT, METHOD, unit())
               .await()
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("premise: the fire times out"))
               .onFailure(cause -> found.set((SliceInvokerError.CompletionUnknown) cause));
        // The caller sees the timeout a moment before the invoker has retained the call.
        awaitRetained(Math.min(retainedBefore + 1, SliceInvokerImpl.LATE_COMPLETION_CAPACITY));

        return found.get();
    }

    private void awaitRetained(int count) {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (impl().lateCompletionCount() != count && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    private SliceInvokerImpl impl() {
        return (SliceInvokerImpl) invoker;
    }

    /// Control: the plain fire-and-forget `invoke` (durable-topic publish and the like) is unchanged and still resolves
    /// when the request is handed to the transport.
    @Test
    @Timeout(30)
    void plainInvoke_stillResolvesOnEnqueue() {
        assertThat(invoker.invoke(ARTIFACT, METHOD, unit()).await().isSuccess()).isTrue();
        awaitSent();
        assertThat(network.sent.get().expectResponse()).isFalse();
    }

    private void awaitSent() {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (network.sent.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static final class CapturingNetwork extends StubClusterNetwork {
        private final AtomicReference<InvokeRequest> sent = new AtomicReference<>();
        private final CopyOnWriteArrayList<InvokeRequest> all = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest request) {
                sent.set(request);
                all.add(request);
            }

            return unit();
        }
    }
}
