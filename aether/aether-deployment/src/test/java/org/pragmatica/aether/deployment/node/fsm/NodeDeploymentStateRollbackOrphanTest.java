// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.VersionRoutingPutReceived;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1068 — a rolled-back blueprint deploy must leave nothing that can start again.
///
/// The measured chain (CI run 34772700962, `StreamCrashDurabilityTest`): the blueprint rolls back,
/// `handleAppBlueprintRemoval` removes the `SliceTargetKey`, the UNLOAD commands time out in
/// consensus, and 123ms later the node logs "KV claims ACTIVE ... not loaded locally — redeploying"
/// and serves a slice with no owning blueprint. Two node-side producers are pinned here:
///
///  - the KV-convergence redeploy (`redeployClaimedActiveSlice`), the boot-time pending-LOAD replay
///    and a live ACTIVATE all start a slice WITHOUT asking whether the committed store still
///    targets that version;
///  - the unload chain wrote a `NodeArtifactValue` in state ACTIVE (the endpoint "unpublish"),
///    which is exactly the claim the redeploy path heals into a running slice.
///
/// Every assertion here reads the committed KV store and the `SliceStore` — never the node's
/// in-memory `deployments` map — because a removal applied to a projection is undone by the next
/// rebuild from the store, and that rebuild IS the path under test.
class NodeDeploymentStateRollbackOrphanTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();
    private static final Artifact ARTIFACT = BASE.withVersion(V1);
    private static final Duration SETTLE = Duration.ofSeconds(2);

    private KVStore<AetherKey, AetherValue> kvStore;
    private RecordingSliceStore sliceStore;
    private RecordingClusterNode cluster;
    private RecordingInvocationHandler invocations;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        sliceStore = new RecordingSliceStore();
        cluster = new RecordingClusterNode(SELF);
        invocations = new RecordingInvocationHandler();
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("ndm-rollback-orphan-test-" + SELF.id(), factory);
    }

    /// The store after a rollback whose UNLOAD never landed: no `SliceTargetKey`, a `NodeArtifactKey`
    /// still claiming ACTIVE for this node, and nothing loaded locally.
    private void seedRolledBackOrphan() {
        seedNodeArtifact(SELF, ARTIFACT, SliceState.ACTIVE);
    }

    private void seedCommittedTarget(Version version) {
        seedCommittedTarget(BASE, version);
    }

    private void seedCommittedTarget(ArtifactBase base, Version version) {
        applyToKvStore(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(base),
                                           SliceTargetValue.sliceTargetValue(version, 1)));
    }

    private void seedVersionRouting(Version oldVersion, Version newVersion) {
        applyToKvStore(new KVCommand.Put<>(VersionRoutingKey.versionRoutingKey(BASE),
                                           VersionRoutingValue.versionRoutingValue(oldVersion, newVersion)));
    }

    /// The committed target arrives AFTER a start was applied: seed it, then deliver the notification the
    /// KV store routes for it.
    private void targetArrives(ArtifactBase base, Version version) {
        var key = SliceTargetKey.sliceTargetKey(base);
        var value = SliceTargetValue.sliceTargetValue(version, 1);

        applyToKvStore(new KVCommand.Put<>(key, value));
        harness.dispatch(new SliceTargetPutReceived(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none())));
    }

    private void routingArrives(Version oldVersion, Version newVersion) {
        var key = VersionRoutingKey.versionRoutingKey(BASE);
        var value = VersionRoutingValue.versionRoutingValue(oldVersion, newVersion);

        applyToKvStore(new KVCommand.Put<>(key, value));
        harness.dispatch(new VersionRoutingPutReceived(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none())));
    }

    private void dispatchNodeArtifactPut(NodeId node, Artifact artifact, SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(node, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(state);

        applyToKvStore(new KVCommand.Put<>(key, value));
        harness.dispatch(new NodeArtifactPutReceived(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none())));
    }

    @Nested
    class RedeployAfterRollback {
        @Test
        void kvClaimsActiveButTargetRemoved_sliceIsNeverLoadedOrActivated() {
            seedRolledBackOrphan();
            harness.dispatch(new QuorumEstablished());
            cluster.commands.clear();

            // The ACTIVE claim reaches the node (the echo of a put that committed late, or the
            // post-activation replay) with the slice not loaded locally: the redeploy path.
            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVE);

            settle();
            assertThat(sliceStore.loadRequests)
                    .as("no committed SliceTarget names %s — the node must not load it", ARTIFACT)
                    .isEmpty();
            assertThat(sliceStore.activateRequests)
                    .as("nothing loaded, nothing to activate")
                    .isEmpty();
            assertThat(invocations.registered)
                    .as("a rolled-back slice must never be registered for invocation")
                    .isEmpty();
            assertThat(cluster.commands)
                    .as("a refused start writes no transition — the leader's orphan sweep owns the key")
                    .noneMatch(NodeDeploymentStateRollbackOrphanTest::startsSlice);
        }

        /// Positive control for the instrument: the same drive with the target present DOES reach
        /// the store, so the empty store above is a refusal and not a chain that never ran.
        @Test
        void kvClaimsActiveAndTargetCommitted_redeploysThroughTheStore() {
            seedCommittedTarget(V1);
            seedRolledBackOrphan();
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVE);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.loadRequests).containsExactly(ARTIFACT));
        }

        @Test
        void oldVersionDuringRollingUpdate_isStillPermitted() {
            // The target already names V2; a VersionRoutingKey says V1 is still legitimately routed.
            seedCommittedTarget(V2);
            seedVersionRouting(V1, V2);
            seedRolledBackOrphan();
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVE);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.loadRequests).containsExactly(ARTIFACT));
        }

        @Test
        void oldVersionAfterRoutingRemoved_isRefused() {
            seedCommittedTarget(V2);
            seedRolledBackOrphan();
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVE);

            settle();
            assertThat(sliceStore.loadRequests)
                    .as("the target moved to %s and no routing names %s — superseded, not permitted", V2, V1)
                    .isEmpty();
        }
    }

    @Nested
    class LoadAndActivateAfterRollback {
        @Test
        void pendingLoadAtBoot_targetRemoved_isNotLoaded() {
            // A LOAD the leader issued before the rollback, replayed by onEntry's pending-LOAD scan.
            seedNodeArtifact(SELF, ARTIFACT, SliceState.LOAD);

            harness.dispatch(new QuorumEstablished());

            settle();
            assertThat(sliceStore.loadRequests).as("a pending LOAD with no committed target must not load").isEmpty();
        }

        @Test
        void pendingLoadAtBoot_targetCommitted_isLoaded() {
            seedCommittedTarget(V1);
            seedNodeArtifact(SELF, ARTIFACT, SliceState.LOAD);

            harness.dispatch(new QuorumEstablished());

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.loadRequests).containsExactly(ARTIFACT));
        }

        @Test
        void liveActivate_targetRemoved_isNotActivated() {
            sliceStore.loaded.add(ARTIFACT);
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVATE);

            settle();
            assertThat(sliceStore.activateRequests).as("ACTIVATE with no committed target must not activate").isEmpty();
            assertThat(cluster.commands).noneMatch(NodeDeploymentStateRollbackOrphanTest::startsSlice);
        }

        @Test
        void liveActivate_targetCommitted_isActivated() {
            seedCommittedTarget(V1);
            sliceStore.loaded.add(ARTIFACT);
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVATE);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.activateRequests).containsExactly(ARTIFACT));
        }
    }

    /// A refusal is deferred, not final. CI run 34785390505 (`SliceMediaTypeTest`): follower smt-3 applied
    /// the leader's LOAD 4 ms BEFORE it applied the `SliceTargetKey` put the leader had already acted on,
    /// refused, and nothing re-checked when the target arrived — the slice never loaded (404s). The store a
    /// follower reads can lack an earlier put when a later command runs, so the gate must re-evaluate when
    /// the target arrives, while a rollback leftover (target removed, nothing ever arrives) stays parked.
    @Nested
    class DeferredStart {
        @Test
        void loadAppliedBeforeTarget_isLoadedWhenTheTargetArrives() {
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.LOAD);
            settle();
            assertThat(sliceStore.loadRequests).as("no target yet — the LOAD is deferred").isEmpty();

            targetArrives(BASE, V1);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.loadRequests).containsExactly(ARTIFACT));
        }

        @Test
        void activeClaimAppliedBeforeTarget_isRedeployedWhenTheTargetArrives() {
            seedRolledBackOrphan();
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.ACTIVE);
            settle();
            assertThat(sliceStore.loadRequests).isEmpty();

            targetArrives(BASE, V1);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.loadRequests).containsExactly(ARTIFACT));
        }

        @Test
        void oldVersionLoadAppliedBeforeRouting_isLoadedWhenTheRoutingArrives() {
            seedCommittedTarget(V2);
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.LOAD);
            settle();
            assertThat(sliceStore.loadRequests).as("target names V2 and no routing names V1 yet").isEmpty();

            routingArrives(V1, V2);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.loadRequests).containsExactly(ARTIFACT));
        }

        @Test
        void targetForAnotherBase_doesNotReDrive() {
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.LOAD);
            targetArrives(ArtifactBase.artifactBase("org.example:slice-other").unwrap(), V1);

            settle();
            assertThat(sliceStore.loadRequests).as("a target for a different base is not this slice's target").isEmpty();
        }

        @Test
        void targetOfAnotherVersion_staysDeferred() {
            harness.dispatch(new QuorumEstablished());

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.LOAD);
            targetArrives(BASE, V2);

            settle();
            assertThat(sliceStore.loadRequests).as("the arriving target names a different version").isEmpty();
        }

        /// The rollback ordering: the leader's sweep UNLOADs the leftover before any target for the base
        /// could arrive again; the UNLOAD supersedes the deferred start, so a later target must not
        /// resurrect it.
        @Test
        void deferredStartSupersededByUnload_isNotResurrectedByALaterTarget() {
            harness.dispatch(new QuorumEstablished());
            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.LOAD);
            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.UNLOAD);
            await().atMost(SETTLE).untilAsserted(() -> assertThat(cluster.commands).anyMatch(NodeDeploymentStateRollbackOrphanTest::removesOwnKey));

            targetArrives(BASE, V1);

            settle();
            assertThat(sliceStore.loadRequests).as("the UNLOAD replaced the deferred LOAD; nothing is left to re-drive").isEmpty();
        }
    }

    /// The unload chain's endpoint "unpublish" wrote `NodeArtifactValue(ACTIVE, methods=[])` between
    /// the UNLOADING put and the key removal. `EndpointRegistry` ignores empty-method puts, so it
    /// unpublished nothing — and a late-committing copy of it is the ACTIVE claim the redeploy path
    /// heals into a running slice. The unload path must never claim ACTIVE.
    @Nested
    class UnloadPath {
        @Test
        void unload_neverPutsActive() {
            seedCommittedTarget(V1);
            sliceStore.loaded.add(ARTIFACT);
            harness.dispatch(new QuorumEstablished());
            cluster.commands.clear();

            dispatchNodeArtifactPut(SELF, ARTIFACT, SliceState.UNLOAD);

            await().atMost(SETTLE).untilAsserted(() -> assertThat(sliceStore.unloadRequests).containsExactly(ARTIFACT));
            await().atMost(SETTLE).untilAsserted(() -> assertThat(cluster.commands).anyMatch(NodeDeploymentStateRollbackOrphanTest::removesOwnKey));
            assertThat(cluster.commands)
                    .as("the unload chain must never write an ACTIVE claim for the slice it is unloading")
                    .noneMatch(command -> putsState(command, SliceState.ACTIVE));
        }
    }

    /// Silence needs a bound. Every negative assertion waits this long; the positive controls beside
    /// them show the request lands well inside it, so an empty store afterwards is a refusal.
    private static void settle() {
        await().pollDelay(SETTLE).atMost(SETTLE.plusSeconds(1)).until(() -> true);
    }

    private static boolean startsSlice(KVCommand<AetherKey> command) {
        return putsState(command, SliceState.LOADING) || putsState(command, SliceState.ACTIVATING)
               || putsState(command, SliceState.ACTIVE);
    }

    private static boolean putsState(KVCommand<AetherKey> command, SliceState state) {
        return command instanceof KVCommand.Put<AetherKey, ?> put
               && put.key() instanceof NodeArtifactKey key
               && key.artifact().equals(ARTIFACT)
               && put.value() instanceof NodeArtifactValue value
               && value.state() == state;
    }

    private static boolean removesOwnKey(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.Remove<AetherKey> remove
               && remove.key() instanceof NodeArtifactKey key
               && key.artifact().equals(ARTIFACT)
               && key.nodeId().equals(SELF);
    }

    private void seedNodeArtifact(NodeId node, Artifact artifact, SliceState state) {
        applyToKvStore(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(node, artifact),
                                           NodeArtifactValue.nodeArtifactValue(state)));
    }

    private void applyToKvStore(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> store,
                                             ClusterNode<KVCommand<AetherKey>> clusterNode,
                                             SliceStore store2) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                store2,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                clusterNode,
                                                store,
                                                invocations,
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    /// Records every request the node makes of the slice store. Loads and activations FAIL after
    /// being recorded: the property under test is whether the node asks at all, and a failing store
    /// terminates the chain in FAILED without needing a runnable `Slice`.
    private static final class RecordingSliceStore implements SliceStore {
        final List<Artifact> loaded = Collections.synchronizedList(new ArrayList<>());
        final List<Artifact> loadRequests = Collections.synchronizedList(new ArrayList<>());
        final List<Artifact> activateRequests = Collections.synchronizedList(new ArrayList<>());
        final List<Artifact> unloadRequests = Collections.synchronizedList(new ArrayList<>());

        @Override public List<LoadedSlice> loaded() {
            return loaded.stream().<LoadedSlice> map(RecordingSliceStore::loadedSlice).toList();
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            loadRequests.add(artifact);

            return Causes.cause("recording store: load refused").promise();
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            activateRequests.add(artifact);

            return Causes.cause("recording store: activate refused").promise();
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Causes.cause("recording store: deactivate refused").promise();
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            unloadRequests.add(artifact);
            loaded.remove(artifact);

            return Promise.unitPromise();
        }

        @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.none();
        }

        private static LoadedSlice loadedSlice(Artifact artifact) {
            return new LoadedSlice() {
                @Override public Artifact artifact() {
                    return artifact;
                }

                // ONE method: the endpoint unpublish only writes when the slice has methods, so an
                // empty list would make the UnloadPath pin pass against the defect.
                @Override public Slice slice() {
                    return () -> List.of(SliceMethod.sliceMethod(MethodName.methodName("ping").unwrap(),
                                                                 (Unit unit) -> Promise.success(unit),
                                                                 TypeToken.typeToken(Unit.class),
                                                                 TypeToken.typeToken(Unit.class)).unwrap());
                }
            };
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }
    }

    private static final class RecordingInvocationHandler implements org.pragmatica.aether.invoke.InvocationHandler {
        final List<Artifact> registered = Collections.synchronizedList(new ArrayList<>());

        @Override public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {}

        @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {
            registered.add(artifact);
        }

        @Override public void unregisterSlice(Artifact artifact) {}

        @Override public Option<SliceBridge> localSlice(Artifact artifact) {
            return Option.none();
        }

        @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
            return Option.none();
        }

        @Override public Option<org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector> metricsCollector() {
            return Option.none();
        }
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self, OTHER);
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
