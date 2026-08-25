// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;

/// The invocation wait is capped by the ambient request budget on BOTH sides of the hop: the caller's
/// `SliceInvoker` correlation wait, and the receiver's `InvocationHandler` dispatch wait. A caller under
/// a client deadline gets at most what REMAINS of it, never the configured timeout stacked on top.
/// Waiting longer than the caller will wait answers nobody, and on a saturated node those extra seconds
/// are held connections and held memory.
///
/// Every pair below is the whole argument, and neither half stands alone. The bounded case proves a
/// short budget resolves fast; the unbounded case proves the SAME fixture with the SAME configured
/// timeout does NOT resolve in that window — so the fast resolution is attributable to the deadline
/// rather than to a fixture that fails instantly for some unrelated reason (a missing endpoint, an
/// unresolvable sender bridge, or an unregistered slice all fail in roughly zero time and would
/// otherwise read as success). The lower time bound on each bounded case guards exactly that.
class InvocationDeadlineCapTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId REMOTE = new NodeId("remote-node");
    /// Long enough that it can never fire in-test: if it ever does, the assertions below are measuring
    /// the configured timeout instead of the budget and go red.
    private static final long CONFIGURED_TIMEOUT_MS = 60_000L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final long BUDGET_MILLIS = 300L;

    @Test
    void invoke_underBoundedDeadline_failsAtTheRemainingBudget_notTheConfiguredTimeout() {
        var invoker = remoteInvoker();

        try {
            var startedAt = System.nanoTime();
            var result = Deadline.runWith(Deadline.fromWireMillis(BUDGET_MILLIS),
                                          () -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}))
                                 .await();
            var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(result.isFailure()).as("nothing ever answers this invocation, so it must fail")
                                          .isTrue();
            assertThat(elapsedMillis)
                .as("the wait is capped by the remaining budget, not the 60s configured timeout")
                .isLessThan(10_000L);
            assertThat(elapsedMillis)
                .as("it must have WAITED the budget — a near-instant failure means the fixture never"
                    + " reached the timeout path (no endpoint, or no sender bridge) and this test would"
                    + " be measuring the wrong thing entirely")
                .isGreaterThanOrEqualTo(BUDGET_MILLIS / 2);
        } finally {
            invoker.stop().await();
        }
    }

    /// The arming half: same invoker, same unanswered invocation, no ambient budget. It must still be
    /// waiting when the bounded case above had long since failed.
    @Test
    void invoke_withNoAmbientDeadline_isStillWaitingAfterTheBoundedCaseWouldHaveFailed() {
        var invoker = remoteInvoker();

        try {
            var startedAt = System.nanoTime();

            invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {})
                   .await(TimeSpan.timeSpan(2).seconds());

            var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMillis)
                .as("with no ambient budget the configured 60s timeout governs, so the invocation is"
                    + " still pending when our own 2s await gives up — this is what makes the bounded"
                    + " case's fast failure attributable to the deadline")
                .isGreaterThanOrEqualTo(1_500L);
        } finally {
            invoker.stop().await();
        }
    }

    /// The RECEIVER half of the same arc. `InvocationHandler` caps its dispatch wait the same way, and
    /// the observable is the `InvokeResponse` failure it sends back once the timeout fires — a handler
    /// that ignored the ambient budget would hold the slice call for its full configured 15s while the
    /// caller had already given up, which is precisely the zombie-dispatch amplification the deadline
    /// arc exists to remove.
    @Test
    void onInvokeRequest_underBoundedDeadline_answersFailureAtTheRemainingBudget() {
        var network = new CapturingNetwork();
        var handler = InvocationHandler.invocationHandler(SELF, network);

        handler.registerSlice(ARTIFACT, neverAnsweringBridge());

        var startedAt = System.nanoTime();

        Deadline.runWith(Deadline.fromWireMillis(BUDGET_MILLIS),
                         () -> handler.onInvokeRequest(request("corr-bounded")));

        var answered = network.awaitResponse(TimeSpan.timeSpan(10).seconds());
        var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(answered)
            .as("the bridge never answers, so the budget must fire and the caller must be told —"
                + " silence here is the failure mode, not an acceptable outcome")
            .isTrue();
        assertThat(network.lastResponseFailed())
            .as("the answer must be a FAILURE response; a success would mean the never-answering bridge"
                + " somehow completed and this test is measuring nothing")
            .isTrue();
        assertThat(elapsedMillis)
            .as("the dispatch wait is capped by the remaining budget, not the configured 15s")
            .isLessThan(10_000L);
        assertThat(elapsedMillis)
            .as("it must have WAITED the budget — a near-instant answer means the request never reached"
                + " the timeout path (unregistered slice answers immediately with 'Slice not found')")
            .isGreaterThanOrEqualTo(BUDGET_MILLIS / 2);
    }

    /// The arming half: same handler, same never-answering bridge, no ambient budget. The configured
    /// 15s governs, so nothing has been answered by the time our own 2s wait gives up.
    @Test
    void onInvokeRequest_withNoAmbientDeadline_hasNotAnsweredWhileTheBoundedCaseAlreadyHad() {
        var network = new CapturingNetwork();
        var handler = InvocationHandler.invocationHandler(SELF, network);

        handler.registerSlice(ARTIFACT, neverAnsweringBridge());
        handler.onInvokeRequest(request("corr-unbounded"));

        assertThat(network.awaitResponse(TimeSpan.timeSpan(2).seconds()))
            .as("with no ambient budget the configured 15s timeout governs, so the dispatch is still"
                + " outstanding at 2s — this is what makes the bounded case's fast answer attributable"
                + " to the deadline rather than to a fixture that fails instantly")
            .isFalse();
    }

    // === fixtures ===

    private static final Artifact ARTIFACT = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("processRequest").unwrap();

    private static InvokeRequest request(String correlationId) {
        return InvokeRequest.invokeRequest(REMOTE, correlationId, "req-" + correlationId, ARTIFACT, METHOD,
                                           new byte[0], true);
    }

    /// Captures the `InvokeResponse` the handler sends back, with a latch so a test can wait for the
    /// asynchronous timeout rather than sleeping a fixed amount and hoping.
    private static final class CapturingNetwork extends StubClusterNetwork {
        private final CountDownLatch answered = new CountDownLatch(1);
        private final AtomicReference<InvokeResponse> captured = new AtomicReference<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof InvokeResponse response) {
                captured.set(response);
                answered.countDown();
            }

            return unit();
        }

        boolean awaitResponse(TimeSpan within) {
            try {
                return answered.await(within.millis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return fail("interrupted while waiting for the handler's response");
            }
        }

        boolean lastResponseFailed() {
            return !captured.get().success();
        }
    }

    /// An invoker whose only endpoint for the method lives on ANOTHER node, so `invoke` takes the
    /// remote request/response path (the one carrying the deadline cap) rather than the local one. The
    /// stub network accepts the send and nothing ever answers it.
    private static SliceInvoker remoteInvoker() {
        var network = new StubClusterNetwork();
        var registry = EndpointRegistry.endpointRegistry();
        var handler = InvocationHandler.invocationHandler(SELF, network);

        // The sender bridge is resolved through the handler's local-slice registry; without it the
        // invocation fails instantly with SENDER_BRIDGE_NOT_FOUND and never reaches the timeout.
        handler.registerSlice(ARTIFACT, neverAnsweringBridge());
        registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(REMOTE));

        return SliceInvoker.sliceInvoker(SELF,
                                         network,
                                         registry,
                                         handler,
                                         new StubSerializer(),
                                         new StubDeserializer(),
                                         CONFIGURED_TIMEOUT_MS,
                                         CLEANUP_INTERVAL_MS,
                                         new StubDeploymentManager());
    }

    /// `encode` returns an empty payload so the SENDER path reaches the transport, and `invoke` never
    /// resolves so the RECEIVER path outlasts every budget under test. One bridge serves both halves:
    /// on the sender side `invoke` is never called (the endpoint is remote), and on the receiver side
    /// `encode` is never called.
    private static SliceBridge neverAnsweringBridge() {
        return new SliceBridge() {
            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.promise();
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
                return InvocationDeadlineCapTest.class.getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(METHOD.name());
            }
        };
    }
}
