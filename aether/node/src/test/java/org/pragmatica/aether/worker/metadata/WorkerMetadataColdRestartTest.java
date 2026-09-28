// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metadata;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1529 acceptance 1 — a worker that survives a core cold restart installs the new run's manifests.
///
/// A cold restart starts the cores from zero and restores through consensus (#1533), so the restored
/// core's committed revision restarts far below the revision the surviving worker installed. Without
/// a cluster incarnation the worker cannot tell that core from one lagging behind it: it sends its
/// installed revision as the minimum, the core answers `core-behind-worker`, and the worker never
/// installs another manifest. The restore increments the cluster incarnation, and the manifest carries
/// it: a newer incarnation resets the worker's revision latch.
///
/// The lagging-core refusal within ONE incarnation is `WorkerMetadataChannelTest.laggingCoreRefusesWorkersInstalledRevision`;
/// the arm below pins that a manifest from an OLDER incarnation is refused however high its revision.
class WorkerMetadataColdRestartTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId WORKER = new NodeId("worker");
    private static final WorkerMetadataLimits LIMITS = new WorkerMetadataLimits(64,
                                                                                8192,
                                                                                65536,
                                                                                1048576,
                                                                                8,
                                                                                32,
                                                                                org.pragmatica.lang.io.TimeSpan.timeSpan(30)
                                                                                                               .seconds(),
                                                                                org.pragmatica.lang.io.TimeSpan.timeSpan(0)
                                                                                                               .millis());

    private final SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private final AetherKey.ConfigKey configKey = new AetherKey.ConfigKey("test", Option.none());
    private final KVStore<AetherKey, AetherValue> worker = new KVStore<>(MessageRouter.mutable(), codec, codec);
    private final ArrayDeque<ProtocolMessage> requests = new ArrayDeque<>();
    private final WorkerMetadataClient client = new WorkerMetadataClient(WORKER,
                                                                         worker,
                                                                         codec,
                                                                         () -> Set.of(CORE),
                                                                         CORE::equals,
                                                                         (_, message) -> requests.add(message),
                                                                         _ -> Result.success(Unit.unit()),
                                                                         _ -> {},
                                                                         () -> {},
                                                                         _ -> {},
                                                                         LIMITS);

    @Test
    void survivingWorker_installsTheRestoredRunsManifest_afterACoreColdRestart() {
        var firstRun = new Core(1L);

        firstRun.seed(5, "before-restart");
        exchange(firstRun);
        assertThat(configValue()).isEqualTo("before-restart");

        var restoredRun = new Core(2L);

        restoredRun.seed(2, "after-restart");
        exchange(restoredRun);

        assertThat(configValue()).as("the worker installs the new run's manifest despite its lower revision")
                                 .isEqualTo("after-restart");
    }

    @Test
    void survivingWorker_refusesAManifestFromAnOlderIncarnation_evenAtAHigherRevision() {
        var restoredRun = new Core(2L);

        restoredRun.seed(2, "current-run");
        exchange(restoredRun);
        assertThat(configValue()).isEqualTo("current-run");

        var previousRun = new Core(1L);

        previousRun.seed(9, "previous-run");
        exchange(previousRun);

        assertThat(configValue()).as("a core still serving the previous incarnation cannot overwrite the current one")
                                 .isEqualTo("current-run");
    }

    private String configValue() {
        return worker.getTyped(configKey, AetherValue.ConfigValue.class)
                     .map(AetherValue.ConfigValue::value)
                     .or("absent");
    }

    private void exchange(Core core) {
        for (var round = 0; round < 3; round++) {
            client.tick();
            pump(core);
        }
    }

    private void pump(Core core) {
        for (var step = 0; step < 10000 && (!requests.isEmpty() || !core.responses.isEmpty()); step++) {
            if (!requests.isEmpty()) {
                core.deliver(requests.remove());
            }

            if (!core.responses.isEmpty()) {
                switch (core.responses.remove()) {
                    case WorkerMetadataMessage.Manifest response -> client.onManifest(response);
                    case WorkerMetadataMessage.Chunk response -> client.onChunk(response);
                    default -> org.assertj.core.api.Assertions.fail("Unexpected response");
                }
            }
        }
    }

    private final class Core {
        final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), codec, codec);
        final ArrayDeque<ProtocolMessage> responses = new ArrayDeque<>();
        final WorkerMetadataServer server;

        Core(long clusterIncarnation) {
            server = new WorkerMetadataServer(CORE,
                                              store,
                                              codec,
                                              (_, message) -> responses.add(message),
                                              WORKER::equals,
                                              () -> Set.of(CORE),
                                              _ -> List.of(),
                                              LIMITS,
                                              (_, _) -> {},
                                              () -> clusterIncarnation);
        }

        void seed(long revision, String value) {
            var activation = new AetherKey.ActivationDirectiveKey(WORKER);
            var own = AetherValue.ActivationDirectiveValue.worker("ours", "");
            var config = new AetherValue.ConfigValue("test", value, revision);
            List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(activation, own),
                                                          new KVCommand.Put<>(configKey, config));

            store.processCommitted(store.createBatch(commands), revision);
            server.put(activation, own);
            server.put(configKey, config);
        }

        void deliver(ProtocolMessage request) {
            switch (request) {
                case WorkerMetadataMessage.ManifestRequest manifest -> server.onManifestRequest(manifest);
                case WorkerMetadataMessage.ChunkRequest chunk -> server.onChunkRequest(chunk);
                default -> org.assertj.core.api.Assertions.fail("Unexpected request");
            }
        }
    }
}
