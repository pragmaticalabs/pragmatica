// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2579 (from flake #2567): a slice whose one ACTIVATE write failed stayed LOADED, serving nothing, until the leader
/// changed. LOADED is neither in progress nor transitional, so neither reconcile nor the stuck remediator acted on it.
///
/// The leader is unchanged throughout. Every command the leader submits runs through the real [KVStore] applier,
/// except the sabotaged ACTIVATE writes, so the compare-and-set of a re-issue is decided by the real applier. The
/// node side is not modelled: "reaches ACTIVATE" means the leader's part of activation is committed.
@Timeout(60)
class ActivateWriteRecoveryTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("org.example:orders-api:1.0.0").unwrap();
    private static final ArtifactBase SLICE_BASE = ArtifactBase.artifactBase("org.example:orders-api").unwrap();
    private static final Version SLICE_VERSION = Version.version("1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final NodeArtifactKey ARTIFACT_KEY = NodeArtifactKey.nodeArtifactKey(NODE_A, SLICE);
    private static final NodeArtifactValue LOADED = NodeArtifactValue.nodeArtifactValue(SliceState.LOADED, 0L);
    /// ACTIVATING's timeout, the activation timeout the backstop waits out.
    private static final long BACKSTOP_MS = 90_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private InMemoryKvStore kvStore;
    private SabotagingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        kvStore = new InMemoryKvStore(router);
        cluster = new SabotagingClusterNode(SELF, kvStore);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory = fsm -> new ClusterDeploymentContext(fsm,
                                                                                                                                     SELF,
                                                                                                                                     cluster,
                                                                                                                                     kvStore,
                                                                                                                                     router,
                                                                                                                                     stubTopologyManager(SELF),
                                                                                                                                     stubSchemaOrchestrator(),
                                                                                                                                     () -> Set.of(SELF, NODE_A),
                                                                                                                                     () -> Set.of(SELF, NODE_A),
                                                                                                                                     Set::of,
                                                                                                                                     Set.of(SELF, NODE_A),
                                                                                                                                     DeploymentAtomicity.ALL_OR_NOTHING,
                                                                                                                                     3,
                                                                                                                                     timeSpan(300).seconds(),
                                                                                                                                     clock::get).dormant();

        harness = FsmTestHarness.harness("activate-recovery-" + System.nanoTime(), factory);
        kvStore.putRaw(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)));
        kvStore.put(SliceTargetKey.sliceTargetKey(SLICE_BASE), SliceTargetValue.sliceTargetValue(SLICE_VERSION, 1, Option.some(OWNER)));
        kvStore.put(ARTIFACT_KEY, LOADED);
    }

    /// Layer 1: the one ACTIVATE write fails and is not applied. The leader re-reads the committed state, finds it
    /// still LOADED, and re-issues ACTIVATE by a compare-and-set on that value. RED at rc5: nothing re-issues it.
    @Test
    void failedActivateWrite_isRetried_untilTheCommittedStateIsActivate() {
        cluster.sabotageActivatePuts(1, Sabotage.FAIL_UNAPPLIED);

        harness.dispatch(new Activate());

        await().atMost(Duration.ofSeconds(20))
               .until(() -> committedState().equals(Option.some(SliceState.ACTIVATE)));
        assertThat(cluster.activateTransactions()).as("the re-issue is a compare-and-set on the committed LOADED value")
                                                  .singleElement()
                                                  .satisfies(mutation -> assertThat(mutation.expected()).isEqualTo(Option.some(LOADED)));
    }

    /// Layer 1, the other outcome of a timed-out write: it applied after all. The recheck finds ACTIVATE committed and
    /// writes nothing, so a node that already moved on is never set back to ACTIVATE.
    @Test
    void failedActivateWrite_thatAppliedAfterAll_isNotReissued() {
        cluster.sabotageActivatePuts(1, Sabotage.FAIL_BUT_APPLIED);

        harness.dispatch(new Activate());

        assertThat(committedState()).isEqualTo(Option.some(SliceState.ACTIVATE));
        await().during(Duration.ofSeconds(8))
               .atMost(Duration.ofSeconds(10))
               .until(() -> cluster.activateTransactions().isEmpty());
    }

    /// Layer 2: the ACTIVATE write reports success but is lost. Within the activation timeout the backstop waits; past
    /// it, one reconcile re-issues ACTIVATE. RED at rc5: LOADED is live to reconcile and not transitional to the
    /// stuck remediator.
    @Test
    void lostActivateWrite_isReissuedByReconcile_onlyAfterTheActivationTimeout() {
        cluster.sabotageActivatePuts(1, Sabotage.LOST);
        harness.dispatch(new Activate());

        clock.addAndGet(BACKSTOP_MS / 2);
        activeState().reconcile();

        assertThat(cluster.activateTransactions()).as("control: within the activation timeout nothing is re-issued").isEmpty();

        clock.addAndGet(BACKSTOP_MS);
        activeState().reconcile();

        assertThat(committedState()).isEqualTo(Option.some(SliceState.ACTIVATE));
        assertThat(cluster.activateTransactions()).singleElement()
                                                  .satisfies(mutation -> assertThat(mutation.expected()).isEqualTo(Option.some(LOADED)));
    }

    /// Layer 2 for a slice the leader sees reach LOADED while it is already active, not at its rebuild: the window
    /// starts at that observation.
    @Test
    void lostActivateWrite_ofASliceObservedLoadedLive_isReissuedByReconcile() {
        kvStore.remove(ARTIFACT_KEY);
        harness.dispatch(new Activate());
        cluster.sabotageActivatePuts(1, Sabotage.LOST);

        kvStore.put(ARTIFACT_KEY, LOADED);
        harness.dispatch(new NodeArtifactPutReceived(new ValuePut<>(new KVCommand.Put<>(ARTIFACT_KEY, LOADED), Option.none())));

        assertThat(cluster.activatePuts()).as("control: the live LOADED observation issued the (lost) ACTIVATE").isEqualTo(1);

        clock.addAndGet(BACKSTOP_MS + 1);
        activeState().reconcile();

        assertThat(committedState()).isEqualTo(Option.some(SliceState.ACTIVATE));
    }

    /// Layer 2 keeps a slice held by its own blueprint's FAILED migration held: the re-issue runs the same gate.
    @Test
    void lostActivateWrite_ofAHeldSlice_staysHeld() {
        kvStore.put(SchemaVersionKey.schemaVersionKey("database"),
                    SchemaVersionValue.schemaVersionValue("database", 1, "V001__init.sql", SchemaStatus.FAILED, COORDS, OWNER));
        harness.dispatch(new Activate());

        clock.addAndGet(BACKSTOP_MS * 2);
        activeState().reconcile();

        assertThat(cluster.activatePuts()).as("control: the held slice was never issued ACTIVATE at all").isZero();
        assertThat(cluster.activateTransactions()).isEmpty();
        assertThat(committedState()).isEqualTo(Option.some(SliceState.LOADED));
    }

    private Option<SliceState> committedState() {
        return kvStore.get(ARTIFACT_KEY)
                      .filter(NodeArtifactValue.class::isInstance)
                      .map(NodeArtifactValue.class::cast)
                      .map(NodeArtifactValue::state);
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
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
                return List.of(self);
            }
        };
    }

    enum Sabotage {
        /// The write fails and does not apply.
        FAIL_UNAPPLIED,
        /// The write fails (a timeout) but applies.
        FAIL_BUT_APPLIED,
        /// The write reports success and never applies.
        LOST
    }

    /// Runs every command through the real applier, except the sabotaged plain ACTIVATE Puts. Records the ACTIVATE
    /// mutations of every leader transaction.
    private static final class SabotagingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final InMemoryKvStore kvStore;
        private final AtomicInteger toSabotage = new AtomicInteger();
        private final AtomicInteger activatePuts = new AtomicInteger();
        private final List<KVCommand.Mutation<AetherKey, AetherValue>> activateMutations = Collections.synchronizedList(new ArrayList<>());
        private volatile Sabotage sabotage = Sabotage.LOST;

        SabotagingClusterNode(NodeId self, InMemoryKvStore kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        void sabotageActivatePuts(int count, Sabotage how) {
            sabotage = how;
            toSabotage.set(count);
        }

        int activatePuts() {
            return activatePuts.get();
        }

        List<KVCommand.Mutation<AetherKey, AetherValue>> activateTransactions() {
            synchronized (activateMutations) {
                return List.copyOf(activateMutations);
            }
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            batch.forEach(this::recordActivateMutations);

            if (isActivatePut(batch)) {
                activatePuts.incrementAndGet();

                if (toSabotage.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    return sabotaged(batch);
                }
            }

            return Promise.success(kvStore.process(kvStore.createBatch(batch)));
        }

        private <R> Promise<List<R>> sabotaged(List<KVCommand<AetherKey>> batch) {
            return switch (sabotage) {
                case FAIL_UNAPPLIED -> Causes.cause("simulated ACTIVATE write timeout").promise();
                case FAIL_BUT_APPLIED -> appliedThenFailed(batch);
                case LOST -> Promise.success(List.of());
            };
        }

        private <R> Promise<List<R>> appliedThenFailed(List<KVCommand<AetherKey>> batch) {
            kvStore.process(kvStore.createBatch(batch));

            return Causes.cause("simulated ACTIVATE write timeout after apply").promise();
        }

        private static boolean isActivatePut(List<KVCommand<AetherKey>> batch) {
            return batch.stream()
                        .anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                             && put.key().equals(ARTIFACT_KEY)
                                             && put.value() instanceof NodeArtifactValue value
                                             && value.state() == SliceState.ACTIVATE);
        }

        @SuppressWarnings("unchecked")
        private void recordActivateMutations(KVCommand<AetherKey> command) {
            if (command instanceof KVCommand.LeaderTransaction<?, ?> transaction) {
                ((List<KVCommand.Mutation<AetherKey, AetherValue>>) (List<?>) transaction.mutations()).stream()
                                                                                                     .filter(mutation -> mutation.key().equals(ARTIFACT_KEY))
                                                                                                     .filter(mutation -> mutation.replacement()
                                                                                                                                 .filter(NodeArtifactValue.class::isInstance)
                                                                                                                                 .map(NodeArtifactValue.class::cast)
                                                                                                                                 .map(value -> value.state() == SliceState.ACTIVATE)
                                                                                                                                 .or(false))
                                                                                                     .forEach(activateMutations::add);
            }
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }

        void remove(AetherKey key) {
            process(createBatch(List.of(new KVCommand.Remove<>(key))));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void putRaw(KVCommand.Put<?, ?> put) {
            process(createBatch((List) List.of(put)));
        }
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

