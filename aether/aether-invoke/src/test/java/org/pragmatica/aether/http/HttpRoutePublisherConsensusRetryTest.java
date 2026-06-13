// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Covers FIX 2b (#325): the route-publish consensus apply is wrapped in
/// [`HttpRoutePublisherImpl#applyWithRetry`] with a 30s `timeout(...)` and a bounded retry. The
/// regression being guarded is a `cluster.apply(...)` Promise that NEVER resolves — pre-fix it
/// wedged a node in ROUTING and the publish Promise hung forever. Post-fix the publish Promise
/// always resolves: success when consensus succeeds, failure once retries are exhausted.
///
/// Routes are produced by the ServiceLoader-discovered [`StubRouteHandlerFactory`]
/// (`src/test/resources/META-INF/services/...`), so `publishRoutes(...)` reaches the
/// apply-with-retry path with a controllable [`StubCluster`].
class HttpRoutePublisherConsensusRetryTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:stub:1.0.0").unwrap();
    // Bound the never-resolving-apply assertion safely above the worst case: the 30s timeout fires
    // for the initial attempt plus CONSENSUS_MAX_RETRIES (2) retries == 3 * 30s == 90s wall clock.
    private static final TimeSpan AWAIT_BUDGET = timeSpan(120).seconds();

    private StubCluster cluster;
    private HttpRoutePublisher publisher;

    @BeforeEach
    void setUp() {
        cluster = new StubCluster();
        publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);
    }

    @Nested
    class FirstAttemptSucceeds {
        @Test
        void publishRoutes_consensusSucceedsImmediately_resolvesSuccessWithSingleApply() {
            cluster.respondWith(_ -> Promise.success(List.of()));

            publish().await(AWAIT_BUDGET)
                     .onFailure(cause -> Assertions.fail("publish must succeed: " + cause.message()));

            assertThat(cluster.applyCount())
                    .as("a first-attempt success must apply exactly once (no retry)")
                    .isEqualTo(1);
        }
    }

    @Nested
    class NeverResolvingApply {
        /// The #325 repro: this is intentionally slow (~90s) because the only retry/timeout seam is
        /// the production-fixed `private static final` 30s timeout x 3 attempts; there is no
        /// injectable scheduler. The point is the publish Promise RESOLVES (with failure) instead of
        /// hanging forever, which is exactly what the fix guarantees.
        @Test
        void publishRoutes_applyNeverResolves_resolvesFailureAfterRetriesExhausted() {
            cluster.respondWith(_ -> Promise.promise());

            var result = publish().await(AWAIT_BUDGET);

            assertThat(result.isFailure())
                    .as("a never-resolving consensus apply must surface as a failed publish, not a hang")
                    .isTrue();
            assertThat(cluster.applyCount())
                    .as("initial attempt + 2 retries == 3 apply invocations before giving up")
                    .isEqualTo(3);
        }
    }

    @Nested
    class TransientFailureThenSuccess {
        @Test
        void publishRoutes_firstApplyFailsThenSucceeds_resolvesSuccessAfterRetry() {
            cluster.respondWith(attempt -> attempt == 1
                                           ? Causes.cause("transient consensus failure").promise()
                                           : Promise.success(List.of()));

            publish().await(AWAIT_BUDGET)
                     .onFailure(cause -> Assertions.fail("publish must succeed after retry: " + cause.message()));

            assertThat(cluster.applyCount())
                    .as("a transient first-attempt failure retries once and then succeeds")
                    .isEqualTo(2);
        }
    }

    private Promise<Unit> publish() {
        return publisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), stubInvokerFacade());
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                  String methodName,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
                return Causes.cause("stub invoker facade").result();
            }
        };
    }

    /// `ClusterNode` test double whose `apply(...)` consults a per-attempt response strategy and
    /// records the invocation count, so each test can deterministically choose success, transient
    /// failure, or a never-resolving Promise per attempt.
    private static final class StubCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final AtomicInteger applyCount = new AtomicInteger(0);
        private volatile IntFunction<Promise<List<Object>>> strategy =
                _ -> Promise.success(List.of());

        void respondWith(IntFunction<Promise<List<Object>>> newStrategy) {
            this.strategy = newStrategy;
        }

        int applyCount() {
            return applyCount.get();
        }

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
            var attempt = applyCount.incrementAndGet();

            return (Promise<List<R>>) (Promise<?>) strategy.apply(attempt);
        }
    }
}
