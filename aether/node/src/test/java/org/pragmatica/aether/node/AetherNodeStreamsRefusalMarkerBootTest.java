// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.LocalDiskTier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #852 round 2, pinned through the REAL boot path: `assembleNode` used to settle the boot in two
/// calls -- `StorageFactory.createAll` for the config-map instances, then the four-argument
/// `defaultStreamStorage` for `streams` -- and only the first was two-phase. A `streams` refusal
/// therefore orphaned every marker `createAll` had already committed, which is verbatim the #852
/// symptom one call later. `streams` is now armed inside the same `createAll`, so no marker is
/// written at all.
///
/// `StorageFactoryEncryptionTest`'s `bootDecision_*` cases pin the factory's five-argument entry
/// point; what they cannot pin is that `assembleNode` USES it rather than making two calls again.
/// That is the whole point of this class: it reaches the ordering through
/// `AetherNode.aetherNode(...)` itself. Real-boot config shape (self-inclusive `coreNodes`, mutual
/// QUIC TLS, an ephemeral port) mirrors `AetherNodeArtifactsPlaintextRefusalBootTest`, for the same
/// reason it gives -- this failure fires inside `assembleNode`, past real `RabiaNode` construction.
class AetherNodeStreamsRefusalMarkerBootTest {
    private static final byte[] PLAINTEXT = "streams-legacy-plaintext-segment-852".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET_PATH = "path/to/k1";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;

    @TempDir
    Path tempDir;

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

    /// `artifacts` is encrypted over a FRESH directory, so its guard passes and the old ordering
    /// stamped it before `streams` was ever consulted; `streams_encrypted` is on over a segments
    /// directory that already holds plaintext, so the `streams` guard refuses. The assertion that
    /// matters is on the directory: an orphaned marker is only observable across a boot, and the
    /// operator's next move -- backing `artifacts` out to `encrypted = false` -- is what used to fail.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_leavesNoArtifactsMarker_whenTheStreamsArmRefusesTheBoot() {
        var artifactsDir = tempDir.resolve("artifacts-disk");
        var markerPath = artifactsDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME);
        var self = NodeId.nodeId("streams-refusal-marker-boot-test").unwrap();
        // `AetherNode.streamDataDir`: sibling of the artifacts disk path, named `stream-segments`,
        // then the node id; the guarded directory is its `segments` child.
        var segmentsDir = artifactsDir.resolveSibling("stream-segments")
                                      .resolve(self.id())
                                      .resolve("segments");

        seedRawPlaintextBlock(segmentsDir);

        AetherNode.aetherNode(minimalConfig(self, artifactsDir), () -> {})
                   .onSuccess(booted -> {
                       node = booted;
                       fail("boot must refuse: the streams segments directory holds plaintext blocks and "
                            + "streams_encrypted is on");
                   })
                   .onFailure(cause -> assertThat(cause.message()).as("the refusal names the segments directory the streams guard walked")
                                                                  .contains("segments"));

        assertThat(Files.exists(markerPath)).as("a boot refused by the streams arm must leave no encryption marker on "
                                                + "'artifacts', whose own guard had already passed in the same decision")
                                            .isFalse();
    }

    private static void seedRawPlaintextBlock(Path dir) {
        LocalDiskTier.localDiskTier(dir, DISK_MAX_BYTES)
                     .unwrap()
                     .put(BlockId.blockId(PLAINTEXT).unwrap(), PLAINTEXT)
                     .await()
                     .onFailure(cause -> fail("seeding a raw plaintext block failed: " + cause.message()));
    }

    private AetherNodeConfig minimalConfig(NodeId self, Path artifactsDir) {
        SecretsProvider provider = path -> Promise.success(Map.of(SECRET_PATH, VALID_AES256_KEY).get(path));
        var environment = Option.some(EnvironmentIntegration.environmentIntegration(Option.none(),
                                                                                     Option.some(provider),
                                                                                     Option.none()));
        var encryption = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of("k1", "${secrets:" + SECRET_PATH + "}"),
                                                                                      "k1",
                                                                                      true));
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024,
                                                DISK_MAX_BYTES,
                                                artifactsDir.toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000,
                                                "60s",
                                                5,
                                                "",
                                                true);
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
                                .environment(environment)
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(Map.of("artifacts", artifactsConfig))
                                .build()
                                .withStorageEncryption(encryption);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
