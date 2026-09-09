// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #887 — a blueprint security override must reach the HOSTING node's own authorization decision,
/// not only the replicated KV entry.
///
/// Pre-fix, [`HttpRoutePublisherImpl`] stored the RAW routes in `publishedRoutes` and applied
/// overrides into a local variable used solely to build the KV entry. `findLocalRoute` — the sole
/// source of the local policy decision ([`AppHttpServer#findRouteSecurityPolicy`]) — read the raw
/// collection, so the operator's override was unreachable by the decision that authorizes requests
/// the node actually serves. Post-fix it is resolved at read time.
///
/// These run against the REAL [`HttpRoutePublisherImpl`] with routes published through the real
/// `publishRoutes(...)` path, via the ServiceLoader-discovered [`StubRouteHandlerFactory`]. Nothing
/// here reconstructs the override rule: the assertions read what production computes. The
/// end-to-end HTTP consequence (a valid API key reaching an admin-restricted route) is pinned
/// separately by `AppHttpServerOverrideEnforcementTest` in `aether/node`, at the outermost
/// observable.
class HttpRoutePublisherOverrideResolutionTest {
    private static final NodeId SELF = NodeId.nodeId("self-override").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:stub:1.0.0").unwrap();

    /// [`StubRouteHandlerFactory`] advertises exactly this route, declared PUBLIC by the 4-arg
    /// `httpRouteDefinition` factory. Locking a public route down to `role:admin` is a
    /// STRENGTHEN_ONLY-legal override and the clearest form of the escalation: an operator closes a
    /// public route, and pre-fix the node serving it never learns.
    private static final String ROUTE_METHOD = "GET";
    private static final String ROUTE_PREFIX = "/stub/";
    private static final String REQUEST_PATH = "/stub/thing";

    private CapturingCluster cluster;
    private HttpRoutePublisher publisher;

