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
/// - the old node killed (kill -9) while the replacement is JOINING: the replacement still completes, and the dead old node
///   is never drained;
/// - the old LEADER killed mid-swap: the cluster converges to a terminal record, with N voters and a leader, never stuck;
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

    @BeforeEach
    void shortBudgets() {
        priorTimings = System.getProperty(TIMINGS_PROPERTY);
        // provisioning, joining, swapping, canary, canaryWait, draining, retiring (ms)
        System.setProperty(TIMINGS_PROPERTY, "60000,60000,90000,60000,0,90000,60000");
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

    @Test
    @Timeout(600)
    void oldNodeKilledWhileTheReplacementIsJoining_stillCompletes_andTheCorpseIsNeverDrained() {
        start(3, "rpk");
        var leader = awaitLeader();
        var victim = followerOf(leader);
        var victimId = victim.self();

        leader.nodeReplacementService().begin(victimId, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitCondition("the replacement is JOINING (or later)", () -> phaseIndex(recordOf(victimId).phase()) >= phaseIndex(NodeReplacementPhase.JOINING));
        cluster.killNode(victimId.id()).await(START_BOUND);
        awaitTerminal(victimId);

        assertThat(recordOf(victimId).phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertOldGone_newVotes(victimId);
    }

    @Test
    @Timeout(600)
    void leaderKilledMidSwap_clusterConverges_toATerminalRecord_withThreeVoters_andALeader() {
        start(3, "rpm");
        var leader = awaitLeader();
        var oldLeader = leader.self();

        leader.nodeReplacementService().begin(oldLeader, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitCondition("the replacement reached SWAPPING", () -> phaseIndex(recordOf(oldLeader).phase()) >= phaseIndex(NodeReplacementPhase.SWAPPING));
        cluster.killNode(oldLeader.id()).await(START_BOUND);
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

    // ---- scenario plumbing -------------------------------------------------------------------------------------------

    private void start(int size, String prefix) {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * size + 4,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> emberCluster(size, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, prefix),
                                                START_BOUND);
    }

    private AetherNode awaitLeader() {
        var found = new AetherNode[1];

        awaitCondition("a leader is elected", () -> {
            found[0] = cluster.currentLeader().flatMap(cluster::getNode).filter(AetherNode::isLeader).or((AetherNode) null);

            return found[0] != null;
        });

        return found[0];
    }

    private AetherNode followerOf(AetherNode leader) {
        return cluster.allNodes().stream().filter(node -> !node.self().equals(leader.self())).findFirst().orElseThrow();
    }

    /// The newest committed record for `original` any node knows: nodes apply the leader's commits at slightly different
    /// moments, and a node that is dying or dead still holds its last, older view, so the highest epoch wins.
    private NodeReplacementValue recordOf(NodeId original) {
        NodeReplacementValue newest = null;

        for (var node : new ArrayList<>(cluster.allNodes())) {
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
                for (var node : new ArrayList<>(test.cluster.allNodes())) {
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
                var leader = test.cluster.currentLeader().flatMap(test.cluster::getNode).or((AetherNode) null);

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
            sleep(300);
            var finalLeader = test.awaitLeader();
            var lost = acked.stream().filter(key -> finalLeader.kvStore().get(key).isEmpty()).map(AetherKey.LogLevelKey::loggerName).toList();

            return new Result(List.copyOf(violations), lost, acked.size(), voterSamples.get(), List.copyOf(phases));
        }
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
