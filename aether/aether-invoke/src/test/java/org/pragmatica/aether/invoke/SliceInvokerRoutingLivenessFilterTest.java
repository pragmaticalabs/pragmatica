// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.forward.AccessibilityFilter;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;


/// #275 (finding 1, round 2): the liveness narrowing must hold on the WEIGHTED-ROUTING path too.
///
/// `SliceInvokerLivenessFilterTest` pins the arms that select within one artifact version. During an
/// active deployment selection goes somewhere else entirely: `EndpointRegistry.selectEndpointWithRouting`
/// draws its candidates from `findEndpointsForBase` — EVERY version of the base — and splits them into
/// old/new. The exclusion handed to that pick therefore has to be computed over the same rows. Scoped to
/// the invoked artifact's own version it could not name a node that hosts only the OTHER version, so a
/// co-confirmed-DEAD node carrying just the new version stayed in the candidate set and the weighted pick
/// handed it out — the ticket's hang, on precisely the path a rolling deployment exercises.
///
/// The observable is the target node of the `InvokeRequest` the invoker hands to the transport; selection
/// itself is private. Every endpoint is remote so each call takes the send arm, and each call is awaited
/// (nothing answers, so a request/response call settles at the 200ms timeout) so the send count is exact.
class SliceInvokerRoutingLivenessFilterTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId DEAD = new NodeId("dead-node");
    private static final NodeId LIVE_OLD = new NodeId("live-old-node");
    private static final NodeId LIVE_NEW = new NodeId("live-new-node");
    private static final Version OLD_VERSION = Version.version("1.0.0").unwrap();
    private static final Version NEW_VERSION = Version.version("1.1.0").unwrap();
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:routed-slice").unwrap();
    private static final Artifact OLD = BASE.withVersion(OLD_VERSION);
    private static final Artifact NEW = BASE.withVersion(NEW_VERSION);
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();
    private static final long TIMEOUT_MS = 200L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    /// Even, and a multiple of both weights used here, so a 1:1 split is expected to be exactly half
    /// and any lingering dead-node pick shows up rather than being rounded away.
    private static final int ROUNDS = 6;

    private final CapturingNetwork network = new CapturingNetwork();
    private final EndpointRegistry registry = EndpointRegistry.endpointRegistry();
    private SliceInvoker invoker;

    @AfterEach
    void tearDown() {
        if (invoker != null) {
            invoker.stop().await();
        }
    }

    /// THE ROUND-1 DEFECT. Routing is 50/50, the only new-version host is DEAD, and the invoked artifact
    /// is the OLD one — so a version-scoped exclusion is EMPTY and the weighted pick alternated onto the
    /// dead node. With the base-scoped exclusion the new arm is empty and every call lands on the survivor.
    @Test
    void weightedRouting_neverSelectsDeadNodeHostingOnlyTheOtherVersion() {
        registerEndpoint(OLD, 0, LIVE_OLD);
        registerEndpoint(NEW, 0, DEAD);
        startInvoker(routing("1:1"), rejecting(DEAD));

        invokeRounds();

        assertThat(network.targets()).as("#275: the weighted pick must not hand out a node the accessibility filter rejects, "
                                         + "even when that node hosts only the version the caller did not name")
                  .hasSize(ROUNDS)
                  .containsOnly(LIVE_OLD);
    }

    /// The mirror arm: the dead node hosts the version being routed TO. Routing is ALL_NEW, so the pick is
    /// confined to the new arm; the only reachable new host must take every call.
    @Test
    void weightedRouting_neverSelectsDeadNodeHostingTheRoutedVersion() {
        registerEndpoint(OLD, 0, LIVE_OLD);
        registerEndpoint(NEW, 0, DEAD);
        registerEndpoint(NEW, 1, LIVE_NEW);
        startInvoker(routing("1:0"), rejecting(DEAD));

        invokeRounds();

        assertThat(network.targets()).as("#275: an all-new routing must skip a dead host of the new version")
                  .hasSize(ROUNDS)
                  .containsOnly(LIVE_NEW);
    }

    /// A reachable MEMBER that runs only the other version is not a liveness exclusion and must not become
    /// one: under ALL_NEW routing the old-version host is simply not a candidate, and the fix must not have
    /// widened the routed candidate set to every version.
    @Test
    void weightedRouting_skipsReachableHostsOfTheUnroutedVersion() {
        registerEndpoint(OLD, 0, LIVE_OLD);
        registerEndpoint(NEW, 0, LIVE_NEW);
        startInvoker(routing("1:0"), acceptAll());

        invokeRounds();

        assertThat(network.targets()).as("an all-new routing selects only new-version endpoints; membership is not the filter here")
                  .hasSize(ROUNDS)
                  .containsOnly(LIVE_NEW);
    }

    /// THE UPGRADE CASE, and the arm that discriminates this fix from the tempting alternative. A dead node
    /// exists, so a fix that merely widened the exclusion and kept the old "anything excluded means abandon
    /// the weighted pick" bail-out would fall back to the plain round-robin over the INVOKED version only —
    /// and silently serve 100% old while the deployment believes it is running a 50/50 canary. Both live
    /// versions must still be selected.
    @Test
    void weightedRouting_stillSplitsAcrossVersions_whenAnUnrelatedNodeIsDead() {
        registerEndpoint(OLD, 0, LIVE_OLD);
        registerEndpoint(NEW, 0, LIVE_NEW);
        registerEndpoint(NEW, 1, DEAD);
        startInvoker(routing("1:1"), rejecting(DEAD));

        invokeRounds();

        assertThat(network.targets()).as("#275: excluding a dead node must not cost the deployment its weighted split")
                  .hasSize(ROUNDS)
                  .doesNotContain(DEAD)
                  .contains(LIVE_OLD)
                  .contains(LIVE_NEW);
    }

    /// The FAILOVER arm reaches the weighted pick by its own route (`selectEndpointWithFailover`), so it
    /// needs its own base-scoped exclusion — the plain arm's is a different call site. Routing is ALL_NEW
    /// and the only new-version host is DEAD, so a version-scoped exclusion computed from the invoked OLD
    /// artifact is empty and the very FIRST failover send went to the dead node. With the base-scoped set
    /// the new arm is empty and the pick falls back to the reachable old host.
    @Test
    void weightedRouting_failoverFirstAttempt_neverSelectsDeadNodeHostingOnlyTheOtherVersion() {
        registerEndpoint(OLD, 0, LIVE_OLD);
        registerEndpoint(NEW, 0, DEAD);
        startInvoker(routing("1:0"), rejecting(DEAD));

        var _ = invoker.invokeWithRetry(OLD, METHOD, "request", new TypeToken<String>() {}, 3).await();

        assertThat(network.targets()).as("#275: the failover arm's first attempt must not go to a node the filter rejects, "
                                         + "even when that node hosts only the version the caller did not name")
                  .isNotEmpty()
                  .containsOnly(LIVE_OLD);
    }

    /// The filter leaves NOTHING. A silent empty pick, or a fallback onto the unfiltered set, would both be
    /// defects: the call must fail without a single send.
    @Test
    void weightedRouting_everyHostInaccessible_failsWithoutSending() {
        registerEndpoint(OLD, 0, DEAD);
        registerEndpoint(NEW, 0, DEAD);
        startInvoker(routing("1:1"), rejecting(DEAD));

        var result = invoker.invoke(OLD, METHOD, "request", new TypeToken<String>() {}).await();

        assertThat(result.isFailure()).as("#275: no reachable endpoint must fail rather than hang or fall back to the dead node")
                  .isTrue();
        assertThat(network.targets()).as("#275: nothing may be sent when every candidate is filtered out")
                  .isEmpty();
    }

    /// Same exhaustion on the retry path. The failover arm reported this as `AllInstancesFailedError` with
    /// an empty attempt list — an operator surface saying the instances failed for a call that never left
    /// this node, and a non-transient cause for a situation a replacement node resolves. It must be the
    /// transient `NoEndpointsError`, and no `AllInstancesFailed` event may be published.
    @Test
    void weightedRouting_everyHostInaccessible_retryReportsNoEndpointsAndPublishesNoFailureEvent() {
        registerEndpoint(OLD, 0, DEAD);
        registerEndpoint(NEW, 0, DEAD);
        startInvoker(routing("1:1"), rejecting(DEAD));

        var events = new CopyOnWriteArrayList<SliceFailureEvent>();
        var _ = invoker.setFailureListener(events::add);
        var failure = new AtomicReference<Cause>();
        var _ = invoker.invokeWithRetry(OLD, METHOD, "request", new TypeToken<String>() {}, 3)
                       .await()
                       .onFailure(failure::set);

        assertThat(failure.get()).as("#275: nothing was attempted, so this is an absence of endpoints, not an instance failure")
                  .isInstanceOf(SliceInvokerError.NoEndpointsError.class);
        assertThat(failure.get().isTransient()).as("#275: every host being unreachable is transient — a replacement is coming")
                  .isTrue();
        assertThat(events).as("#275: no AllInstancesFailed event for a call that never left this node")
                  .isEmpty();
        assertThat(network.targets()).as("#275: nothing may be sent when every candidate is filtered out")
                  .isEmpty();
    }

    private void invokeRounds() {
        for (int i = 0; i < ROUNDS; i++) {
            var _ = invoker.invoke(OLD, METHOD, "request-" + i, new TypeToken<String>() {}).await();
        }
    }

    private void registerEndpoint(Artifact artifact, int instanceNumber, NodeId nodeId) {
        registry.registerEndpoint(new EndpointKey(artifact, METHOD, instanceNumber), EndpointValue.endpointValue(nodeId));
    }

    private void startInvoker(VersionRouting routing, AccessibilityFilter filter) {
        var handler = InvocationHandler.invocationHandler(SELF, network);

        handler.registerSlice(OLD, silentBridge());
        invoker = SliceInvoker.sliceInvoker(SELF,
                                            network,
                                            registry,
                                            handler,
                                            new StubSerializer(),
                                            new StubDeserializer(),
                                            TIMEOUT_MS,
                                            CLEANUP_INTERVAL_MS,
                                            new RoutingDeploymentManager(routing));
        invoker.setAccessibilityFilter(filter);
    }

    private static VersionRouting routing(String ratio) {
        return VersionRouting.versionRouting(ratio).unwrap();
    }

    private static AccessibilityFilter rejecting(NodeId rejected) {
        return candidates -> candidates.stream()
                                       .filter(node -> !node.equals(rejected))
                                       .toList();
    }

    private static AccessibilityFilter acceptAll() {
        return candidates -> candidates;
    }

    /// Reports a deployment of `BASE` in progress at the given ratio, as `NodeDeploymentManager` does while
    /// a rolling update is live.
    private static final class RoutingDeploymentManager extends StubDeploymentManager {
        private final VersionRouting routing;

        private RoutingDeploymentManager(VersionRouting routing) {
            this.routing = routing;
        }

        @Override
        public Option<ActiveRouting> activeRouting(ArtifactBase artifactBase) {
            if (!artifactBase.equals(BASE)) {
                return Option.none();
            }

            return Option.some(new DeploymentManager.ActiveRouting(routing, OLD_VERSION, NEW_VERSION));
        }
    }

    /// Records the target node of every `InvokeRequest` the invoker sends; nothing ever answers.
    private static final class CapturingNetwork extends StubClusterNetwork {
        private final List<NodeId> targets = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof InvokeRequest) {
                targets.add(nodeId);
            }

            return unit();
        }

        List<NodeId> targets() {
            return List.copyOf(targets);
        }
    }

    /// `encode` yields an empty payload so the send arm reaches the transport; `invoke` is never reached
    /// because every endpoint is remote.
    private static SliceBridge silentBridge() {
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
                return SliceInvokerRoutingLivenessFilterTest.class.getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(METHOD.name());
            }
        };
    }
}
