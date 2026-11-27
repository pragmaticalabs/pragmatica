// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// A worker fixture that deploys a real, loadable slice with one declared stream-consumer method (#3811, #3726 G1).
///
/// **The invariant.** A test fixture builds only states that production reaches, in the order production reaches them (rework rule 4). Two
/// halves. (1) A worker's ACTIVE `NodeArtifactKey` row is true only when the worker loaded the slice from a resolvable artifact under a committed
/// `SliceTarget`; a row committed by hand with no loadable artifact is removed by intended behaviour (`StaleEntryCleaner.cleanupOrphanedSliceEntries`
/// without a target, `handleLoadingFailure` with one), and a test that needs it survives only while it wins that race. (2) A committed
/// `SliceTarget` has its blueprint committed before it: `SliceRoutes.createWrite` refuses the first write of a target no committed blueprint
/// declares (`NOT_IN_BLUEPRINT`), so a target committed with no blueprint is a state no operator can reach.
///
/// **The enforcer.** This class commits neither a `NodeArtifactKey` nor a `SliceTargetKey`. The only placement it offers is [#deployOnWorkers], which
/// drives the operator's own path through the management API: it publishes a blueprint declaring the slice (the blueprint apply commits the
/// `SliceTarget`), awaits that target, then moves it to WORKERS_ONLY through the scale endpoint (the same fenced write `aether scale --placement` makes).
/// The load, and so the ACTIVE row, is the worker's. The slice jar sits in a staged repository every node resolves from ([#install]).
///
/// What stays hand-committed by the tests that use it is the consumer DECLARATION (`StreamRegistrationKey`, via [#commit]). Production writes it
/// from the slice's reactive manifest and the stream's config section when the node activates the slice
/// (`NodeDeploymentState.doPublishStreamSubscriptions`, value `StreamRegistrationValue(self, group, ...)`), which a minimal jar does not carry. The
/// tests commit it only after the slice is ACTIVE on every worker (`awaitActiveOnEveryWorker`), which is LATER than production (production puts it in
/// the ACTIVATING chain, before ACTIVE). The state reached is one production also reaches; the order is a marked design choice (PR #3833). It is
/// declared configuration, not instance state.
final class WorkerConsumerSlice {
    private static final String SLICE_CLASS = WorkerConsumerFactory.class.getName();
    private static final TimeSpan COMMIT_BOUND = TimeSpan.timeSpan(30).seconds();
    /// The blueprint floor (`SliceSpec.MIN_INSTANCES`): a blueprint slice below it is refused (400), and the scale endpoint refuses a count below it too.
    private static final int INSTANCES = 3;
    private static final long TARGET_BOUND_MS = 60_000L;

    private final Artifact artifact;
    private final RepositoryType.Staged repository;

    private WorkerConsumerSlice(Artifact artifact, RepositoryType.Staged repository) {
        this.artifact = artifact;
        this.repository = repository;
    }

    /// Writes the slice jar for `artifact` under `root` in Maven layout and returns the fixture over a staged repository rooted there.
    static WorkerConsumerSlice stagedAt(Path root, Artifact artifact) throws IOException {
        writeJar(jarPath(root, artifact), artifact);

        return new WorkerConsumerSlice(artifact, RepositoryType.Staged.staged(root, Set.of(artifact)));
    }

    Artifact artifact() {
        return artifact;
    }

    /// Makes every node of `cluster` resolve slices from the staged repository. Before start.
    EmberCluster install(EmberCluster cluster) {
        assertThat(cluster.withSliceRepositories(List.of(repository)).isSuccess()).as("the staged slice repository is accepted before start")
                                                                                .isTrue();

        return cluster;
    }

    /// Deploys the slice onto workers the way an operator does: publishes a blueprint declaring it (3 instances, `SliceSpec.MIN_INSTANCES`), awaits
    /// the `SliceTarget` that apply commits, then moves the target to WORKERS_ONLY through the scale endpoint. Everything after it, the placement, the load and the worker's ACTIVE row,
    /// is the system's. The apply first places the slice on a core (CORE_ONLY is a target's default), so a caller that needs the worker-only state waits
    /// for it with [#awaitHostedByWorkersOnly].
    void deployOnWorkers(EmberCluster cluster) {
        var applied = StreamTripwireSupport.post(leaderPort(cluster), "/api/v1/blueprints", "application/toml", blueprint());

        assertThat(applied.acked()).as("the blueprint declaring %s is applied: %s", artifact, applied).isTrue();
        StreamTripwireSupport.awaitUntil(() -> targetCommitted(cluster), TARGET_BOUND_MS, "the blueprint apply committed the SliceTarget of " + artifact);
        StreamTripwireSupport.awaitUntil(() -> workersOnlyAccepted(cluster), TARGET_BOUND_MS, "the scale endpoint accepted WORKERS_ONLY for " + artifact);
    }

