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
/// **The invariant.** A test fixture builds only states that production reaches (rework rule 4). A worker's ACTIVE `NodeArtifactKey` row is true
/// only when the worker loaded the slice from a resolvable artifact under a committed `SliceTarget`; a row committed by hand with no loadable
/// artifact is removed by intended behaviour (`StaleEntryCleaner.cleanupOrphanedSliceEntries` without a target, `handleLoadingFailure` with one),
/// and a test that needs it survives only while it wins that race.
///
/// **The enforcer.** This class has no way to commit a `NodeArtifactKey`. The only placement it offers is [#deployOnWorkers], which commits the
/// `SliceTarget` (WORKERS_ONLY, one instance, the row a blueprint apply writes) and leaves the load, and so the ACTIVE row, to the worker. The
/// slice jar sits in a staged repository every node resolves from ([#install]).
///
/// What stays hand-committed by the tests that use it is the consumer DECLARATION (`StreamRegistrationKey`): production derives it from the
/// slice's reactive manifest and the stream's config section, which a minimal jar does not carry. It is declared configuration, not instance state.
final class WorkerConsumerSlice {
    private static final String SLICE_CLASS = WorkerConsumerFactory.class.getName();
    private static final TimeSpan COMMIT_BOUND = TimeSpan.timeSpan(30).seconds();

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

    /// Commits the slice target, WORKERS_ONLY with one instance. Everything after it, the placement, the load and the worker's ACTIVE row, is the system's.
    void deployOnWorkers(AetherNode leader) {
        var target = AetherValue.SliceTargetValue.sliceTargetValue(artifact.version(), 1)
                                .withPlacement("WORKERS_ONLY");

        commit(leader, AetherKey.SliceTargetKey.sliceTargetKey(artifact.base()), target);
    }

    /// The worker the leader's placement view shows the slice ACTIVE on, once there is one. Waits up to `budgetMs`.
    NodeId awaitActiveWorker(Supplier<AetherNode> leader, List<NodeId> workers, long budgetMs, Supplier<String> state) {
        StreamTripwireSupport.awaitUntil(() -> activeWorker(leader.get(), workers).isPresent(),
                                         budgetMs,
                                         "the slice " + artifact + " is ACTIVE on a worker (loaded by the worker from its artifact)" + state.get());

        return activeWorker(leader.get(), workers).unwrap();
    }

    private Option<NodeId> activeWorker(AetherNode leader, List<NodeId> workers) {
        return Option.from(leader.deploymentMap()
                                 .byArtifact(artifact)
                                 .entrySet()
                                 .stream()
                                 .filter(entry -> entry.getValue() == SliceState.ACTIVE && workers.contains(entry.getKey()))
                                 .map(Map.Entry::getKey)
                                 .findFirst());
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
