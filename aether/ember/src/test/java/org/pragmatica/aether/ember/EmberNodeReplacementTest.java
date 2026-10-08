// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1543 part E on REAL in-JVM clusters: a core is replaced by a fresh-id node through the whole phase machine, while the
/// installed electorate is sampled on EVERY node and a writer keeps committing to the KV.
///
/// What each scenario pins:
/// - follower and LEADER replacement at 3 cores: the installed voter set has exactly N members on every sample (a one-out-
///   one-in swap, never a shrink), the old node ends gone and the new one votes, a successor leader is elected when the
///   old one was the leader, and every ACKED write is in the final KV (a refused write is retryable, never lost);
/// - the old node crashed SILENTLY (`blackhole`: the process stops answering, no SWIM leave; `killNode` is a graceful stop and is
///   not used for crashes) while the replacement is JOINING: the replacement still completes, and the dead old node
///   is never drained;
/// - the old LEADER crashed (blackhole) mid-swap: the cluster converges to a terminal record, with N voters and a leader, never stuck;
/// - a replacement that never boots: the record is ROLLED_BACK at its join deadline and the old node is untouched.
/// The phase budgets are shortened by the `aether.replacement.timings.ms` property so the never-joins case does not wait
/// ten minutes.
@PortBudget
class EmberNodeReplacementTest {
    private static final int SLOTS_3 = 2 * 3 + 4;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS_3 = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                  EmberTestPorts.POOL_LAST,
                                                                                  EmberTestPorts.POOL_STEP,
                                                                                  SLOTS_3,
                                                                                  MGMT_OFFSET,
                                                                                  APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long DONE_BOUND_MS = 240_000L;
    private static final String TIMINGS_PROPERTY = NodeReplacementPlanner.Timings.OVERRIDE_PROPERTY;

    private EmberCluster cluster;
    private String priorTimings;
    private final Set<String> blackholed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void shortBudgets() {
        priorTimings = System.getProperty(TIMINGS_PROPERTY);
        // provisioning, joining, swapping, canary, canaryWait, draining, retiring (ms)
        System.setProperty(TIMINGS_PROPERTY, "60000,60000,90000,60000,3000,90000,60000");
    }

