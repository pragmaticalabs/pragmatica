// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3301 (the FSM2 successor of #1109's pin): a warning the FSM2 cluster leader raises, `slice-start-awaiting-target`, reaches the event
/// surface (`eventAggregator().events()`) of a NON-leader node. `AetherNodeLeaderAwaitedTargetBootTest` pins the property on the legacy
/// engine only, on one node's own sink; a lone node never elects a leader under FSM2, hence three Ember cores.
///
/// How the leader is made to raise it. The leader declares a blueprint's target put with a witness on the blueprint value it mirrors
/// (CF-5). Both blueprint values are committed in ONE batch: the deploying value, then the same blueprint `registerOnly`. The
/// leader declares at the first and builds its witness from it, the store already holds the second when the put reaches the applier, so
/// the put is refused and, with no open apply to re-drive it, only the awaited-target watch (`Blueprints.sweepAwaited`) is left to act,
/// at its 120 s bound. It then raises the warning and re-sends the put, which commits.
///
/// The batch is committed only after the leader's FSM has rebuilt and run a reconcile pass. A `Rebuild` reads the committed store as ONE
/// snapshot (`ClusterFsmInterpreter.rebuild`), and a snapshot taken after the batch holds only its final value, the `registerOnly` one, for
/// which declaring nothing is correct: the leader would be inert and the wait below would time out. Right after the election the rebuild
/// ran 89 ms before the batch's first put reached the FSM on a quiet host (t3301c, 2026-11-18), so the order was a race, lost under load.
/// The gate is a fixture need, not a product workaround: a real publish commits an apply record with the blueprint, and the first
/// reconcile after a `Rebuild` re-declares such a blueprint (`Blueprints.redrive`); this batch has no apply record and ends
/// `registerOnly`, so only a live put can declare it (t3301d, 2026-11-18: blueprint committed before the rebuild, target committed with
/// an apply record, never without one).
///
/// The assertion is on code AND subject AND origin, so no other producer satisfies it: the node-side FSM raises the same code with the
/// subject `<artifact>@<node>` (Planner), and the origin of this event must be the leader.
@PortBudget
class EmberLeaderAwaitedTargetFsm2Test {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES + 1;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long SUBMIT_BOUND_MS = 30_000L;
    private static final long PASS_BOUND_MS = 60_000L;
    private static final String SUBMISSIONS = "aether_deployment_fsm_submissions_total";
    private static final String RECONCILE_PASSES = "aether_deployment_fsm_reconcile_passes_total";
    private static final String AWAITING_TARGET = "slice-start-awaiting-target";
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:awaited-app:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:awaited-slice:1.0.0").unwrap();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void leaderRaisedAwaitingTarget_reachesANonLeadersEventSurface_underFsm2() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberLeaderAwaitedTargetFsm2Test::fsm2Cluster, START_BOUND);
        awaitCondition(() -> "a leader", 90_000L, () -> cluster.currentLeader().isPresent());
        var leaderId = cluster.currentLeader().or("");
        var leader = cluster.getNode(leaderId).unwrap();
        var follower = cluster.allNodes().stream().filter(node -> !node.self().id().equals(leaderId)).findFirst().orElseThrow();

        assertThat(count(follower, leaderId)).as("control: nothing raised yet").isZero();

        awaitCondition(() -> "the leader FSM never completed a reconcile pass (rebuilt and not suspended); leader " + leaderId,
                       PASS_BOUND_MS,
                       () -> leaderReconcilePasses(leader) >= 1.0);
        commitDeployingThenRegisterOnly(leader);

