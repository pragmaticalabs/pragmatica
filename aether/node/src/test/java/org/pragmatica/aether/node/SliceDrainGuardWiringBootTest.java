// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery.DrainRefusal;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1488 review F3: pins the `AetherNode` WIRING `leaderReconciler.setSliceDrainGuard(...)`. Every
/// `LeaderReconcilerTest` fixture wires the guard itself, and the reconciler's default guard permits
/// every drain, so deleting the production line reddened nothing.
///
/// A real self-forming single-node boot (the #858 shape). The reconciler is not reachable through the
/// `AetherNode` interface and a single node never becomes leader, so no drain pass can be driven here.
/// The test therefore reads the guard the booted node's reconciler actually holds, by reflection, and
/// applies it to a placement written into the node's own KV-Store: a node that is the only ACTIVE
/// holder of a slice with no target (minAvailable 1) must be refused. The default guard refuses
/// nothing, so the refusal exists only if `AetherNode` wired the KV-backed guard to this node's store.
/// The reflection names are the pinned surface: renaming either is a red, not a silent pass.
class SliceDrainGuardWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final String RECONCILER_ACCESSOR = "leaderReconciler";
    private static final String GUARD_FIELD = "sliceDrainGuard";
    private static final Artifact SLICE = Artifact.artifact("org.example:drain-guard-wiring:1.0.0").unwrap();
    private static final NodeId SOLE_OWNER = NodeId.nodeId("drain-guard-owner-" + UUID.randomUUID()).unwrap();

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_wiresKvBackedSliceDrainGuard_intoItsLeaderReconciler() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        var guard = wiredGuard(node);

        assertThat(guard.apply(SOLE_OWNER, Set.of()))
            .as("control: with no placement in the node's KV-Store the guard has nothing to refuse")
            .isEqualTo(Option.none());

        apply(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(SOLE_OWNER, SLICE),
                                  NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE)));

        assertThat(guard.apply(SOLE_OWNER, Set.of()).map(DrainRefusal::owner))
            .as("the booted node's reconciler must hold the KV-backed guard over this node's store: draining the sole ACTIVE holder is refused")
            .isEqualTo(Option.some(SOLE_OWNER));
    }

    @SuppressWarnings("unchecked")
    private static BiFunction<NodeId, Set<NodeId>, Option<DrainRefusal>> wiredGuard(AetherNode node) throws ReflectiveOperationException {
        var reconciler = (LeaderReconciler) node.getClass()
                                                .getMethod(RECONCILER_ACCESSOR)
                                                .invoke(node);
        var field = LeaderReconciler.class.getDeclaredField(GUARD_FIELD);

        field.setAccessible(true);

        return ((AtomicReference<BiFunction<NodeId, Set<NodeId>, Option<DrainRefusal>>>) field.get(reconciler)).get();
    }

    private void apply(KVCommand<AetherKey> command) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("drain-guard-wiring-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
