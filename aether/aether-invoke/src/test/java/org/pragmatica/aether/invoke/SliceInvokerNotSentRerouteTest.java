// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
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
import org.pragmatica.consensus.net.WriteOutcome;
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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2807, owner ruling (B): the typed `invoke` re-routes a call to ANOTHER instance only when the transport provably
/// refused to write it (`ForwardError.NotSent`), at most `max_retries` times and never past the call's deadline. A call
/// whose request was handed to the transport (an unknown outcome, a departed target, a callee failure) fails once and
/// is never re-sent, because a non-idempotent call must not run twice.
///
/// The observable is the list of `InvokeRequest` frames the invoker hands to the transport. Every assertion on it is
/// taken after the call's own promise settled: a re-route is part of that promise's chain, so a re-send could not
/// happen after it. Affinity pins the first pick to [#FIRST], so which node refuses is deterministic.
class SliceInvokerNotSentRerouteTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId FIRST = new NodeId("first-node");
    private static final NodeId SECOND = new NodeId("second-node");
    private static final NodeId THIRD = new NodeId("third-node");
    private static final NodeId FOURTH = new NodeId("fourth-node");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:reroute-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();
    private static final String ANSWER = "answer";
    /// Short enough that an unanswered call settles within the test, long enough that a send is never mistaken for a
    /// timeout.
    private static final long TIMEOUT_MS = 300L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final long SEND_WAIT_MS = 2_000L;

    private final RecordingNetwork network = new RecordingNetwork();
    private final EndpointRegistry registry = EndpointRegistry.endpointRegistry();
    private SliceInvoker invoker;

    @AfterEach
    void tearDown() {
        invoker.stop().await();
    }

    /// (a) The first instance refuses the write; the call goes to the second and succeeds with its answer.
    @Test
    void invoke_notSentOnFirstInstance_reroutesToSecondAndSucceeds() {
        startInvoker(3, FIRST, SECOND);
        network.outcome(FIRST, WriteOutcome.NoPeerState::new);

        var call = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {});

        answer(awaitSend(2), true, new byte[0]);
        call.await()
            .onFailure(cause -> fail("a refused write must be re-routed, got " + cause.message()))
            .onSuccess(value -> assertThat(value).isEqualTo(ANSWER));
        assertThat(network.targets()).as("refused on the first instance, re-routed once to the second")
                  .containsExactly(FIRST, SECOND);
    }

    /// (b) An unknown outcome is never re-sent: `ConnectionDead` leaves an `InvokeRequest` in the offline buffer, where
    /// it may still be delivered, so the call waits and times out on the first instance alone.
    @Test
    void invoke_outcomeUnknown_isNeverResent() {
        startInvoker(3, FIRST, SECOND);
        network.outcome(FIRST, WriteOutcome.ConnectionDead::new);

        var result = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}).await();

        assertFailure(result, CoreError.Timeout.class);
        assertThat(network.targets()).as("a request that may still be delivered must reach exactly one instance")
                  .containsExactly(FIRST);
    }

    /// (b) The callee answered with a failure: the request ran, so it is surfaced, not re-sent.
    @Test
    void invoke_calleeFailure_isNeverResent() {
        startInvoker(3, FIRST, SECOND);

        var call = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {});

        answer(awaitSend(1), false, "boom".getBytes(StandardCharsets.UTF_8));
        assertFailure(call.await(), SliceInvokerError.RemoteInvocationError.class);
        assertThat(network.targets()).as("a request the callee ran must reach exactly one instance")
                  .containsExactly(FIRST);
    }

    /// (c) The target departed after the send: one typed failure, no re-send.
    @Test
    void invoke_targetDepartsAfterSend_failsWithoutResend() {
        startInvoker(3, FIRST, SECOND);

        var call = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {});

        awaitSend(1);
        invoker.onNodeDeparture(FIRST);
        assertFailure(call.await(), SliceInvokerError.TargetDeparted.class);
        assertThat(network.targets()).as("a request sent before its target departed must not be re-sent")
                  .containsExactly(FIRST);
    }

    /// (d) The bound: with `max_retries` = 2 and four refusing instances, three sends (the first plus two re-routes),
    /// then a typed NoInstanceAccepted failure (never the HttpError NotSent).
    @Test
    void invoke_everyInstanceRefuses_reroutesAtMostMaxRetriesTimes() {
        startInvoker(2, FIRST, SECOND, THIRD, FOURTH);
        network.refuseAll();

        var result = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}).await();

        assertFailure(result, SliceInvokerError.NoInstanceAccepted.class);
        assertThat(network.targets()).as("one send plus max_retries re-routes, each to a different instance")
                  .hasSize(3)
                  .doesNotHaveDuplicates()
                  .startsWith(FIRST);
    }

    /// (d) `max_retries` = 0 disables re-routing: the refusal surfaces, as NoInstanceAccepted, after the one send.
    @Test
    void invoke_maxRetriesZero_neverReroutes() {
        startInvoker(0, FIRST, SECOND);
        network.outcome(FIRST, WriteOutcome.BackpressureRefused::new);

        var result = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}).await();

        assertFailure(result, SliceInvokerError.NoInstanceAccepted.class);
        assertThat(network.targets()).containsExactly(FIRST);
    }

    /// (e) The budget: the refusal arrives after the caller's deadline has passed, so no re-route starts.
    @Test
    void invoke_notSentAfterDeadline_isNotRerouted() {
        startInvoker(3, FIRST, SECOND);
        var now = new AtomicLong();
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), now::get);

        network.outcome(FIRST, node -> expireThenRefuse(now, node));

        var result = Deadline.runWith(deadline,
                                      () -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}))
                             .await();

        assertFailure(result, SliceInvokerError.NoInstanceAccepted.class);
        assertThat(network.targets()).as("no re-route may start once the caller's deadline has passed")
                  .containsExactly(FIRST);
    }

    /// The control for (e): the same refusal inside the budget is re-routed, so the deadline is what stopped it.
    @Test
    void invoke_notSentWithinDeadline_isRerouted() {
        startInvoker(3, FIRST, SECOND);
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), new AtomicLong()::get);

        network.outcome(FIRST, WriteOutcome.NoPeerState::new);

        var call = Deadline.runWith(deadline,
                                    () -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}));

        answer(awaitSend(2), true, new byte[0]);
        assertThat(call.await().isSuccess()).isTrue();
        assertThat(network.targets()).containsExactly(FIRST, SECOND);
    }

    /// A15, F2: `stop()` lands between the refusal and the re-route decision (driven through the deadline clock, which
    /// the decision reads just before the stopped guard). A stopped invoker sends no further frame: its pending map is
    /// already cleared, so a new frame could only be settled by its own timeout.
    @Test
    void invoke_stopBetweenRefusalAndReroute_sendsNoFurtherFrame() {
        startInvoker(3, FIRST, SECOND);
        network.outcome(FIRST, WriteOutcome.NoPeerState::new);
        var stopFired = new AtomicBoolean();
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), () -> stopOnceFirstFrameWasSent(stopFired));

        var result = Deadline.runWith(deadline,
                                      () -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}))
                             .await(timeSpan(3).seconds());

        assertThat(stopFired.get()).as("control: stop() ran inside the re-route decision").isTrue();
        assertThat(network.targets()).as("no frame may go out once the invoker is stopped").containsExactly(FIRST);
        assertFailure(result, SliceInvokerError.NoInstanceAccepted.class);
    }

    private long stopOnceFirstFrameWasSent(AtomicBoolean stopFired) {
        if (!network.requests().isEmpty() && stopFired.compareAndSet(false, true)) {
            invoker.stop().await();
        }

        return 0L;
    }

    /// A07: an encode failure happens before any write, so it proves the request never left and is re-routed.
    @Test
    void invoke_encodeFailedOnFirstInstance_reroutesToSecond() {
        startInvoker(3, FIRST, SECOND);
        network.outcome(FIRST, node -> new WriteOutcome.EncodeFailed(node, "InvokeRequest"));

        var call = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {});

        answer(awaitSend(2), true, new byte[0]);
        assertThat(call.await().isSuccess()).isTrue();
        assertThat(network.targets()).containsExactly(FIRST, SECOND);
    }

    /// A27: the affinity node hosts nothing, so the endpoint registry falls back to its own pick, which here can only
    /// be the refused instance. The re-route must not take it: with nothing else left the call fails after one send.
    @Test
    void invoke_affinityFallbackLandsOnRefusedInstance_isNotResentThere() {
        startInvoker(3, FIRST);
        invoker.registerAffinityResolver(ARTIFACT, METHOD, _ -> Option.some(new NodeId("ghost-node")));
        network.outcome(FIRST, WriteOutcome.NoPeerState::new);

        var result = invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}).await();

        assertFailure(result, SliceInvokerError.NoInstanceAccepted.class);
        assertThat(network.targets()).as("the refused instance must not be picked again through the affinity fallback")
                  .containsExactly(FIRST);
    }

    /// A31: the re-route is armed from the call's own remaining budget. The refusal settles on another thread, where
    /// the ambient deadline is unbound and reads as unbounded; a re-route that asked the ambient scope would wait the
    /// full invocation timeout instead of what is left of the caller's budget.
    @Test
    void invoke_rerouteAfterAsyncRefusal_waitsForTheCallsRemainingBudgetOnly() {
        startInvoker(3, FIRST, SECOND);
        network.refuseAsync(FIRST);
        var budget = timeSpan(100).millis();
        var deadline = Deadline.startingNow(budget, () -> 0L);

        Deadline.runWith(deadline, () -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}));
        awaitSend(2);

        assertThat(network.waits()).as("first send and re-route").hasSize(2);
        assertThat(network.waits().get(1).nanos()).as("the re-route waits for the call's remaining budget, not the "
                                                      + "full invocation timeout")
                          .isLessThanOrEqualTo(budget.nanos());
    }

    /// F1: the failure a route returns for an exhausted call is not an HttpError and claims nothing about the outer
    /// handler's effects: the status is 500 and the body never says "safe to retry" or "nothing was applied".
    @Test
    void route_exhaustedReroutes_answersWithoutSafeToRetryClaim() {
        startInvoker(0, FIRST, SECOND);
        network.refuseAll();
        Route<String> route = Route.route(HttpMethod.GET,
                                          "/outer",
                                          _ -> invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {}),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "outer");
        RouteSource source = () -> Stream.of(route);
        var router = SliceRouter.sliceRouter(source, cause -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause), JsonMapper.defaultJsonMapper());
        var request = HttpRequestContext.httpRequestContext("/outer", "GET", Map.of(), Map.of(), "req_2807");

        var response = router.handle(request).await().unwrap();
        var body = new String(response.body(), StandardCharsets.UTF_8);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(body).doesNotContain("safe to retry").doesNotContain("nothing was applied");
        assertThat(network.targets()).containsExactly(FIRST);
    }

    /// F1: the exhausted failure itself is a plain slice-level cause, never the transport-hop HttpError.
    @Test
    void invoke_exhausted_failureIsNotAnHttpError() {
        startInvoker(0, FIRST);
        network.refuseAll();

        invoker.invoke(ARTIFACT, METHOD, "request", new TypeToken<String>() {})
               .await()
               .onSuccess(value -> fail("expected failure, got " + value))
               .onFailure(cause -> assertThat(cause).isNotInstanceOf(HttpError.class));
    }

    private static WriteOutcome expireThenRefuse(AtomicLong now, NodeId node) {
        now.addAndGet(timeSpan(20).seconds().nanos());

        return new WriteOutcome.NoPeerState(node);
    }

    private void startInvoker(int maxRetries, NodeId... hosts) {
        var handler = InvocationHandler.invocationHandler(SELF, network);

        handler.registerSlice(ARTIFACT, answeringBridge());
        for (int i = 0; i < hosts.length; i++) {
            registry.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, i), EndpointValue.endpointValue(hosts[i]));
        }

        invoker = SliceInvoker.sliceInvoker(SELF,
                                            network,
                                            registry,
                                            handler,
                                            new StubSerializer(),
                                            new StubDeserializer(),
                                            TIMEOUT_MS,
                                            CLEANUP_INTERVAL_MS,
                                            maxRetries,
                                            new StubDeploymentManager());
        invoker.registerAffinityResolver(ARTIFACT, METHOD, _ -> Option.some(FIRST));
    }

    private void answer(InvokeRequest request, boolean success, byte[] payload) {
        invoker.onInvokeResponse(InvokeResponse.invokeResponse(network.lastTarget(),
                                                               request.correlationId(),
                                                               request.requestId(),
                                                               success,
                                                               payload));
    }

    /// The `count`-th request handed to the transport, waiting for the encode continuation to reach it.
    private InvokeRequest awaitSend(int count) {
        var limit = System.currentTimeMillis() + SEND_WAIT_MS;

        while (network.requests().size() < count && System.currentTimeMillis() < limit) {
            Thread.onSpinWait();
        }

        assertThat(network.requests()).as("expected send #" + count).hasSizeGreaterThanOrEqualTo(count);

        return network.requests().get(count - 1);
    }

    private static <T> void assertFailure(Result<T> result, Class<? extends Cause> expected) {
        result.onSuccess(value -> fail("expected " + expected.getSimpleName() + ", got success " + value))
              .onFailure(cause -> assertThat(cause).isInstanceOf(expected));
    }

    /// Records every `InvokeRequest` and its target; each node's write outcome is `Sent` unless the test sets another.
    private static final class RecordingNetwork extends StubClusterNetwork {
        private final List<InvokeRequest> requests = new CopyOnWriteArrayList<>();
        private final List<NodeId> targets = new CopyOnWriteArrayList<>();
        private final Map<NodeId, Function<NodeId, WriteOutcome>> outcomes = new ConcurrentHashMap<>();
        private final List<TimeSpan> waits = new CopyOnWriteArrayList<>();
        private final java.util.Set<NodeId> asyncRefusals = ConcurrentHashMap.newKeySet();
        private volatile boolean refuseAll;

        /// The node refuses every write, but reports it from another thread, as the real transport does.
        void refuseAsync(NodeId node) {
            asyncRefusals.add(node);
        }

        List<TimeSpan> waits() {
            return List.copyOf(waits);
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            waits.add(offlineTtl);

            return send(nodeId, message);
        }

        void outcome(NodeId node, Function<NodeId, WriteOutcome> outcome) {
            outcomes.put(node, outcome);
        }

        void refuseAll() {
            refuseAll = true;
        }

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest request) {
                requests.add(request);
                targets.add(nodeId);
            }

            if (asyncRefusals.contains(nodeId)) {
                return Promise.<WriteOutcome> promise(promise -> Thread.startVirtualThread(() -> promise.succeed(new WriteOutcome.NoPeerState(nodeId))));
            }

            return Promise.success(refuseAll
                                   ? new WriteOutcome.NoPeerState(nodeId)
                                   : outcomes.getOrDefault(nodeId, WriteOutcome.Sent::new).apply(nodeId));
        }

        List<InvokeRequest> requests() {
            return List.copyOf(requests);
        }

        List<NodeId> targets() {
            return List.copyOf(targets);
        }

        NodeId lastTarget() {
            return targets.getLast();
        }
    }

    /// `encode` yields an empty payload so the send arm reaches the transport; `decode` yields [#ANSWER] for any
    /// successful response; `invoke` is never reached because every endpoint is remote.
    private static SliceBridge answeringBridge() {
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
            public Promise<Object> decode(byte[] bytes) {
                return Promise.success(ANSWER);
            }

            @Override
            public ClassLoader classLoader() {
                return SliceInvokerNotSentRerouteTest.class.getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(METHOD.name());
            }
        };
    }
}