    @AfterEach
    void tearDown() {
        if (priorTimings == null) {
            System.clearProperty(TIMINGS_PROPERTY);
        } else {
            System.setProperty(TIMINGS_PROPERTY, priorTimings);
        }

        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void replaceAFollower_atThreeCores_keepsThreeVotersOnEverySample_andLosesNoAckedWrite() {
        replaceAndVerify(3, false, "rpf");
    }

    @Test
    @Timeout(600)
    void replaceTheLeader_atThreeCores_swapsItsSeat_electsASuccessor_andLosesNoAckedWrite() {
        replaceAndVerify(3, true, "rpl");
    }

    @Test
    @Timeout(900)
    void replaceAFollower_atFiveCores_keepsFiveVotersOnEverySample_andLosesNoAckedWrite() {
        replaceAndVerify(5, false, "rpf5");
    }

    @Test
    @Timeout(900)
    void replaceTheLeader_atFiveCores_swapsItsSeat_electsASuccessor_andLosesNoAckedWrite() {
        replaceAndVerify(5, true, "rpl5");
    }

    /// Replace one core (the leader or a follower) while sampling every voting node's installed electorate and committing
    /// writes; then check the outcome. `size` is the electorate size N that must hold on EVERY sample.
    private void replaceAndVerify(int size, boolean replaceLeader, String prefix) {
        start(size, prefix);
        var leader = awaitLeader();
        var victim = replaceLeader ? leader : followerOf(leader);
        var victimId = victim.self();
        var watch = Watch.begin(this, size).watching(victimId);

        leader.nodeReplacementService().begin(victimId, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitTerminal(victimId);
        var result = watch.finish();

        assertThat(recordOf(victimId).phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(result.voterViolations()).as("installed voters == %d on every sample", size).isEmpty();
        assertThat(result.voterSamples()).as("the sampler really looked: voting-node samples").isGreaterThan(100);
        assertThat(result.phases()).as("the phases walked").contains(NodeReplacementPhase.SWAPPING, NodeReplacementPhase.CANARY, NodeReplacementPhase.DONE);
        assertThat(result.lostAckedWrites()).as("acked writes missing from the final KV").isEmpty();
        assertThat(result.acked()).as("the writer made progress").isGreaterThan(5);
        assertOldGone_newVotes(victimId, size);

        if (replaceLeader) {
            assertThat(awaitLeader().self()).as("a successor was elected").isNotEqualTo(victimId);
        }
    }

    /// The tripwire's phase budgets: the same as every other scenario except DRAINING_OLD, 30 s instead of 90 s. Today a blackholed,
    /// never-yet-healthy old node is declared dead by the leader only when the peer-side liveness sweep (pingInterval x 8, 80 s in
    /// Ember) or the node's own quorum-loss self-drain (~66 s) closes its open channel, so the leader's view of it flips ~67-80 s
    /// after the crash. With a 90 s DRAINING_OLD budget (entered ~13 s after the crash) that race could go either way, which made the
    /// tripwire fire on a CI run where Dead arrived first (phase DONE). 30 s ends the budget ~43 s after the crash: well before any
    /// observed Dead edge, so today's outcome is deterministically FAILED_KEPT_BOTH, yet long enough that a real #2021 fix (detection
    /// in seconds) lets the replacement complete and trips this test.
    private static final String TRIPWIRE_TIMINGS = "60000,60000,90000,60000,3000,30000,60000";
    private static final String TRIPWIRE_NAME = "tripwire2021_oldNodeCrashedAtColdBoot_endsKeptBoth_withoutRequestingTheDrain";
    private static final String REAL_TEST_NAME = "oldNodeKilledWhileTheReplacementIsJoining_stillCompletes_andTheCorpseIsNeverDrained";

    /// The assertion that is meant to hold: an old core that crashes abruptly during JOINING right after cluster start ends DONE.
    /// It cannot hold yet: SWIM never saw the node healthy, so it reports UNKNOWN and the live-transport veto keeps the leader from
    /// seeing it Dead inside the draining budget (#2021). Disabled until #2021 lands; [#TRIPWIRE_NAME] guards it meanwhile.
    @Test
    @Disabled("enable when #2021 lands; tripwire " + TRIPWIRE_NAME + " guards this")
    @Timeout(600)
    void oldNodeKilledWhileTheReplacementIsJoining_stillCompletes_andTheCorpseIsNeverDrained() {
        var victimId = crashOldNodeWhileTheReplacementIsJoining("rpk", 0L);

        assertThat(recordOf(victimId).phase()).as("reason: %s", recordOf(victimId).reason()).isEqualTo(NodeReplacementPhase.DONE);
        assertOldGone_newVotes(victimId);
    }

    /// Today's outcome of the same crash, pinned: the drain is never requested (the leader still reads the old node as alive) and
    /// the record ends FAILED_KEPT_BOTH, the safe terminal state. ENABLED so that the day #2021 changes the outcome this goes red
    /// and says what to do; a disabled test would stay silent.
    @Test
    @Timeout(600)
    void tripwire2021_oldNodeCrashedAtColdBoot_endsKeptBoth_withoutRequestingTheDrain() {
        System.setProperty(TIMINGS_PROPERTY, TRIPWIRE_TIMINGS);
        var victimId = crashOldNodeWhileTheReplacementIsJoining("rpk", 0L);
        var record = recordOf(victimId);
        var landed = "#2021 landed: delete this tripwire and enable " + REAL_TEST_NAME + " (phase was " + record.phase() + ", reason: " + record.reason() + ")";

        assertThat(record.phase()).as(landed).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(record.reason()).as(landed).startsWith("drain did not complete").contains("oldAlive=true").contains("drain=NOT_REQUESTED");
    }

    /// The same crash on a cluster that has run long enough for SWIM to have seen every member healthy. (SWIM does not declare a
    /// member dead that it never saw healthy until its cold-boot suppression ends, so the crash in the case above is detected late;
    /// this case separates that from the replacement's own behaviour.)
    @Test
    @Timeout(600)
    void oldNodeCrashedWhileJoining_onASettledCluster_stillCompletes_andTheCorpseIsNeverDrained() {
        var victimId = crashOldNodeWhileTheReplacementIsJoining("rpz", 30_000L);

        assertThat(recordOf(victimId).phase()).as("reason: %s", recordOf(victimId).reason()).isEqualTo(NodeReplacementPhase.DONE);
        assertOldGone_newVotes(victimId);
    }

    /// Starts a replacement of a follower, crashes (blackholes) that follower while the record is in JOINING, waits for a terminal
    /// phase and returns the follower's id.
    private NodeId crashOldNodeWhileTheReplacementIsJoining(String prefix, long settleMs) {
        start(3, prefix);
        var leader = awaitLeader();
        var victim = followerOf(leader);
        var victimId = victim.self();

        sleep(settleMs);
        leader.nodeReplacementService().begin(victimId, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitCondition("the replacement is JOINING", () -> recordOf(victimId).phase() == NodeReplacementPhase.JOINING);
        // The crash must land while the record is in JOINING: sample the phase at the moment of the crash, print it, and fail
        // the test if the window was missed, so a green result can never come from a crash after JOINING finished.
        var phaseAtKill = recordOf(victimId).phase();

        System.out.println("EMBER-REPLACEMENT kill of " + victimId.id() + " at record phase " + phaseAtKill + " (settle " + settleMs + " ms)");
        assertThat(phaseAtKill).as("the kill window (JOINING) was missed").isEqualTo(NodeReplacementPhase.JOINING);
        blackhole(victimId);
        System.out.println("EMBER-REPLACEMENT kill landed; record phase now " + recordOf(victimId).phase());
        awaitTerminal(victimId);

        return victimId;
    }

    @Test
    @Timeout(600)
    void leaderKilledMidSwap_clusterConverges_toATerminalRecord_withThreeVoters_andALeader() {
        start(3, "rpm");
        var leader = awaitLeader();
        var oldLeader = leader.self();

        leader.nodeReplacementService().begin(oldLeader, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitCondition("the replacement reached SWAPPING", () -> phaseIndex(recordOf(oldLeader).phase()) >= phaseIndex(NodeReplacementPhase.SWAPPING));
        blackhole(oldLeader);
        awaitTerminal(oldLeader);

        assertThat(recordOf(oldLeader).phase()).as("a terminal outcome, never stuck").isIn(NodeReplacementPhase.DONE,
                                                                                         NodeReplacementPhase.ROLLED_BACK,
                                                                                         NodeReplacementPhase.FAILED_KEPT_BOTH);
        var survivor = awaitLeader();

        awaitCondition("three voters again", () -> installedVoters(survivor).size() == 3);
    }

    @Test
    @Timeout(600)
    void aReplacementThatNeverBoots_isRolledBackAtItsJoinDeadline_andTheOldNodeIsUntouched() {
        cluster = EmberTestPorts.startedCluster(PORTS_3,
                                                basePort -> {
                                                    var built = emberCluster(3, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "rpn");

                                                    built.withComputeProviderDecorator(NeverBootsProvider::new);

                                                    return built;
                                                },
                                                START_BOUND);
        var leader = awaitLeader();
        var victim = followerOf(leader);

        leader.nodeReplacementService().begin(victim.self(), "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitTerminal(victim.self());

        assertThat(recordOf(victim.self()).phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(installedVoters(leader)).as("the old node still votes").contains(victim.self()).hasSize(3);
        assertThat(cluster.getNode(victim.self().id()).isPresent()).as("the old node is still running").isTrue();
    }

    /// #1543 E2: a WORKER is replaced with no voter swap at all. The electorate is sampled on every node and must stay the
    /// same three cores throughout, the phases never include SWAPPING, the old worker is retired and the replacement is a
    /// worker.
    @Test
    @Timeout(600)
    void replaceAWorker_swapsNoVoter_retiresTheOldWorker_andTheReplacementIsAWorker() {
        start(3, "rpw");
        var leader = awaitLeader();
        var worker = cluster.addWorkerNode().await(START_BOUND).unwrap();

        awaitCondition("the worker is ready", () -> cluster.getNode(worker.id()).filter(AetherNode::isReady).isPresent());
        var cores = installedVoters(leader);
        var watch = Watch.begin(this, 3).watching(worker);

        leader.nodeReplacementService().begin(worker, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitTerminal(worker);
        var result = watch.finish();
        var record = recordOf(worker);

        assertThat(record.phase()).as("reason: %s", record.reason()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(record.role()).isEqualTo("worker");
        assertThat(result.phases()).as("a worker never swaps a seat").doesNotContain(NodeReplacementPhase.SWAPPING);
        assertThat(result.phases()).contains(NodeReplacementPhase.CANARY, NodeReplacementPhase.DONE);
        assertThat(result.voterViolations()).as("the electorate is untouched on every sample").isEmpty();
        assertThat(installedVoters(awaitLeader())).as("the same cores vote").isEqualTo(cores);
        awaitCondition("the old worker is gone", () -> cluster.getNode(worker.id()).isEmpty());
        assertThat(cluster.getNode(record.replacement().id()).isPresent()).as("the replacement runs").isTrue();
        assertThat(installedVoters(awaitLeader())).as("the replacement is a worker, not a voter").doesNotContain(record.replacement());
        assertThat(awaitLeader().membershipFsm().memberDescriptor(record.replacement()).map(descriptor -> descriptor.role()).or("none"))
            .as("the replacement advertises the worker role").isEqualTo("worker");
    }

    /// #1543 E2, EXTERNAL mode: the operator names a fresh id and starts that core itself. The leader provisions nothing;
    /// the same phase machine swaps its seat in. An id that is already a member is refused.
    @Test
    @Timeout(600)
    void externalReplacement_operatorStartsTheChosenCore_andThePhasesCompleteWithoutTheLeaderProvisioning() {
        start(3, "rpx");
        var leader = awaitLeader();
        var victim = followerOf(leader).self();
        var chosen = NodeId.nodeId("rpx-ext-9").unwrap();
        var refusal = leader.nodeReplacementService().beginExternal(victim, leader.self(), "").await(START_BOUND);

        assertThat(refusal.isFailure()).as("a member id is not a fresh id").isTrue();
        var watch = Watch.begin(this, 3).watching(victim);
        var begun = leader.nodeReplacementService().beginExternal(victim, chosen, "").await(START_BOUND);

        begun.onFailure(cause -> throwBecause(cause.message()));
        assertThat(begun.unwrap().mode()).isEqualTo(NodeReplacementValue.MODE_EXTERNAL);
        sleep(2_000);
        assertThat(cluster.getNode(chosen.id()).isEmpty()).as("the leader did not start the node itself").isTrue();
        cluster.addCoreNode(chosen.id()).await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitTerminal(victim);
        var result = watch.finish();

        assertThat(recordOf(victim).phase()).as("reason: %s", recordOf(victim).reason()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(recordOf(victim).replacement()).isEqualTo(chosen);
        assertThat(result.voterViolations()).as("installed voters == 3 on every sample").isEmpty();
        assertOldGone_newVotes(victim, 3);
    }

    // ---- v-2008 round: events, canary death, slow provider ------------------------------------------------------------

    /// B3: the operator events of a replacement follow the COMMITTED transition and are raised by the cluster-events owner, so the
    /// recovery of "started" is published whichever node is leader when the replacement completes. A replaced LEADER is the case
    /// where the leader that raised "started" is gone by the time "completed" commits.
    @Test
    @Timeout(600)
    void followerReplacement_publishesStartedAndCompleted_control() {
        eventsScenario(false, "evf");
    }

    @Test
    @Timeout(600)
    void leaderReplacement_publishesStartedAndCompleted_acrossTheLeaderChange() {
        eventsScenario(true, "evl");
    }

    private void eventsScenario(boolean replaceLeader, String prefix) {
        start(3, prefix);
        var leader = awaitLeader();
        var victim = replaceLeader ? leader.self() : followerOf(leader).self();

        leader.nodeReplacementService().begin(victim, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitTerminal(victim);
        assertThat(recordOf(victim).phase()).isEqualTo(NodeReplacementPhase.DONE);
        awaitCondition("the replacement's events reach the cluster-events stream", 60_000L, () -> replacementCodes(victim).contains("node-replacement-completed"));
        assertThat(replacementCodes(victim)).as("opened and closed, once each").containsExactly("node-replacement-started", "node-replacement-completed");
    }

    private List<String> replacementCodes(NodeId subject) {
        return awaitLeader().eventAggregator()
                            .events()
                            .await(TimeSpan.timeSpan(30).seconds())
                            .or(List.of())
                            .stream()
                            .filter(org.pragmatica.aether.api.ClusterEvent.OperatorWarning.class::isInstance)
                            .map(org.pragmatica.aether.api.ClusterEvent.OperatorWarning.class::cast)
                            .filter(event -> subject.id().equals(event.details().get("subject")))
                            .map(event -> event.details().get("code"))
                            .toList();
    }

    /// B5: the replacement crashes silently right after the swap. The canary waits long enough for SWIM to declare the death, the
    /// swap is reverted, and the healthy original survives and votes again.
    @Test
    @Timeout(900)
    void replacementThatCrashesInTheCanary_isRevertedAndTheOriginalSurvives() {
        System.setProperty(TIMINGS_PROPERTY, "60000,60000,90000,150000,60000,90000,60000");
        start(3, "rpy");
        var leader = awaitLeader();
        var victim = followerOf(leader).self();
        var watch = Watch.begin(this, 3).watching(victim);

        leader.nodeReplacementService().begin(victim, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitCondition("the swap is done and the canary runs", 180_000L, () -> phaseIndex(recordOf(victim).phase()) >= phaseIndex(NodeReplacementPhase.CANARY));
        var replacement = recordOf(victim).replacement();

        blackhole(replacement);
        awaitTerminal(victim);
        var result = watch.finish();

        assertThat(recordOf(victim).phase()).as("reason: %s", recordOf(victim).reason()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(result.phases()).as("the original was never drained").doesNotContain(NodeReplacementPhase.DRAINING_OLD, NodeReplacementPhase.RETIRING_OLD);
        assertThat(result.lostAckedWrites()).isEmpty();
        awaitCondition("the original votes again and the dead replacement does not",
                       120_000L,
                       () -> installedVoters(awaitLeader()).contains(victim) && !installedVoters(awaitLeader()).contains(replacement));
        assertThat(cluster.getNode(victim.id()).isPresent()).as("the original is running").isTrue();
    }

    /// B4: a provider that takes 40 s to create the node. The reconciler must not run the provision again while the first is
    /// pending, must not roll back, and the replacement completes with exactly one create.
    @Test
    @Timeout(900)
    void slowProvider_isCalledOnce_andTheReplacementStillCompletes() {
        var creates = new AtomicInteger();

        start(3, "rps", delegate -> new SlowCreateProvider(delegate, creates));
        var leader = awaitLeader();
        var victim = followerOf(leader).self();

        leader.nodeReplacementService().begin(victim, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitTerminal(victim);

        assertThat(recordOf(victim).phase()).as("reason: %s", recordOf(victim).reason()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(creates.get()).as("one create for one replacement").isEqualTo(1);
        assertOldGone_newVotes(victim, 3);
    }

    /// The instrument can fail: a sampler that never reports a violation proves nothing. Here the electorate is shrunk ON
    /// PURPOSE (a plain reconfiguration to two members, no replacement involved) while the sampler expects three, and the
    /// sampler must report it.
    @Test
    @Timeout(300)
    void controlTheVoterSamplerReportsADeliberatelyShrunkElectorate() {
        start(3, "rpc");
        var leader = awaitLeader();
        var watch = Watch.begin(this, 3);
        var kept = new ArrayList<>(installedVoters(leader));

        kept.remove(followerOf(leader).self());
        runtime(leader).reconfigure(new org.pragmatica.consensus.rabia.ClusterConfig(kept)).await(START_BOUND);
        awaitCondition("the electorate shrank to two", () -> installedVoters(leader).size() == 2);
        sleep(500);
        var result = watch.finish();

        assertThat(result.voterViolations()).as("the sampler saw the shrink").isNotEmpty();
        assertThat(result.voterViolations().getFirst()).contains("2 voters");
    }

    // ---- scenario plumbing -------------------------------------------------------------------------------------------

    private void start(int size, String prefix) {
        start(size, prefix, null);
    }

    private void start(int size,
                       String prefix,
                       java.util.function.Function<org.pragmatica.aether.environment.ComputeProvider, org.pragmatica.aether.environment.ComputeProvider> decorator) {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * size + 4,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> {
                                                    var built = emberCluster(size, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, prefix);

                                                    if (decorator != null) {
                                                        built.withComputeProviderDecorator(decorator::apply);
                                                    }

                                                    return built;
                                                },
                                                START_BOUND);
    }

    /// Abrupt death: the node stops answering, with no SWIM leave. (`killNode` is `stop()`, a graceful departure.)
    private void blackhole(NodeId id) {
        blackholed.add(id.id());
        cluster.blackhole(id.id()).await(START_BOUND);
    }

    private List<AetherNode> live() {
        return cluster.allNodes().stream().filter(node -> !blackholed.contains(node.self().id())).toList();
    }

    private AetherNode awaitLeader() {
        var found = new AetherNode[1];

        awaitCondition("a leader is elected", () -> {
            found[0] = live().stream().filter(AetherNode::isLeader).findFirst().orElse(null);

            return found[0] != null;
        });

        return found[0];
    }

    private AetherNode followerOf(AetherNode leader) {
        return live().stream().filter(node -> !node.self().equals(leader.self())).findFirst().orElseThrow();
    }

    /// The newest committed record for `original` any node knows: nodes apply the leader's commits at slightly different
    /// moments, and a node that is dying or dead still holds its last, older view, so the highest epoch wins.
    private NodeReplacementValue recordOf(NodeId original) {
        NodeReplacementValue newest = null;

        for (var node : new ArrayList<>(live())) {
            var found = node.nodeReplacementService().status(original).or((NodeReplacementValue) null);

            if (found != null && (newest == null || found.epoch() > newest.epoch())) {
                newest = found;
            }
        }

        return newest;
    }

    private void awaitTerminal(NodeId original) {
        awaitCondition("the replacement of " + original.id() + " reaches a terminal phase",
                       DONE_BOUND_MS,
                       () -> Option.option(recordOf(original)).filter(record -> isTerminal(record.phase())).isPresent());
    }

    private static boolean isTerminal(NodeReplacementPhase phase) {
        return phase == NodeReplacementPhase.DONE || phase == NodeReplacementPhase.ROLLED_BACK || phase == NodeReplacementPhase.FAILED_KEPT_BOTH;
    }

    private static int phaseIndex(NodeReplacementPhase phase) {
        return phase.ordinal();
    }

    private void assertOldGone_newVotes(NodeId old) {
        assertOldGone_newVotes(old, 3);
    }

    private void assertOldGone_newVotes(NodeId old, int size) {
        var survivor = awaitLeader();
        var replacement = recordOf(old).replacement();

        awaitCondition("the replacement votes and the old node does not", () -> installedVoters(survivor).contains(replacement) && !installedVoters(survivor).contains(old));
        assertThat(installedVoters(survivor)).hasSize(size);
        awaitCondition("the old node is gone from the cluster", () -> cluster.getNode(old.id()).isEmpty());
    }

    static Set<NodeId> installedVoters(AetherNode node) {
        return runtime(node).voterConfiguration().map(configuration -> Set.copyOf(configuration.members())).or(Set.of());
    }

    static RabiaNode<?> runtime(AetherNode node) {
        return Result.lift(() -> {
            var accessor = node.getClass().getDeclaredMethod("clusterNode");

            accessor.setAccessible(true);

            return (RabiaNode<?>) accessor.invoke(node);
        }).unwrap();
    }

    private static void throwBecause(String message) {
        throw new AssertionError(message);
    }

    private void awaitCondition(String what, BooleanSupplier condition) {
        awaitCondition(what, 120_000L, condition);
    }

    private void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Samples the installed electorate of every voting node and keeps a writer committing, for the life of a replacement.
    private static final class Watch {
        record Result(List<String> voterViolations, List<String> lostAckedWrites, int acked, int voterSamples, List<NodeReplacementPhase> phases) {}

        private final EmberNodeReplacementTest test;
        private final int expectedVoters;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<String> violations = new CopyOnWriteArrayList<>();
        private final List<AetherKey.LogLevelKey> acked = new CopyOnWriteArrayList<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger voterSamples = new AtomicInteger();
        private final List<NodeReplacementPhase> phases = new CopyOnWriteArrayList<>();
        private volatile NodeId watched;
        private Thread sampler;
        private Thread writer;

        private Watch(EmberNodeReplacementTest test, int expectedVoters) {
            this.test = test;
            this.expectedVoters = expectedVoters;
        }

        static Watch begin(EmberNodeReplacementTest test, int expectedVoters) {
            var watch = new Watch(test, expectedVoters);

            watch.sampler = Thread.ofPlatform().daemon().start(watch::sample);
            watch.writer = Thread.ofPlatform().daemon().start(watch::write);

            return watch;
        }

        private void sample() {
            while (running.get()) {
                for (var node : new ArrayList<>(test.live())) {
                    var voters = installedVoters(node);

                    if (voters.contains(node.self())) {
                        voterSamples.incrementAndGet();
                    }

                    if (voters.contains(node.self()) && voters.size() != expectedVoters) {
                        violations.add(node.self().id() + " installed " + voters.size() + " voters: " + voters);
                    }
                }
                Option.option(watched).onPresent(this::notePhase);
                sleep(50);
            }
        }

        private void joinSampler() {
            try {
                sampler.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void notePhase(NodeId original) {
            Option.option(test.recordOf(original))
                  .map(NodeReplacementValue::phase)
                  .filter(phase -> phases.isEmpty() || phases.getLast() != phase)
                  .onPresent(phases::add);
        }

        Watch watching(NodeId original) {
            watched = original;

            return this;
        }

        private void write() {
            while (running.get()) {
                var i = sequence.incrementAndGet();
                var key = new AetherKey.LogLevelKey("e1.write." + i);
                var value = new AetherValue.LogLevelValue("e1.write." + i, "INFO", System.currentTimeMillis());
                var leader = test.live().stream().filter(AetherNode::isLeader).findFirst().orElse(null);

                if (leader != null) {
                    var outcome = leader.<Object> apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))).await(TimeSpan.timeSpan(5).seconds());

                    if (outcome.isSuccess()) {
                        acked.add(key);
                    }
                }
                sleep(100);
            }
        }

        Result finish() {
            running.set(false);
            joinSampler();
            // The sampler may have stopped between the terminal commit and its next look: note the phase once more, so the
            // phases reported are the phases the cluster reached.
            Option.option(watched).onPresent(this::notePhase);
            sleep(300);
            var finalLeader = test.awaitLeader();
            var lost = acked.stream().filter(key -> finalLeader.kvStore().get(key).isEmpty()).map(AetherKey.LogLevelKey::loggerName).toList();

            return new Result(List.copyOf(violations), lost, acked.size(), voterSamples.get(), List.copyOf(phases));
        }
    }

    /// Delays every create by 40 s (a cloud VM boot) and counts the calls.
    private static final class SlowCreateProvider implements org.pragmatica.aether.environment.ComputeProvider {
        private final org.pragmatica.aether.environment.ComputeProvider delegate;
        private final AtomicInteger creates;

        SlowCreateProvider(org.pragmatica.aether.environment.ComputeProvider delegate, AtomicInteger creates) {
            this.delegate = delegate;
            this.creates = creates;
        }

        @Override public org.pragmatica.aether.environment.ProviderDefaults providerDefaults() {return delegate.providerDefaults();}

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(org.pragmatica.aether.environment.ProvisionRequest request) {
            creates.incrementAndGet();

            return org.pragmatica.lang.Promise.<org.pragmatica.lang.Unit> promise(TimeSpan.timeSpan(40).seconds(), p -> p.succeed(org.pragmatica.lang.Unit.unit()))
                                              .flatMap(_ -> delegate.createFrom(request));
        }

        @Override public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> terminate(org.pragmatica.aether.environment.InstanceId instanceId) {return delegate.terminate(instanceId);}
        @Override public org.pragmatica.lang.Promise<List<org.pragmatica.aether.environment.InstanceInfo>> listInstances() {return delegate.listInstances();}
        @Override public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> instanceStatus(org.pragmatica.aether.environment.InstanceId instanceId) {return delegate.instanceStatus(instanceId);}
    }

    /// A provider that reports a provision as successful but never boots a node: the replacement never joins.
    private static final class NeverBootsProvider implements org.pragmatica.aether.environment.ComputeProvider {
        private final org.pragmatica.aether.environment.ComputeProvider delegate;

        NeverBootsProvider(org.pragmatica.aether.environment.ComputeProvider delegate) {
            this.delegate = delegate;
        }

        @Override public org.pragmatica.aether.environment.ProviderDefaults providerDefaults() {return delegate.providerDefaults();}

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(org.pragmatica.aether.environment.ProvisionRequest request) {
            return org.pragmatica.lang.Promise.<org.pragmatica.aether.environment.InstanceInfo> success(null);
        }

        @Override public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> terminate(org.pragmatica.aether.environment.InstanceId instanceId) {return delegate.terminate(instanceId);}
        @Override public org.pragmatica.lang.Promise<List<org.pragmatica.aether.environment.InstanceInfo>> listInstances() {return delegate.listInstances();}
        @Override public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> instanceStatus(org.pragmatica.aether.environment.InstanceId instanceId) {return delegate.instanceStatus(instanceId);}
    }
}
