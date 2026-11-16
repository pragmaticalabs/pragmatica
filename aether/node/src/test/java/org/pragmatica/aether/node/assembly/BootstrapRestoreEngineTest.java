// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.GitBackupRepository;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2767 kill test for the mutant "`ClusterBootstrapAssembly.bootstrapRegistrars` hands the `BackupRestoreCoordinator` a fixed engine
/// instead of `config.deploymentEngine()`": the restore of a backup that carries no engine marker (rc4 and earlier) must end with a marker
/// of the engine the NODE IS CONFIGURED for. The test goes through the real registrar wiring (the coordinator is built by the assembly, not
/// by the test), restores a markerless backup from a real git remote into a store holding no marker, and reads the marker the restore left.
/// Both engines are asserted, so the engine cannot be a constant. The coordinator's own rule is pinned by `BackupRestoreCoordinatorTest`.
class BootstrapRestoreEngineTest {
    private static final NodeId SELF = NodeId.nodeId("restore-1").unwrap();
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final ConfigKey ALPHA_KEY = ConfigKey.forKey("alpha");
    private static final ConfigValue ALPHA = ConfigValue.configValue("alpha", "1");

    @TempDir
    Path temp;

    @Test
    void restoringAMarkerlessBackup_underAnFsm2Config_leavesAnFsm2Marker() {
        assertThat(markerLeftByRestoreUnder(DeploymentEngine.FSM2)).isEqualTo(DeploymentEngine.FSM2);
    }

    @Test
    void restoringAMarkerlessBackup_underALegacyConfig_leavesALegacyMarker() {
        assertThat(markerLeftByRestoreUnder(DeploymentEngine.LEGACY)).isEqualTo(DeploymentEngine.LEGACY);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DeploymentEngine markerLeftByRestoreUnder(DeploymentEngine configured) {
        var remote = seededRemote();
        var config = mock(AetherNodeConfig.class);

        when(config.deploymentEngine()).thenReturn(configured);
        when(config.self()).thenReturn(SELF);
        when(config.clusterSecret()).thenReturn(Option.none());
        when(config.backupConfig()).thenReturn(Option.some(new BackupConfig(true,
                                                                            temp.resolve("node-backup")
                                                                                .toString(),
                                                                            remote,
                                                                            RestoreMode.AUTO)));

        var router = MessageRouter.mutable();
        var slot = new AtomicLong();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, NODE_CODEC, NODE_CODEC);

        kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                       LeaderValue.leaderValue(SELF, 1L)))),
                                 slot.incrementAndGet());

        var routes = new ArrayList<MessageRouter.Entry<?>>();
        var inputs = new ClusterBootstrapAssembly.BootstrapRegistrarsInputs(config,
                                                                            kvStore,
                                                                            NODE_CODEC,
                                                                            () -> true,
                                                                            batch -> Promise.success(kvStore.processCommitted(kvStore.createBatch(batch),
                                                                                                                              slot.incrementAndGet())),
                                                                            OperatorWarningSink.handingOffTo(_ -> {}),
                                                                            routes);

        ClusterBootstrapAssembly.bootstrapRegistrars(inputs);
        // Registration order in bootstrapRegistrars: admin key, incarnation genesis, backup service, then the restore coordinator last.
        var restoreCoordinator = leaderChangeConsumers(routes).getLast();

        restoreCoordinator.accept(new LeaderChange(Option.some(SELF), true));
        await().atMost(30, SECONDS)
               .until(() -> kvStore.get(ALPHA_KEY).isPresent() && kvStore.get(DeploymentEngineKey.deploymentEngineKey()).isPresent());

        return kvStore.get(DeploymentEngineKey.deploymentEngineKey())
                      .map(DeploymentEngineValue.class::cast)
                      .map(DeploymentEngineValue::engine)
                      .unwrap();
    }

    /// A bare git remote whose `kv-backup` branch holds a backup with an incarnation and one entry, and no engine marker.
    private String seededRemote() {
        var remote = temp.resolve("remote.git");
        var seeder = temp.resolve("seeder");
        var all = new HashMap<AetherKey, AetherValue>();

        all.put(ALPHA_KEY, ALPHA);
        all.put(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.clusterIncarnationValue("01K4ZT9Q6W3X8Y2B7C5D1E0F9G", 3, "01K4ZT9Q6W3X8Y2B7C5D1INST0"));
        git(temp, "init", "--quiet", "--bare", remote.toString());
        git(remote, "config", "gc.autoDetach", "false");
        git(remote, "config", "maintenance.autoDetach", "false");
        git(temp, "clone", "--quiet", remote.toString(), seeder.toString());
        git(seeder, "checkout", "--quiet", "-B", "kv-backup");
        try {
            Files.writeString(seeder.resolve(GitBackupRepository.FILE),
                              BackupEntryCodec.backupEntryCodec(NODE_CODEC)
                                              .encode(40, all)
                                              .unwrap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(seeder, "add", GitBackupRepository.FILE);
        git(seeder, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", "kv backup (markerless)");
        git(seeder, "push", "--quiet", "origin", "kv-backup");

        return remote.toString();
    }

    private static void git(Path dir, String... args) {
        var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());

            assertThat(process.waitFor()).as("git %s: %s", List.of(args), output).isZero();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Consumer<LeaderChange>> leaderChangeConsumers(List<MessageRouter.Entry<?>> routes) {
        return routes.stream()
                     .flatMap(entry -> entry.entries())
                     .filter(tuple -> tuple.first() == LeaderChange.class)
                     .map(tuple -> (Consumer<LeaderChange>) tuple.last())
                     .toList();
    }
}