    private String blueprint() {
        return """
               id = "%s"
               [[slices]]
               artifact = "%s"
               instances = %d
               """.formatted(artifact.asString(), artifact.asString(), INSTANCES);
    }

    private boolean targetCommitted(EmberCluster cluster) {
        return leaderNode(cluster).kvStore()
                                  .getTyped(AetherKey.SliceTargetKey.sliceTargetKey(artifact.base()), AetherValue.SliceTargetValue.class)
                                  .isPresent();
    }

    /// Idempotent: a refusal by an election or by the target fence is retried by the caller's await, an accepted move is repeated harmlessly.
    private boolean workersOnlyAccepted(EmberCluster cluster) {
        var body = "{\"artifact\":\"" + artifact.asString() + "\",\"instances\":" + INSTANCES + ",\"placement\":\"WORKERS_ONLY\"}";

        return StreamTripwireSupport.post(leaderPort(cluster), "/api/v1/scale", body).acked();
    }

    private static int leaderPort(EmberCluster cluster) {
        return cluster.getLeaderManagementPort().unwrap();
    }

    private static AetherNode leaderNode(EmberCluster cluster) {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    /// Waits until every host the leader's placement view shows for the slice (in any state) is one of `workers` and the slice is ACTIVE on every one of them
    /// (three instances over the test's three workers). The wait covers the move off the cores the blueprint apply first used, and fixes the candidate set the
    /// stream consumer manager ranks, so the assignee does not move while the test reads it. Waits up to `budgetMs`.
    void awaitActiveOnEveryWorker(Supplier<AetherNode> leader, List<NodeId> workers, long budgetMs, Supplier<String> state) {
        StreamTripwireSupport.awaitUntil(() -> hostedByWorkersOnly(leader.get(), workers) && activeOnEvery(leader.get(), workers),
                                         budgetMs,
                                         "the slice " + artifact + " is ACTIVE on every worker (loaded by each from its artifact) and hosted by workers only" + state.get());
    }

    /// True while every host the leader's placement view shows for the slice, in any state, is one of `workers`.
    boolean hostedByWorkersOnly(AetherNode leader, List<NodeId> workers) {
        return workers.containsAll(leader.deploymentMap().byArtifact(artifact).keySet());
    }

    /// The assignee of a consumer of this slice is (a) one of `workers`, (b) a node the leader's placement view shows the slice ACTIVE on, and (c) not a core.
    /// Each is asserted on its own, with the hosts named, because (a) alone would pass a worker that does not host the slice and (c) is what #2600 is about.
    void assertAssigneeIsActiveWorker(EmberCluster cluster, NodeId assignee, List<NodeId> workers) {
        var placement = leaderNode(cluster).deploymentMap().byArtifact(artifact);
        var cores = cluster.allNodes().stream().map(AetherNode::self).filter(id -> !workers.contains(id)).toList();

        assertThat(workers).as("the assignee %s is a worker (workers=%s)", assignee, workers).contains(assignee);
        assertThat(placement.get(assignee)).as("the assignee %s has %s ACTIVE in the leader's deploymentMap (placement=%s)", assignee, artifact, placement)
                                           .isEqualTo(SliceState.ACTIVE);
        assertThat(cores).as("the assignee %s is never a core (cores=%s)", assignee, cores).doesNotContain(assignee);
    }

    private boolean activeOnEvery(AetherNode leader, List<NodeId> workers) {
        var placement = leader.deploymentMap().byArtifact(artifact);

        return workers.stream().allMatch(worker -> placement.get(worker) == SliceState.ACTIVE);
    }

    /// One accepted leader transaction for `key`.
    static void commit(AetherNode leader, AetherKey key, AetherValue value) {
        var authority = leader.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(value))));
        var results = leader.<Object> apply(List.of(transaction)).await(COMMIT_BOUND).unwrap();

        assertThat(results).as("the commit of %s is accepted", key)
                           .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private static Path jarPath(Path root, Artifact artifact) throws IOException {
        var directory = root.resolve(artifact.groupId().id().replace('.', '/'))
                            .resolve(artifact.artifactId().id())
                            .resolve(artifact.version().withQualifier());

        Files.createDirectories(directory);

        return directory.resolve(artifact.artifactId().id() + "-" + artifact.version().withQualifier() + ".jar");
    }

    private static void writeJar(Path jar, Artifact artifact) throws IOException {
        var manifest = new Manifest();

        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Slice-Artifact", artifact.asString());
        manifest.getMainAttributes().putValue("Slice-Class", SLICE_CLASS);
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/dependencies/" + SLICE_CLASS));
            out.write("".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
