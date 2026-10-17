// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.ExecutionOutcome;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1573 B1: an HTTP route execution is counted into the same execution counters as a bridge execution.
/// HTTP routes invoke the typed slice instance directly, never the bridge, so before this they were
/// invisible to the all-instances-failed detector: HTTP successes could not veto a rollback, and a version
/// broken only on its HTTP path was never rolled back. The classification is the bridge's
/// (`InvocationMetricsCollector.ExecutionOutcome`).
class HttpRoutePublisherOutcomeTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:outcome-slice:1.0.0").unwrap();

    private record Recorded(Artifact artifact, String method, ExecutionOutcome outcome) {}

    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private HttpRoutePublisher publisher;
    private SliceRouter router;

    @BeforeEach
    void setUp() {
        publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new NoopCluster());

        publisher.setRouteOutcomeRecorder(this::record);
        publisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), new OutcomeRouteSlice(), stubInvokerFacade())
                 .await(timeSpan(10).seconds())
                 .onFailure(cause -> Assertions.fail("publish: " + cause.message()));
        router = publisher.getSliceRouter(ARTIFACT)
                          .fold(() -> Assertions.fail("no router published"), found -> found);
    }

    @Test
    void routeReturningAValue_isRecordedAsASuccess() {
        var response = get("/outcome/ok");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(recorded).containsExactly(new Recorded(ARTIFACT, "ok", ExecutionOutcome.SUCCESS));
    }

    @Test
    void routeHandlerThrowing_isRecordedAsADefect_andStillAnsweredWithAnError() {
        var response = get("/outcome/throws");

        assertThat(response.statusCode()).as("the throw is answered by the router's error mapping").isGreaterThanOrEqualTo(500);
        assertThat(recorded).containsExactly(new Recorded(ARTIFACT, "throws", ExecutionOutcome.DEFECT));
    }

    @Test
    void routeReturningAFailure_isNotRecorded_evenThoughItMapsToAServerError() {
        var _ = get("/outcome/returned");

        assertThat(recorded).as("a returned failure is the method's own answer (e.g. a downstream outage), never a defect")
                            .isEmpty();
    }

    /// v1608 R11: a handler that completes with a `Result.Failure` VALUE returned a failure. It is NEUTRAL —
    /// never a success (that would let a version returning errors veto a rollback its defects elsewhere
    /// justify) and never a defect.
    @Test
    void routeCompletingWithAFailureValue_isNeutral_neitherSuccessNorDefect() {
        var response = get("/outcome/returned-value");

        assertThat(response.statusCode()).as("arming: the router answers the returned failure as an error").isGreaterThanOrEqualTo(400);
        assertThat(recorded).isEmpty();
    }

    @Test
    void requestNoRouteMatches_isNotRecorded() {
        var response = get("/outcome/absent");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(recorded).isEmpty();
    }

    /// Requests the published path ending in `suffix` — path mode mounts it under a version prefix.
    private HttpResponseData get(String suffix) {
        var path = publisher.allLocalRoutes()
                            .stream()
                            .map(route -> route.pathPrefix())
                            .filter(prefix -> prefix.endsWith(suffix + "/"))
                            .findFirst()
                            .orElseGet(() -> "/absent" + suffix);

        return router.handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req_outcome"))
                     .await(timeSpan(10).seconds())
                     .fold(cause -> Assertions.fail("router failed: " + cause.message()), response -> response);
    }

    private Unit record(Artifact artifact, String method, ExecutionOutcome outcome) {
        recorded.add(new Recorded(artifact, method, outcome));

        return Unit.unit();
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<org.pragmatica.aether.slice.MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                                            String methodName,
                                                                                            TypeToken<T> requestType,
                                                                                            TypeToken<R> responseType) {
                return Causes.cause("stub invoker facade").result();
            }
        };
    }

    private static final class NoopCluster implements ClusterNode<KVCommand<AetherKey>> {
        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            return Assertions.fail("topologyManager() is not exercised by route publication");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
