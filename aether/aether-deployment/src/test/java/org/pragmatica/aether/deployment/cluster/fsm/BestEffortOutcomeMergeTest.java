// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #805 item 2 — the lost-update pin for `recordBestEffortFailureOutcome`.
///
/// **The race, stated exactly.** Merging a newly-failed slice into the blueprint's
/// `DeploymentOutcomeValue` is a read-modify-write. The read happens when the command is BUILT; the
/// Put applies later, after consensus. Two BEST_EFFORT slice failures that are both in flight before
/// either applies therefore read the same base and each Put the other's id away.
///
/// **Why these tests stage in-flight overlap rather than spawning threads.** The concurrency that
/// produces the loss is not thread overlap — `ClusterDeploymentState.Active` is a leader-pinned
/// singleton driven from the applier thread. It is *submission* overlap: two batches pending
/// simultaneously. And their commit order is NOT their submission order — `RabiaEngine` keeps
/// `pendingBatches` in a `ConcurrentSkipListMap` keyed by a SHA-256 content hash and every proposal
/// site takes `firstEntry()`, so which of two pending batches is decided first is a content-hash coin
/// flip on the happy path, needing no failed consensus round. [HoldingClusterNode] reproduces that
/// directly and lets each test pick the order, so BOTH orderings are pinned deterministically instead
/// of one being left to a scheduler that may never produce it.
///
/// **Why the concurrent cases await.** `Promise.onResult` runs its action synchronously only when the
/// promise is ALREADY resolved; on a promise resolved later it is queued as an event processor and
/// dispatched asynchronously (`PromiseImpl.processActions` — "event processors executed
/// asynchronously"). A held batch's confirm-and-retry therefore runs off the releasing thread, in the
/// test and in production alike, so these tests await convergence instead of asserting inline. The
/// sequential cases below deliberately do NOT await: their promises are pre-resolved, the confirm runs
/// inline, and asserting directly keeps that distinction visible.
///
/// Every test drives the real production path — a `NodeArtifactPutReceived` notification carrying a
/// fatal FAILED state, exactly what `ClusterDeploymentManager.onNodeArtifactPut` dispatches — and
/// asserts on committed KV state, never on a recorded command list. A test that inspected proposed
/// commands would pass against the unfixed code, because the unfixed code proposes both Puts too; the
/// loss is only visible after they apply.
class BestEffortOutcomeMergeTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE_A = Artifact.artifact("org.example:orders-api:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("org.example:billing-api:1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final String FAILURE = "slice class not found";

    private InMemoryKvStore kvStore;
    private HoldingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        kvStore = new InMemoryKvStore(router);
        cluster = new HoldingClusterNode(SELF, kvStore);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
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
                                                    DeploymentAtomicity.BEST_EFFORT,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    System::currentTimeMillis).dormant();

        harness = FsmTestHarness.harness("best-effort-outcome-" + System.nanoTime(), factory);
        harness.dispatch(new Activate());
        registerOwnedSlice(SLICE_A);
        registerOwnedSlice(SLICE_B);
    }

    @Nested
    class ConcurrentFailures {
        /// The ticketed race. Both merges are built while the outcome key is still absent, so each
        /// carries only its own slice id. Before #805 the second to apply overwrote the first and one
        /// id was gone; the version fence now rejects it and the confirm-and-retry re-merges against
        /// the value that actually landed.
        @Test
        void bothFailingSlices_areRecorded_whenTheSecondMergeAppliesAfterTheFirst() {
            cluster.holdOutcomeBatches(2);

            failSlice(SLICE_A);
            failSlice(SLICE_B);

            assertThat(cluster.heldCount()).as("both merges must be pending before either applies — otherwise the "
                                               + "second reads the first's value and there is no race to pin")
                                           .isEqualTo(2);

            cluster.releaseHeld(0, 1);

            awaitFailingSlices(SLICE_A, SLICE_B);
        }

        /// The same two pending merges, decided in the OPPOSITE order. This is not a duplicate: the
        /// order is chosen by SHA-256 batch-id comparison, so production reaches both, and a fix that
        /// happened to work only when submission order survives would pass the test above and fail
        /// this one.
        @Test
        void bothFailingSlices_areRecorded_whenTheSecondMergeAppliesBeforeTheFirst() {
            cluster.holdOutcomeBatches(2);

            failSlice(SLICE_A);
            failSlice(SLICE_B);

            assertThat(cluster.heldCount()).isEqualTo(2);

            cluster.releaseHeld(1, 0);

            awaitFailingSlices(SLICE_A, SLICE_B);
        }

        /// The fence must reject the stale merge rather than accept it — otherwise the retry would
        /// never fire and the test above would be passing for the wrong reason (a merge that happened
        /// to carry both ids). Pins the version chain the recovery walks: two accepted writes.
        @Test
        void theRecoveredMerge_advancesTheVersionChainByExactlyOne() {
            cluster.holdOutcomeBatches(2);

            failSlice(SLICE_A);
            failSlice(SLICE_B);
            cluster.releaseHeld(0, 1);

            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedOutcome().outcomeVersion()).as("first merge lands at version 1; "
                                                                                          + "the second is fenced out and its retry lands at version 2")
                                                                                     .isEqualTo(2L));
        }

        @Test
        void theRecordStaysFAILED_afterTheRecoveredMerge() {
            cluster.holdOutcomeBatches(2);

            failSlice(SLICE_A);
            failSlice(SLICE_B);
            cluster.releaseHeld(0, 1);

            awaitFailingSlices(SLICE_A, SLICE_B);

            assertThat(recordedOutcome().status()).isEqualTo(DeploymentOutcomeStatus.FAILED);
        }
    }

    @Nested
    class SequentialFailures {
        /// The uncontended path must be untouched: when the first merge has already applied, the
        /// second reads it, merges onto it, and is accepted at version 2 with no retry. This is the
        /// behaviour that existed before #805 and it must survive the fence.
        @Test
        void bothFailingSlices_areRecorded_whenTheMergesDoNotOverlap() {
            failSlice(SLICE_A);
            failSlice(SLICE_B);

            assertThat(recordedFailingSlices()).containsExactlyInAnyOrder(SLICE_A.asString(), SLICE_B.asString());
            assertThat(recordedOutcome().outcomeVersion()).as("two uncontended writes, no rejection, no retry")
                                                          .isEqualTo(2L);
        }

        /// `handleDeterministicFailure` returns early for an artifact already in `permanentlyFailed`,
        /// so a repeated notification must not append a duplicate id nor burn a version.
        @Test
        void aRepeatedFailure_doesNotDuplicateTheSliceId() {
            failSlice(SLICE_A);
            failSlice(SLICE_A);

            assertThat(recordedFailingSlices()).containsExactly(SLICE_A.asString());
            assertThat(recordedOutcome().outcomeVersion()).isEqualTo(1L);
        }
    }

    // --- fixture ---

    /// Drives the exact notification production dispatches on a fatal slice failure. `fatal = true`
    /// selects `handleDeterministicFailure` over the retry path.
    private void failSlice(Artifact artifact) {
        var key = NodeArtifactKey.nodeArtifactKey(NODE_A, artifact);
        var value = new NodeArtifactValue(SliceState.FAILED, Option.some(FAILURE), true, 0, List.of(), 0L);
        var put = new KVCommand.Put<NodeArtifactKey, NodeArtifactValue>(key, value);

        harness.dispatch(new NodeArtifactPutReceived(new ValuePut<>(put, Option.none())));
    }

    /// `recordBestEffortFailureOutcome` resolves the owning blueprint from the FSM's in-memory
    /// `blueprints` mirror. That lookup is out of #805's scope (item 1 moved the SCHEMA GATE's owner
    /// resolution, not this one), so the mirror is seeded directly here.
    private void registerOwnedSlice(Artifact artifact) {
        activeState().blueprints()
                     .put(artifact, Blueprint.blueprint(artifact, 1, 1, Option.some(OWNER), true));
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    /// Waits for the merge chain to converge on exactly `expected`. A timeout here means the retry
    /// never recovered the fenced-out id — the very loss #805 item 2 fixes.
    private void awaitFailingSlices(Artifact... expected) {
        var ids = java.util.Arrays.stream(expected)
                                  .map(Artifact::asString)
                                  .toArray(String[]::new);

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(recordedFailingSlices()).containsExactlyInAnyOrder(ids));
    }

    private List<String> recordedFailingSlices() {
        return recordedOutcome().failingSlices();
    }

    private DeploymentOutcomeValue recordedOutcome() {
        return kvStore.get(DeploymentOutcomeKey.deploymentOutcomeKey(OWNER))
                      .filter(DeploymentOutcomeValue.class::isInstance)
                      .map(DeploymentOutcomeValue.class::cast)
                      .or(() -> org.junit.jupiter.api.Assertions.fail("no deployment-outcome record was committed"));
    }

    /// A cluster node that APPLIES batches into the real [KVStore] — so the applier's `VersionFenced`
    /// successor check actually runs — and can hold deployment-outcome batches pending, reproducing
    /// two merges in flight at once.
    ///
    /// Only outcome batches are held; unload/allocation commands issued on the same failure path pass
    /// straight through, so the hold window contains exactly the writes under test. The hold budget is
    /// consumed, not standing: once `holdBudget` batches are captured, later outcome batches (the
    /// retries) apply immediately, which is what lets a released batch's confirm-and-retry run to
    /// completion inside `releaseHeld`.
    ///
    /// `apply`'s Promise resolves only AFTER the batch has been applied to the state machine, matching
    /// `RabiaEngine.commitChanges`, which calls `stateMachine.process` before `promise.succeed`. The
    /// retry protocol depends on that ordering: its re-read must observe its own batch's effect.
    private static final class HoldingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final InMemoryKvStore store;
        private final List<HeldBatch> held = Collections.synchronizedList(new ArrayList<>());
        private int holdBudget;

        private record HeldBatch(List<KVCommand<AetherKey>> commands, Promise<List<Object>> promise) {}

        HoldingClusterNode(NodeId self, InMemoryKvStore store) {
            this.self = self;
            this.store = store;
        }

        void holdOutcomeBatches(int count) {
            holdBudget = count;
        }

        int heldCount() {
            return held.size();
        }

        /// Applies the held batches in the given order, resolving each promise as it lands. Resolution
        /// synchronously runs the FSM's confirm-and-retry for that batch, so a rejected merge is
        /// recovered before the next held batch is released — the same sequencing the applier thread
        /// produces in production.
        void releaseHeld(int... order) {
            for (var index : order) {
                var batch = held.get(index);

                store.applyBatch(batch.commands());
                batch.promise()
                     .succeed(List.of());
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            if (holdBudget > 0 && touchesOutcomeRecord(batch)) {
                holdBudget--;
                var pending = Promise.<List<Object>> promise();

                held.add(new HeldBatch(List.copyOf(batch), pending));

                return (Promise<List<R>>) (Promise<?>) pending;
            }

            store.applyBatch(batch);

            return Promise.success(List.of());
        }

        private static boolean touchesOutcomeRecord(List<KVCommand<AetherKey>> batch) {
            return batch.stream()
                        .anyMatch(command -> command.key() instanceof DeploymentOutcomeKey);
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
    }

    /// Extends the real [KVStore], so `applyBatch` goes through `process` and therefore through the
    /// applier's fences. A double that stored values directly would accept every write and make every
    /// assertion here vacuous.
    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        /// `synchronized` because production applies every decision on ONE thread
        /// (`RabiaEngine`'s single-thread executor), and `KVStore.process`'s fence is a read of
        /// committed storage followed by a write — not atomic. Serializing here models the applier
        /// faithfully; leaving it unserialized would let the test's release thread and an async retry
        /// interleave inside the fence in a way production cannot produce.
        synchronized void applyBatch(List<KVCommand<AetherKey>> commands) {
            process(createBatch(List.copyOf(commands)));
        }
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
}
