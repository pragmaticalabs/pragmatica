// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.function.LongSupplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetRemoveReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.apache.logging.log4j.Level;
import org.pragmatica.testlogging.LogCapture;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2628 (D4, D6): the slice-target writes of a blueprint are correct whatever order Rabia commits them in.
///
/// Rabia commits pending batches in content-hash order, not submission order, and the leader submitted the target put
/// (on the blueprint's put notification) and the target removal (on its removal notification) fire-and-forget. Every
/// test here holds the leader's batches, lets the real `KVStore` apply them in an order the test chooses (the reverse of
/// submission), and bridges the store's real notifications back into the real leader FSM, so the leader sees exactly
/// what the applier committed. Nothing here depends on an engine FIFO.
@Timeout(60)
class SliceTargetOrderingTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId OTHER = new NodeId("node-other");
    private static final BlueprintId OWNER = BlueprintId.blueprintId("com.example:orders-app:1.0.0").unwrap();
    private static final Artifact V1 = Artifact.artifact("com.example:orders-api:1.0.0").unwrap();
    private static final Artifact V2 = Artifact.artifact("com.example:orders-api:2.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(V1.base());
    private static final AppBlueprintKey BLUEPRINT_KEY = AppBlueprintKey.appBlueprintKey(OWNER);

    private KVStore<AetherKey, AetherValue> store;
    private HoldingClusterNode cluster;
    private LogCapture log;
    private LogCapture routerLog;
    private final AtomicLong clockMs = new AtomicLong(10_000_000L);
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void leaderWithHeldWrites() {
        var router = MessageRouter.mutable();

        log = LogCapture.capture(ClusterDeploymentState.Active.class, Level.INFO, false);
        routerLog = LogCapture.capture("org.pragmatica.messaging.MessageRouter", Level.ERROR, true);

        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        commitLeader();
        cluster = new HoldingClusterNode(store);
        harness = activeLeader(cluster, store, clockMs::get);
        bridgeNotifications(router);
    }

    @AfterEach
    void releaseLog() {
        var thrown = routerLog.messages(Level.ERROR);

        log.close();
        routerLog.close();
        // The store routes its notifications to the leader FSM: a handler that threw is logged by the router and swallowed.
        assertThat(thrown).as("no notification handler of the leader threw").isEmpty();
    }

    /// The ERROR lines logged so far, after the continuations of the promises the test resolved have had time to run.
    private List<String> awaitErrors() {
        try {
            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !log.messages(Level.ERROR).isEmpty());
        } catch (ConditionTimeoutException _) {
            return List.of();
        }

        return log.messages(Level.ERROR);
    }

    // ---- D4 ----

    /// The target put of a publish and the removal of its delete commit in the reverse of their submission: at rc5 the
    /// put lands last, re-adds the target for the deleted blueprint and the leader LOADs it.
    @Test
    void publishThenDelete_commitReversed_leavesNoOrphanTargetAndNoLoad() {
        publish(blueprintOf(V1, "attempt-1"));
        delete();

        cluster.commitAllInReverseSubmissionOrder();

        assertThat(holdsTarget(store)).as("no target survives the delete of its blueprint").isFalse();
        assertThat(loads()).as("nothing is LOADed for a deleted blueprint").isEmpty();
        assertThat(active().blueprints()).as("the leader does not hold the deleted blueprint's slice").doesNotContainKey(V1);
    }

    /// Control of the instrument: the LOAD detector and the commit path see a LOAD when the publish is not deleted.
    @Test
    void publishAndCommit_control_issuesLoad() {
        publish(blueprintOf(V1, "attempt-1"));

        cluster.commitAllInSubmissionOrder();

        assertThat(holdsTarget(store)).isTrue();
        assertThat(loads()).as("control: a committed target is LOADed").isNotEmpty();
    }

    /// The removal of a deleted blueprint must not remove the target of a republish that committed first.
    @Test
    void deleteThenRepublish_removalCommitsLast_isRefused() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        delete();
        var removalSeq = cluster.lastTargetWriteSeq();
        publish(blueprintOf(V2, "attempt-2"));
        var republishSeq = cluster.lastTargetWriteSeq();

        cluster.commit(republishSeq);
        cluster.commit(removalSeq);

        assertThat(committedTarget().map(SliceTargetValue::currentVersion)).as("the republish's target survives the stale removal")
                                                                           .isEqualTo(Option.some(V2.version()));
    }

    /// The delete itself still removes the target when nothing races it (the fence refuses stale removals, never real ones).
    @Test
    void publishCommitThenDelete_removesTheTarget() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        assertThat(holdsTarget(store)).as("control: the target committed").isTrue();

        delete();
        cluster.commitAllInSubmissionOrder();

        assertThat(holdsTarget(store)).as("the deleted blueprint's target is removed").isFalse();
    }

    /// Two versions of one slice base owned by the deleted blueprint (a rollout in the mirror) are ONE target key: the removal
    /// names it once, because the applier refuses a transaction that names a key twice.
    @Test
    void deleteWithTwoVersionsOfOneBaseInTheMirror_removesTheTargetOnce() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        active().blueprints().put(V2, Blueprint.blueprint(V2, 2, 1, Option.some(OWNER), true));

        delete();
        cluster.commitAllInSubmissionOrder();

        assertThat(holdsTarget(store)).as("the target is removed although the base is named by two versions").isFalse();
    }

    /// A removal built over a target that moves before it commits (an autoscaler write) is refused by its compare-and-set and
    /// rebuilt against the moved target, so the delete still removes it.
    @Test
    void removalOverAMovedTarget_isRebuiltAndLands() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        delete();
        var removalSeq = cluster.lastTargetWriteSeq();

        moveTarget(7);
        cluster.commit(removalSeq);
        var rebuilt = cluster.awaitHeldTargetWrite();

        assertThat(rebuilt.isPresent()).as("the refused removal is rebuilt").isTrue();
        cluster.commit(rebuilt.unwrap());
        assertThat(holdsTarget(store)).as("and the rebuilt removal lands").isFalse();
    }

    /// The same budget bounds a removal that keeps losing to a moving target: the first submission and 5 rebuilds.
    @Test
    void removalOverATargetThatKeepsMoving_stopsAfterTheBudget() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        var before = cluster.submittedCount();

        delete();
        for (var round = 0; round < 20; round++) {
            var held = cluster.awaitHeldTargetWrite();

            if (held.isEmpty()) {
                break;
            }

            moveTarget(10 + round);
            cluster.commit(held.unwrap());
        }

        assertThat(cluster.targetWritesSince(before)).as("1 submission and 5 rebuilds, then it gives up").isEqualTo(6);
        assertThat(log.messages(Level.ERROR)).as("and says so").anyMatch(message -> message.contains("NOT removed"));
    }

    /// The last allowed attempt of a removal is allowed to land: it is not reported as given up.
    @Test
    void removalLandingOnTheLastAttempt_reportsNoFailure() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();

        delete();
        for (var refusal = 0; refusal < 5; refusal++) {
            refuseHeldTargetWrite(10 + refusal);
        }
        cluster.commit(cluster.awaitHeldTargetWrite().unwrap());

        assertThat(holdsTarget(store)).as("the sixth submission removed it").isFalse();
        assertThat(cluster.awaitHeldTargetWrite().isPresent()).as("and nothing is rebuilt after it").isFalse();
        assertThat(log.messages(Level.ERROR)).as("and nothing is reported as given up").isEmpty();
    }

    /// A removal refused because the blueprint was published again is not rebuilt: the publish owns the target now.
    @Test
    void removalRefusedByARepublish_isNotRebuilt() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        delete();
        var removalSeq = cluster.lastTargetWriteSeq();
        publish(blueprintOf(V2, "attempt-2"));
        var republishSeq = cluster.lastTargetWriteSeq();

        cluster.commit(republishSeq);
        cluster.commit(removalSeq);

        assertThat(cluster.awaitHeldTargetWrite().isPresent()).as("the refused removal is not rebuilt").isFalse();
        assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2.version()));
    }

    /// The removal's blueprint fence, apart from its compare-and-set: a register-only republish writes no target, so nothing
    /// moves the target the removal read, and only the blueprint being committed again refuses the removal.
    @Test
    void deleteThenRegisterOnlyRepublish_removalCommitsLast_isRefused() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        delete();
        var removalSeq = cluster.lastTargetWriteSeq();
        publish(AppBlueprintValue.appBlueprintValue(blueprintOf(V1, "attempt-2").blueprint(), true, "attempt-2"));

        cluster.commit(removalSeq);

        assertThat(holdsTarget(store)).as("the republished blueprint keeps its target").isTrue();
    }

    // ---- D6 ----

    /// Two publishes of one blueprint id; the first publish's target commits last. At rc5 the target diverges from the
    /// committed blueprint (v1 over v2).
    @Test
    void twoPublishes_v1TargetCommitsLast_finalTargetMatchesV2() {
        publish(blueprintOf(V1, "attempt-1"));
        var firstSeq = cluster.lastTargetWriteSeq();
        publish(blueprintOf(V2, "attempt-2"));
        var secondSeq = cluster.lastTargetWriteSeq();

        cluster.commit(secondSeq);
        cluster.commit(firstSeq);

        assertThat(committedTarget().map(SliceTargetValue::currentVersion)).as("the final target is the committed blueprint's")
                                                                           .isEqualTo(Option.some(V2.version()));
        assertThat(active().blueprints()).containsKey(V2).doesNotContainKey(V1);
        assertThat(cluster.awaitHeldTargetWrite().isPresent()).as("the refused first publish is not rebuilt: the second owns the target")
                                                              .isFalse();
    }

    /// A republish built over a target that moves before it commits (an autoscaler write) is refused by the compare-and-set,
    /// then rebuilt against the moved target: the declaration lands and the scale the autoscaler chose is carried.
    /// (A promise's continuations run on another thread, so the rebuild is awaited, not assumed.)
    @Test
    void republishOverAMovedTarget_isRebuiltAgainstTheMovedTarget() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();

        publish(blueprintOf(V2, "attempt-2"));
        var republishSeq = cluster.lastTargetWriteSeq();
        moveTarget(5);
        cluster.commit(republishSeq);
        var rebuilt = cluster.awaitHeldTargetWrite();

        assertThat(rebuilt.isPresent()).as("the refused republish is rebuilt").isTrue();
        cluster.commit(rebuilt.unwrap());

        var landed = committedTarget().unwrap();

        assertThat(landed.currentVersion()).as("the rebuilt republish landed").isEqualTo(V2.version());
        assertThat(landed.targetInstances()).as("the autoscaler's count is carried through the rebuild").isEqualTo(5);
    }

    /// A refused write is rebuilt a bounded number of times, never without end: a target that moves under every attempt
    /// stops the republish after the budget (the first submission and 5 rebuilds).
    @Test
    void republishOverATargetThatKeepsMoving_stopsAfterTheBudget() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        var before = cluster.submittedCount();

        publish(blueprintOf(V2, "attempt-2"));
        for (var round = 0; round < 20; round++) {
            var held = cluster.awaitHeldTargetWrite();

            if (held.isEmpty()) {
                break;
            }

            moveTarget(10 + round);
            cluster.commit(held.unwrap());
        }

        assertThat(cluster.targetWritesSince(before)).as("1 submission and 5 rebuilds, then it gives up").isEqualTo(6);
        assertThat(committedTarget().unwrap().currentVersion()).as("and the declaration never landed").isEqualTo(V1.version());
        assertThat(log.messages(Level.ERROR)).as("and says so").anyMatch(message -> message.contains("NOT written"));
    }

    /// The last allowed attempt is allowed to land: it is not reported as given up.
    @Test
    void republishLandingOnTheLastAttempt_reportsNoFailure() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();

        publish(blueprintOf(V2, "attempt-2"));
        for (var refusal = 0; refusal < 5; refusal++) {
            refuseHeldTargetWrite(10 + refusal);
        }
        cluster.commit(cluster.awaitHeldTargetWrite().unwrap());

        assertThat(committedTarget().unwrap().currentVersion()).as("the sixth submission landed").isEqualTo(V2.version());
        assertThat(cluster.awaitHeldTargetWrite().isPresent()).as("and nothing is rebuilt after it").isFalse();
        assertThat(log.messages(Level.ERROR)).as("and nothing is reported as given up").isEmpty();
    }

    /// A target write that fails outright (consensus could not commit it) is reported, naming the write.
    @Test
    void failedTargetWrite_isReported() {
        publish(blueprintOf(V1, "attempt-1"));

        cluster.fail(cluster.lastTargetWriteSeq(), org.pragmatica.lang.utils.Causes.cause("no quorum"));

        assertThat(awaitErrors()).anyMatch(message -> message.contains("DEPLOY_TARGET_PUT") && message.contains("no quorum"));
    }

    /// A removal that fails outright is reported, naming the write.
    @Test
    void failedTargetRemoval_isReported() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        delete();

        cluster.fail(cluster.lastTargetWriteSeq(), org.pragmatica.lang.utils.Causes.cause("no quorum"));

        assertThat(awaitErrors()).anyMatch(message -> message.contains("BLUEPRINT_DELETE_TARGET_REMOVE") && message.contains("no quorum"));
    }

    /// A register-only publish writes no target at all, so there is nothing to fence and nothing is submitted.
    @Test
    void registerOnlyPublish_submitsNoTargetWrite() {
        var before = cluster.submittedCount();

        publish(AppBlueprintValue.appBlueprintValue(blueprintOf(V1, "attempt-1").blueprint(), true, "attempt-1"));

        assertThat(cluster.targetWritesSince(before)).isZero();
        assertThat(log.messages(Level.ERROR)).isEmpty();
        assertThat(active().inFlightBlueprints()).as("and the apply is still tracked").containsKey(OWNER);
    }

    /// The first publish's targets must not be written twice for a base a blueprint lists twice: the applier refuses a
    /// transaction that names one key twice, which would refuse the whole publish for good.
    @Test
    void blueprintListingOneBaseTwice_stillCommitsItsTarget() {
        var twice = ExpandedBlueprint.expandedBlueprint(OWNER,
                                                        List.of(ResolvedSlice.resolvedSlice(V1, 2, 1, false, Set.of()).unwrap(),
                                                                ResolvedSlice.resolvedSlice(V2, 2, 1, false, Set.of()).unwrap()),
                                                        Option.none());

        publish(AppBlueprintValue.appBlueprintValue(twice, false, "attempt-1"));
        cluster.commitAllInSubmissionOrder();

        assertThat(committedTarget().map(SliceTargetValue::currentVersion)).as("the later declaration wins")
                                                                           .isEqualTo(Option.some(V2.version()));
        assertThat(cluster.awaitHeldTargetWrite().isPresent()).as("and the earlier declaration is not mistaken for unlanded").isFalse();
    }

    /// A republish that moves nothing but one declared field is not mistaken for landed when a moved target refuses it:
    /// each declared field is part of what "the target declares the blueprint" means.
    @ParameterizedTest
    @EnumSource(Declared.class)
    void republishOfOneChangedField_overAMovedTarget_isRebuiltUntilTheFieldLands(Declared field) {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        field.prepareCommitted(store, committedTarget().unwrap());

        publish(field.republish(OWNER));
        var republishSeq = cluster.lastTargetWriteSeq();
        moveTarget(9);
        cluster.commit(republishSeq);
        var rebuilt = cluster.awaitHeldTargetWrite();

        assertThat(rebuilt.isPresent()).as("the refused republish of %s is rebuilt", field).isTrue();
        cluster.commit(rebuilt.unwrap());
        assertThat(field.lands(committedTarget().unwrap())).as("%s lands", field).isTrue();
    }

    /// Declared fields of a republish of v1 over the committed v1 target that the committed value does not have yet.
    enum Declared {
        MAX_INSTANCES {
            @Override ResolvedSlice slice() {return ResolvedSlice.resolvedSlice(V1, 2, 1, false, Set.of(), Option.some(4), Option.none(), Option.none()).unwrap();}
            @Override boolean lands(SliceTargetValue target) {return target.maxInstances().equals(Option.some(4));}
        },
        SCALE_UP_THRESHOLD {
            @Override ResolvedSlice slice() {return ResolvedSlice.resolvedSlice(V1, 2, 1, false, Set.of(), Option.none(), Option.some(0.7), Option.none()).unwrap();}
            @Override boolean lands(SliceTargetValue target) {return target.scaleUpThreshold().equals(Option.some(0.7));}
        },
        SCALE_DOWN_THRESHOLD {
            @Override ResolvedSlice slice() {return ResolvedSlice.resolvedSlice(V1, 2, 1, false, Set.of(), Option.none(), Option.none(), Option.some(0.2)).unwrap();}
            @Override boolean lands(SliceTargetValue target) {return target.scaleDownThreshold().equals(Option.some(0.2));}
        },
        MIN_AVAILABLE {
            @Override ResolvedSlice slice() {return ResolvedSlice.resolvedSlice(V1, 2, 2, false, Set.of()).unwrap();}
            @Override boolean lands(SliceTargetValue target) {return target.minInstances() == 2;}
        },
        OWNER {
            @Override ResolvedSlice slice() {return ResolvedSlice.resolvedSlice(V1, 2, 1, false, Set.of()).unwrap();}
            @Override boolean lands(SliceTargetValue target) {return target.owningBlueprint().equals(Option.some(SliceTargetOrderingTest.OWNER));}

            /// The committed target is owned by another version of the blueprint (a different id, no ownership conflict), so only the owner differs.
            @Override void prepareCommitted(KVStore<AetherKey, AetherValue> store, SliceTargetValue committed) {
                var other = BlueprintId.blueprintId("com.example:orders-app:0.9.0").unwrap();
                var reowned = SliceTargetValue.sliceTargetValue(committed.currentVersion(), 2, 1, Option.some(other), Option.none(), Option.none(), Option.none());

                store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(TARGET_KEY, reowned))));
            }
        };

        abstract ResolvedSlice slice();

        abstract boolean lands(SliceTargetValue target);

        void prepareCommitted(KVStore<AetherKey, AetherValue> store, SliceTargetValue committed) {}

        AppBlueprintValue republish(BlueprintId owner) {
            return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(owner, List.of(slice()), Option.none()), false, "attempt-2");
        }
    }

    /// A refused write is not rebuilt by a leader whose term ended: nothing more is written, and nothing reports a failure.
    @Test
    void refusedWriteOfAnEndedTerm_isNotRebuiltAndReportsNothing() {
        publish(blueprintOf(V1, "attempt-1"));
        cluster.commitAllInSubmissionOrder();
        publish(blueprintOf(V2, "attempt-2"));
        var republishSeq = cluster.lastTargetWriteSeq();
        moveTarget(5);

        active().onExit();
        cluster.commit(republishSeq);

        assertThat(cluster.awaitHeldTargetWrite().isPresent()).as("an ended term rebuilds nothing").isFalse();
        assertThat(awaitErrors()).as("and does not report the refused rebuild as a failure").isEmpty();
    }

    // ---- restore ----

    @Nested
    class LeaderChange {
        /// A new leader restoring from the store finds a target whose blueprint is gone. At rc5 it re-adopts the orphan
        /// (the mirror holds it again, owned by the deleted blueprint).
        @Test
        void orphanTarget_isNotReAdopted_andIsSwept() {
            var fresh = freshStore();
            var applying = new ApplyingClusterNode(fresh);

            commitLeader(fresh);
            fresh.process(fresh.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(TARGET_KEY, targetOf(V1, Option.some(OWNER))))));

            var restored = (ClusterDeploymentState.Active) activeLeader(applying, fresh).state();

            assertThat(restored.blueprints()).as("the orphan is not re-adopted").doesNotContainKey(V1);
            assertThat(holdsTarget(fresh)).as("the orphan is swept from the store").isFalse();
            assertThat(applying.loads()).as("nothing is LOADed for it").isEmpty();
        }

        /// Control: a target whose blueprint is committed is adopted, so the refusal above is about the missing blueprint.
        @Test
        void ownedTargetWithItsBlueprint_isAdopted() {
            var fresh = freshStore();
            var applying = new ApplyingClusterNode(fresh);

            commitLeader(fresh);
            fresh.process(fresh.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(BLUEPRINT_KEY, blueprintOf(V1, "attempt-1")),
                                                                         new KVCommand.Put<>(TARGET_KEY, targetOf(V1, Option.some(OWNER))))));

            var restored = (ClusterDeploymentState.Active) activeLeader(applying, fresh).state();

            assertThat(restored.blueprints()).containsKey(V1);
            assertThat(holdsTarget(fresh)).isTrue();
        }

        /// An unowned target (the scale API's own) has no blueprint to be an orphan of and is adopted as before.
        @Test
        void unownedTarget_isAdopted() {
            var fresh = freshStore();
            var applying = new ApplyingClusterNode(fresh);

            commitLeader(fresh);
            fresh.process(fresh.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(TARGET_KEY, targetOf(V1, Option.none())))));

            var restored = (ClusterDeploymentState.Active) activeLeader(applying, fresh).state();

            assertThat(restored.blueprints()).containsKey(V1);
            assertThat(holdsTarget(fresh)).isTrue();
        }
    }

    // ---- awaited resubmission (#1109), driven through the leader's own gate and clock ----

    @Nested
    class AwaitedResubmission {
        /// The resubmission of a lost target put is built while the blueprint is committed, and the blueprint is deleted before
        /// it commits. At rc5 it is a plain put and re-adds the target.
        @Test
        void resubmissionCommittingAfterTheDelete_isRefused() {
            publish(blueprintOf(V1, "attempt-1"));
            cluster.drop(cluster.lastTargetWriteSeq());

            pastTheAwaitedBound();
            var resubmissionSeq = cluster.lastTargetWriteSeq();
            delete();
            cluster.commit(resubmissionSeq);

            assertThat(holdsTarget(store)).as("a resubmission of a deleted blueprint's target adds nothing").isFalse();
        }

        /// A resubmission that fails outright is reported, naming the write.
        @Test
        void failedResubmission_isReported() {
            publish(blueprintOf(V1, "attempt-1"));
            cluster.drop(cluster.lastTargetWriteSeq());

            pastTheAwaitedBound();
            cluster.fail(cluster.lastTargetWriteSeq(), org.pragmatica.lang.utils.Causes.cause("no quorum"));

            assertThat(awaitErrors()).anyMatch(message -> message.contains("AWAITED_TARGET_RESUBMIT") && message.contains("no quorum"));
        }

        /// Control: the same resubmission commits while the blueprint is committed, so the refusal above is the fence.
        @Test
        void resubmissionWhileTheBlueprintIsCommitted_lands() {
            publish(blueprintOf(V1, "attempt-1"));
            var lostSeq = cluster.lastTargetWriteSeq();

            cluster.drop(lostSeq);
            pastTheAwaitedBound();
            cluster.commit(cluster.lastTargetWriteSeq());

            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V1.version()));
        }

        /// The resubmission is a compare-and-set on the target committed when it is built, so one over a committed target of
        /// another version still lands.
        @Test
        void resubmissionOverACommittedTargetOfAnotherVersion_lands() {
            publish(blueprintOf(V1, "attempt-1"));
            cluster.commitAllInSubmissionOrder();
            store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(TARGET_KEY, targetOf(V2, Option.some(OWNER))))));
            var before = cluster.submittedCount();

            active().resubmitTarget(V1, Blueprint.blueprint(V1, 2, 1, Option.some(OWNER), true), targetOf(V1, Option.some(OWNER)));
            cluster.commit(cluster.lastTargetWriteSeq());

            assertThat(cluster.targetWritesSince(before)).isEqualTo(1);
            assertThat(committedTarget().map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V1.version()));
        }

        /// A slice the committed blueprint no longer declares (the mirror still holds it) is not resubmitted at all.
        @Test
        void sliceTheCommittedBlueprintDoesNotDeclare_isNotResubmitted() {
            publish(blueprintOf(V1, "attempt-1"));
            cluster.drop(cluster.lastTargetWriteSeq());
            active().blueprints().put(V2, Blueprint.blueprint(V2, 1, 1, Option.some(OWNER), true));
            var before = cluster.submittedCount();

            pastTheAwaitedBound();

            assertThat(cluster.targetWritesNaming(V2.version(), before)).as("nothing is written for the undeclared v2").isZero();
        }

        /// An unowned slice has no blueprint to fence on and is resubmitted as the plain put it always was.
        @Test
        void unownedSlice_isResubmittedAsAPlainPut() {
            active().blueprints().put(V1, Blueprint.blueprint(V1, 1, 1, Option.none(), true));
            var before = cluster.submittedCount();

            pastTheAwaitedBound();

            assertThat(cluster.plainTargetPutsSince(before)).isEqualTo(1);
        }
    }

    // ---- helpers ----

    private ClusterDeploymentState.Active active() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private void publish(AppBlueprintValue value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(BLUEPRINT_KEY, value))));
    }

    /// Waits for the rebuilt write to be held, then moves the target under it and commits it: the compare-and-set refuses it.
    /// (Moving first would race the rebuild, which reads the target when it is built.)
    private void refuseHeldTargetWrite(int instances) {
        var held = cluster.awaitHeldTargetWrite().unwrap();

        moveTarget(instances);
        cluster.commit(held);
    }

    /// An autoscaler write: the committed target changes under whatever is in flight.
    private void moveTarget(int instances) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(TARGET_KEY, committedTarget().unwrap().withInstances(instances)))));
    }

    /// The leader refuses LOAD while no committed target names the slice; at the bound it resubmits the target put.
    private void pastTheAwaitedBound() {
        active().reconcile();
        clockMs.addAndGet(active().ctx().awaitedTargetBound().millis());
        active().reconcile();
    }

    private void delete() {
        store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Remove<>(BLUEPRINT_KEY))));
    }

    private static boolean holdsTarget(KVStore<AetherKey, AetherValue> kv) {
        return kv.get(TARGET_KEY).isPresent();
    }

    private Option<SliceTargetValue> committedTarget() {
        return store.get(TARGET_KEY).filter(SliceTargetValue.class::isInstance).map(SliceTargetValue.class::cast);
    }

    private List<KVCommand<AetherKey>> loads() {
        return cluster.allSubmitted().stream().filter(SliceTargetOrderingTest::isLoad).toList();
    }

    private static boolean isLoad(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.Put<AetherKey, ?> put
               && put.key() instanceof NodeArtifactKey
               && put.value() instanceof NodeArtifactValue value
               && value.state() == SliceState.LOAD;
    }

    private static AppBlueprintValue blueprintOf(Artifact slice, String attemptId) {
        var resolved = ResolvedSlice.resolvedSlice(slice, 2, 1, false, Set.of()).unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(resolved), Option.none()),
                                                   false,
                                                   attemptId);
    }

    private static SliceTargetValue targetOf(Artifact slice, Option<BlueprintId> owner) {
        return SliceTargetValue.sliceTargetValue(slice.version(), 2, 1, owner, Option.none(), Option.none(), Option.none());
    }

    private static KVStore<AetherKey, AetherValue> freshStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private void commitLeader() {
        commitLeader(store);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commitLeader(KVStore<AetherKey, AetherValue> target) {
        target.process(target.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)))));
    }

    /// The store's real notifications, delivered to the leader FSM as `AetherNode` wires them.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void bridgeNotifications(MessageRouter.MutableRouter router) {
        router.addRoute(ValuePut.class, (Consumer<ValuePut>) this::deliverPut);
        router.addRoute(ValueRemove.class, (Consumer<ValueRemove>) this::deliverRemove);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void deliverPut(ValuePut put) {
        switch (put.cause().key()) {
            case AppBlueprintKey _ -> harness.dispatch(new AppBlueprintPutReceived(put));
            case SliceTargetKey _ -> harness.dispatch(new SliceTargetPutReceived(put));
            default -> {}
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void deliverRemove(ValueRemove remove) {
        switch (remove.cause().key()) {
            case AppBlueprintKey _ -> harness.dispatch(new AppBlueprintRemoveReceived(remove));
            case SliceTargetKey _ -> harness.dispatch(new SliceTargetRemoveReceived(remove));
            default -> {}
        }
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> activeLeader(ClusterNode<KVCommand<AetherKey>> node,
                                                                                       KVStore<AetherKey, AetherValue> kv) {
        return activeLeader(node, kv, () -> 10_000_000L);
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> activeLeader(ClusterNode<KVCommand<AetherKey>> node,
                                                                                       KVStore<AetherKey, AetherValue> kv,
                                                                                       LongSupplier clock) {
        var router = MessageRouter.mutable();
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    node,
                                                    kv,
                                                    router,
                                                    stubTopologyManager(),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, OTHER),
                                                    () -> Set.of(SELF, OTHER),
                                                    Set::of,
                                                    Set.of(SELF, OTHER),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        var fsmHarness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("slice-target-ordering-" + System.nanoTime(), factory);

        fsmHarness.dispatch(new Activate());

        return fsmHarness;
    }

    /// Holds every submitted batch until the test commits it, in any order, into the real store.
    private static final class HoldingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private record Held(int seq, List<KVCommand<AetherKey>> batch, Promise<List<Object>> promise) {}

        private final KVStore<AetherKey, AetherValue> store;
        private final List<Held> held = new ArrayList<>();
        private final List<List<KVCommand<AetherKey>>> submitted = new ArrayList<>();
        private int seq;

        HoldingClusterNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        @SuppressWarnings("unchecked")
        @Override
        public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            var promise = Promise.<List<Object>> promise();

            held.add(new Held(seq++, List.copyOf(batch), promise));
            submitted.add(List.copyOf(batch));

            return (Promise<List<R>>) (Promise<?>) promise;
        }

        /// The submission number of the latest batch that writes the target key, whatever its form (put, remove, transaction).
        int lastTargetWriteSeq() {
            return IntStream.range(0, submitted.size())
                            .filter(index -> touchesTarget(submitted.get(index)))
                            .max()
                            .orElseThrow();
        }

        /// The submission number of the oldest HELD batch that writes the target key, if one is held.
        synchronized Option<Integer> heldTargetWriteSeq() {
            return held.stream()
                       .filter(entry -> touchesTarget(entry.batch()))
                       .map(Held::seq)
                       .min(Integer::compare)
                       .map(Option::some)
                       .orElseGet(Option::none);
        }

        /// The oldest HELD batch that writes the target key, waiting briefly for one: a refused write's rebuild is
        /// submitted from the continuation of the promise the commit resolved, on another thread.
        Option<Integer> awaitHeldTargetWrite() {
            try {
                Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> heldTargetWriteSeq().isPresent());
            } catch (ConditionTimeoutException _) {
                return Option.none();
            }

            return heldTargetWriteSeq();
        }

        int submittedCount() {
            return submitted.size();
        }

        List<KVCommand<AetherKey>> allSubmitted() {
            return submitted.stream().flatMap(List::stream).toList();
        }

        int targetWritesSince(int firstIndex) {
            return (int) submitted.stream().skip(firstIndex).filter(HoldingClusterNode::touchesTarget).count();
        }

        /// Submissions since `firstIndex` that write a target naming this version.
        int targetWritesNaming(org.pragmatica.aether.artifact.Version version, int firstIndex) {
            return (int) submitted.stream()
                                  .skip(firstIndex)
                                  .flatMap(List::stream)
                                  .flatMap(command -> targetValues(command).stream())
                                  .filter(value -> value.currentVersion().equals(version))
                                  .count();
        }

        int plainTargetPutsSince(int firstIndex) {
            return (int) submitted.stream()
                                  .skip(firstIndex)
                                  .flatMap(List::stream)
                                  .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put && TARGET_KEY.equals(put.key()))
                                  .count();
        }

        private static boolean touchesTarget(List<KVCommand<AetherKey>> batch) {
            return batch.stream().anyMatch(command -> TARGET_KEY.equals(command.key()) || mutatesTarget(command));
        }

        private static boolean mutatesTarget(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
                   && transaction.mutations().stream().anyMatch(mutation -> TARGET_KEY.equals(mutation.key()));
        }

        /// The target values a command writes: a plain put's, or the replacements of a transaction's target mutations.
        private static List<SliceTargetValue> targetValues(KVCommand<AetherKey> command) {
            return switch (command) {
                case KVCommand.Put<AetherKey, ?> put when TARGET_KEY.equals(put.key()) && put.value() instanceof SliceTargetValue value -> List.of(value);
                case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                          .stream()
                                                                                          .filter(mutation -> TARGET_KEY.equals(mutation.key()))
                                                                                          .flatMap(mutation -> mutation.replacement().stream())
                                                                                          .filter(SliceTargetValue.class::isInstance)
                                                                                          .map(SliceTargetValue.class::cast)
                                                                                          .toList();
                default -> List.of();
            };
        }

        /// The apply of the held batch with this submission number: the real applier, then the caller's promise resolves.
        synchronized void commit(int submissionSeq) {
            var entry = held.stream().filter(h -> h.seq() == submissionSeq).findFirst().orElseThrow();

            held.remove(entry);
            var applied = store.<Object> process(store.createBatch(entry.batch()));

            entry.promise().succeed(applied);
        }

        /// The apply of the held batch fails (consensus could not commit it): nothing is applied.
        synchronized void fail(int submissionSeq, org.pragmatica.lang.Cause cause) {
            var entry = held.stream().filter(h -> h.seq() == submissionSeq).findFirst().orElseThrow();

            held.remove(entry);
            entry.promise().resolve(cause.result());
        }

        /// A batch that never commits (the lost put of #1109): it is forgotten, its promise never resolves.
        synchronized void drop(int submissionSeq) {
            held.removeIf(h -> h.seq() == submissionSeq);
        }

        void commitAllInReverseSubmissionOrder() {
            for (var round = 0; round < 50 && !held.isEmpty(); round++) {
                commit(held.stream().mapToInt(Held::seq).max().orElseThrow());
            }
        }

        void commitAllInSubmissionOrder() {
            for (var round = 0; round < 50 && !held.isEmpty(); round++) {
                commit(held.stream().mapToInt(Held::seq).min().orElseThrow());
            }
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
    }

    /// Applies each batch at once, as a cluster whose commit order is the submission order.
    private static final class ApplyingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final KVStore<AetherKey, AetherValue> store;
        private final List<KVCommand<AetherKey>> commands = new ArrayList<>();

        ApplyingClusterNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success((List<R>) store.<Object> process(store.createBatch(batch)));
        }

        List<KVCommand<AetherKey>> loads() {
            return commands.stream().filter(SliceTargetOrderingTest::isLoad).toList();
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
    }

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> baseline(String datasourceName, int version) {return Promise.success(Unit.unit());}
        };
    }

    private static TopologyManager stubTopologyManager() {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(SELF, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));}

            @Override public int clusterSize() {return 2;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}

            @Override public List<NodeId> topology() {return List.of(SELF);}
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