        awaitCondition(() -> "control: the leader FSM never submitted a target put (kind=TARGETS, outcome=submitted), so it never declared the blueprint: it is inert (suspended, or rebuilt from a snapshot that already held the registerOnly value); leader " + leaderId,
                       SUBMIT_BOUND_MS,
                       () -> targetArm(leader, "submitted") >= 1.0);
        awaitCondition(() -> "control: the leader's target put was never refused (outcome=refused*), so the put committed or was lost; leader " + leaderId,
                       SUBMIT_BOUND_MS,
                       () -> refusedTargets(leader) >= 1.0);
        assertThat(leader.kvStore().get(SliceTargetKey.sliceTargetKey(SLICE.base())).isPresent())
            .as("control: the first target put was refused, so the leader is awaiting a committed target")
            .isFalse();
        awaitCondition(() -> "the leader's awaiting-target on " + follower.self().id() + "; events: " + codesSeenBy(follower),
                       300_000L,
                       () -> count(follower, leaderId) >= 1);
        assertThat(follower.self().id()).as("the surface read is a non-leader's").isNotEqualTo(leaderId);
    }

    private static EmberCluster fsm2Cluster(int basePort) {
        var created = emberCluster(CORES, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "t3301");

        created.withDeploymentEngine(DeploymentEngine.FSM2);
        created.withReconciliationIntervalForNewNodes(TimeSpan.timeSpan(5).seconds());

        return created;
    }

    private static void commitDeployingThenRegisterOnly(AetherNode leader) {
        var slice = ResolvedSlice.resolvedSlice(SLICE, 1, 1, false, Set.of()).unwrap();
        var expanded = ExpandedBlueprint.expandedBlueprint(OWNER, List.of(slice), Option.none());
        var key = AppBlueprintKey.appBlueprintKey(OWNER);
        List<KVCommand<AetherKey>> batch = List.of(new KVCommand.Put<AetherKey, AetherValue>(key, AppBlueprintValue.appBlueprintValue(expanded)),
                                                   new KVCommand.Put<AetherKey, AetherValue>(key, AppBlueprintValue.appBlueprintValue(expanded, true)));

        leader.<Object>apply(batch).await(APPLY_BOUND).onFailure(cause -> throwAssertion("the blueprint batch must commit: " + cause.message()));
    }

    private static void throwAssertion(String message) {
        throw new AssertionError(message);
    }

    /// The leader FSM's target-batch submissions of one outcome arm: the leader's own count of what it sent, read from its meters.
    private static double targetArm(AetherNode leader, String outcome) {
        return counterSum(leader, SUBMISSIONS, "fsm", "cluster", "kind", "TARGETS", "outcome", outcome);
    }

    /// Reconcile passes the leader's cluster FSM completed: counted only past `Rebuild` and while not suspended (`Reconcile.run`).
    private static double leaderReconcilePasses(AetherNode leader) {
        return counterSum(leader, RECONCILE_PASSES, "fsm", "cluster");
    }

    private static double counterSum(AetherNode node, String name, String... tags) {
        return node.deploymentWiring()
                   .meters()
                   .find(name)
                   .tags(tags)
                   .counters()
                   .stream()
                   .mapToDouble(counter -> counter.count())
                   .sum();
    }

    /// The target batches the applier refused (any `refused_*` arm: version, mirror lag or witness).
    private static double refusedTargets(AetherNode leader) {
        return leader.deploymentWiring()
                     .meters()
                     .find(SUBMISSIONS)
                     .tags("fsm", "cluster", "kind", "TARGETS")
                     .counters()
                     .stream()
                     .filter(counter -> counter.getId().getTag("outcome").startsWith("refused"))
                     .mapToDouble(counter -> counter.count())
                     .sum();
    }

    /// The warnings of the awaited-target code, for this artifact exactly, that the LEADER originated.
    private static long count(AetherNode reader, String leaderId) {
        return events(reader).stream()
                             .filter(e -> e instanceof ClusterEvent.OperatorWarning)
                             .filter(e -> AWAITING_TARGET.equals(e.details().get("code")))
                             .filter(e -> SLICE.asString().equals(e.details().get("subject")))
                             .filter(e -> leaderId.equals(e.sourceNode().id()))
                             .count();
    }

    private static List<String> codesSeenBy(AetherNode node) {
        return events(node).stream()
                           .map(e -> e.getClass().getSimpleName() + "/" + e.details().get("code") + "/" + e.details().get("subject"))
                           .toList();
    }

    private static List<ClusterEvent> events(AetherNode node) {
        return node.eventAggregator().events().await().or(List.of());
    }

    private static void awaitCondition(Supplier<String> what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(500L);
        }
        throw new AssertionError("timed out: " + what.get());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