    @BeforeEach
    void setUp() {
        cluster = new CapturingCluster();
        publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);
        publishRoutes();
    }

    private void publishRoutes() {
        publisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    private static SecurityOverrides lockdownToAdmin() {
        return SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry(ROUTE_METHOD + " " + ROUTE_PREFIX,
                                                                                          "role:admin")),
                                                   SecurityOverridePolicy.STRENGTHEN_ONLY);
    }

    private SecurityPolicy localPolicy() {
        return publisher.findLocalRoute(ROUTE_METHOD, REQUEST_PATH)
                        .map(HttpRoutePublisher.LocalRouteInfo::security)
                        .toResult(Causes.cause("no local route matched " + ROUTE_METHOD + " " + REQUEST_PATH))
                        .unwrap();
    }

    @Nested
    class InstrumentCheck {
        /// Neither assertion below is worth anything if the route under test is not the one this
        /// test thinks it is. Pin the pre-override state explicitly: a local match exists, and it
        /// carries the factory's declared PUBLIC policy.
        @Test
        void findLocalRoute_matchesDeclaredPublicRoute_beforeAnyOverride() {
            assertThat(publisher.findLocalRoute(ROUTE_METHOD, REQUEST_PATH).isPresent())
                    .as("the stub factory's route must match, or every other assertion here is vacuous")
                    .isTrue();
            assertThat(localPolicy())
                    .as("pre-override the local decision must see the route's own declared policy")
                    .isEqualTo(SecurityPolicy.publicRoute());
        }
    }

    @Nested
    class LocalDecisionSeesOverride {
        /// THE #887 REGRESSION. Pre-fix this returns `Public` — the raw stored policy — and the
        /// hosting node serves the locked-down route to anyone.
        @Test
        void findLocalRoute_returnsOverriddenPolicy_afterOverrideApplied() {
            publisher.updateSecurityOverrides(lockdownToAdmin());

            assertThat(localPolicy())
                    .as("the hosting node's own decision must see the operator's override")
                    .isEqualTo(SecurityPolicy.roleRequired("admin"));
        }

        /// #887 acceptance 3: an override that arrives AFTER publication takes effect on the local
        /// decision with no republication of the slice. `publishRoutes` is called once, in setUp.
        @Test
        void findLocalRoute_returnsOverriddenPolicy_withoutRepublishingTheSlice() {
            var publishesBefore = cluster.routePublishCount();

            publisher.updateSecurityOverrides(lockdownToAdmin());

            assertThat(localPolicy()).isEqualTo(SecurityPolicy.roleRequired("admin"));
            assertThat(publishesBefore)
                    .as("the slice was published exactly once, before the override existed")
                    .isEqualTo(1);
        }

        /// The reverse direction, which a snapshot-based fix would get wrong: withdrawing the
        /// override must return the local decision to the route's declared policy, again with no
        /// republication. Applying overrides into `publishedRoutes` at publish time would leave the
        /// stronger policy latched forever.
        @Test
        void findLocalRoute_returnsDeclaredPolicy_afterOverrideWithdrawn() {
            publisher.updateSecurityOverrides(lockdownToAdmin());
            assertThat(localPolicy()).isEqualTo(SecurityPolicy.roleRequired("admin"));

            publisher.updateSecurityOverrides(SecurityOverrides.EMPTY);

            assertThat(localPolicy())
                    .as("a withdrawn override must not stay latched in the local decision")
                    .isEqualTo(SecurityPolicy.publicRoute());
        }
    }

    @Nested
    class ReportedStateTracksEnforcedState {
        /// #887 acceptance 4: `GET /api/v1/routes` answers from the replicated KV entries. If an
        /// override changes the ENFORCED policy without rewriting those entries, the management API
        /// reports a policy this node does not apply — in the withdrawal direction it would report
        /// protection that is no longer enforced. The republication makes the entry track.
        @Test
        void updateSecurityOverrides_republishesRouteEntry_carryingOverriddenPolicy() {
            publisher.updateSecurityOverrides(lockdownToAdmin());

            assertThat(cluster.routePublishCount())
                    .as("the override update must rewrite the cluster route entry")
                    .isEqualTo(2);
            assertThat(cluster.lastPublishedSecurity())
                    .as("the republished entry must carry the overridden policy, not the raw one")
                    .isEqualTo("ROLE:admin");
        }

        /// Enforced and reported must agree on the withdrawal path too, not just the lockdown path.
        @Test
        void updateSecurityOverrides_republishesRouteEntry_whenOverrideWithdrawn() {
            publisher.updateSecurityOverrides(lockdownToAdmin());
            publisher.updateSecurityOverrides(SecurityOverrides.EMPTY);

            assertThat(cluster.routePublishCount()).isEqualTo(3);
            assertThat(cluster.lastPublishedSecurity())
                    .as("withdrawing an override must stop the entry advertising the stronger policy")
                    .isEqualTo("PUBLIC");
            assertThat(localPolicy())
                    .as("reported and enforced must agree after the republication settles")
                    .isEqualTo(SecurityPolicy.publicRoute());
        }
    }

    /// SF-4. `updateSecurityOverrides` is called by `SecurityOverrideSynchronizer.resync`, which runs
    /// once per KV notification ON EVERY NODE. An unconditional republish therefore turns one
    /// `AppBlueprintKey` put anywhere in the cluster into "every node rewrites every local artifact's
    /// route entry through consensus", and `KVStore.reset()` — one `ValueRemove` per key — multiplies
    /// that by the key count during a state-machine reset. Load, not divergence, but load proportional
    /// to nodes x blueprints x artifacts.
    @Nested
    class RepublishesOnlyWhenOverridesChange {
        @Test
        void updateSecurityOverrides_doesNotRepublish_whenOverridesUnchanged() {
            publisher.updateSecurityOverrides(lockdownToAdmin());
            var afterFirst = cluster.routePublishCount();

            publisher.updateSecurityOverrides(lockdownToAdmin());

            assertThat(cluster.routePublishCount())
                    .as("an unchanged override set must not rewrite the cluster route entry again")
                    .isEqualTo(afterFirst);
        }

        @Test
        void updateSecurityOverrides_doesNotRepublish_whenBothCallsAreEmpty() {
            var afterPublish = cluster.routePublishCount();

            publisher.updateSecurityOverrides(SecurityOverrides.EMPTY);

            assertThat(cluster.routePublishCount())
                    .as("EMPTY equals the initial state, so the common resync case must be a no-op")
                    .isEqualTo(afterPublish);
        }

        @Test
        void updateSecurityOverrides_stillRepublishes_whenOverridesActuallyChange() {
            publisher.updateSecurityOverrides(lockdownToAdmin());
            var afterFirst = cluster.routePublishCount();

            publisher.updateSecurityOverrides(SecurityOverrides.EMPTY);

            assertThat(cluster.routePublishCount())
                    .as("the guard must suppress only NO-OP updates, never a real change")
                    .isGreaterThan(afterFirst);
        }

        /// The guard is deliberately not a bare equality check. A republish that FAILED left the KV
        /// entry advertising the previous policy; short-circuiting a later identical resync would
        /// strand it there until the next genuine change, turning a transient consensus failure into a
        /// permanent divergence between enforced and reported state.
        @Test
        void updateSecurityOverrides_retriesRepublish_whenPreviousAttemptFailed() throws Exception {
            cluster.failApplies(true);
            publisher.updateSecurityOverrides(lockdownToAdmin());

            var afterFailedAttempt = awaitStableCount();

            assertThat(afterFailedAttempt)
                    .as("the failing attempt and its retries must actually have reached the cluster")
                    .isGreaterThan(1);

            cluster.failApplies(false);

            // The failure flag is set from the republish Promise's onFailure callback, which may not
            // have run by the time updateSecurityOverrides returns. Polling here is not papering over
            // that race: in production `resync` recurs on every blueprint change and every ACTIVE
            // edge, so "a repeated identical resync eventually retries" IS the property. Bounded, so
            // a guard that never retries fails rather than hanging.
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

            while (cluster.routePublishCount() <= afterFailedAttempt && System.nanoTime() < deadline) {
                publisher.updateSecurityOverrides(lockdownToAdmin());
                Thread.sleep(25);
            }

            assertThat(cluster.routePublishCount())
                    .as("an identical resync after a FAILED republish must retry, not short-circuit forever")
                    .isGreaterThan(afterFailedAttempt);
        }

        /// Wait for the retry burst to finish so the baseline is a settled number rather than one
        /// sampled mid-flight.
        private int awaitStableCount() throws Exception {
            var previous = -1;
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

            while (System.nanoTime() < deadline) {
                var current = cluster.routePublishCount();

                if (current == previous) {
                    return current;
                }

                previous = current;
                Thread.sleep(50);
            }

            return cluster.routePublishCount();
        }
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

    /// `ClusterNode` test double that records every applied command, so a test can assert both THAT
    /// the route entry was rewritten and WHAT policy it carries.
    private static final class CapturingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = new CopyOnWriteArrayList<>();
        private volatile boolean failApplies;

        void failApplies(boolean fail) {
            this.failApplies = fail;
        }

        int routePublishCount() {
            return (int) applied.stream().filter(CapturingCluster::isRoutePut).count();
        }

        String lastPublishedSecurity() {
            return applied.stream()
                          .filter(CapturingCluster::isRoutePut)
                          .map(command -> ((KVCommand.Put<?, ?>) command).value())
                          .map(NodeRoutesValue.class::cast)
                          .reduce((first, second) -> second)
                          .map(value -> value.routes().getFirst().security())
                          .orElseThrow(() -> new AssertionError("no route entry was ever published"));
        }

        private static boolean isRoutePut(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.Put<?, ?> put && put.value() instanceof NodeRoutesValue;
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
            applied.addAll(commands);
            if (failApplies) {
                return Causes.cause("consensus unavailable").promise();
            }

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
