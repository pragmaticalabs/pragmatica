// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.EncryptingStorageTier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #858: exercises the post-formation DHT marker check on a REAL, self-forming single-node cluster
/// through `AetherNode.start()` -- the boundary the #858 fix moved the check across (previously
/// `createAll`, called from the constructor, awaited the marker check for up to
/// [StorageFactory#DHT_MARKER_TIMEOUT] before the `DHTClient` could route).
///
/// Two claims, each with its own test:
///
///   1. Construction stays fast even when the 'artifacts' instance carries a DHT tier with no
///      keyring -- the branch that used to block. "start() succeeds" is not evidence of this by
///      itself (a slow-but-eventually-successful construction would pass such an assertion); the
///      timing bound is the actual claim, and its discriminating power is demonstrated in the PR by
///      a mutation probe: reverting `StorageFactory.java`, `AetherNode.java` and `DhtStorageTier.java`
///      to their pre-#858 shapes (`e01e32dad`) and re-running this test alone shows it fail at ~30 s.
///   2. With a keyring configured on the DHT-backed 'artifacts' instance, `start()` performs the
///      deferred write for real: the marker is read back directly off the node's own `DHTClient`
///      (`ManageableNode.dhtClient()` -- a pre-existing public accessor, not a new test seam; see the
///      class-level note on why no new production surface was needed) and is absent before formation,
///      present with the active key id as its value after `start()` resolves.
///
/// A genuine cross-boot scenario ("marker written by a PRIOR boot, no keyring THIS boot -> refusal")
/// is infeasible as a real-boot test here: no seam exists across any of the four
/// `AetherNode.aetherNode(...)` factory overloads to share one node's in-memory DHT store with a
/// second, separately-constructed node -- every `dhtClient`/`dhtNode` reference in `AetherNode.java`
/// is local to `assembleNode`, never returned or accepted as a parameter. That scenario stays covered
/// at the `StorageFactory` unit level by
/// `StorageFactoryEncryptionTest#verifyDhtMarker_fails_whenDhtCarriesEncryptionMarker_andDiskUnavailable_andNoKeyringSupplied`.
class AetherNodeDhtMarkerPostFormationBootTest {
    private static final String SECRET_PATH = "path/to/k1";
    private static final String ACTIVE_KEY_ID = "k1";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final TimeSpan START_BOUND = timeSpan(15).seconds();
    private static final TimeSpan MARKER_READ_BOUND = timeSpan(5).seconds();
    private static final long CONSTRUCTION_BOUND_MS = 5_000;

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void construction_staysUnderFiveSeconds_whenArtifactsInstanceHasDhtTierButNoKeyring() {
        var constructStarted = System.nanoTime();

        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none()), () -> {})
                          .onFailure(cause -> fail("construction must not touch the DHT any more (#858) - " + cause.message()))
                          .unwrap();

        assertThat(elapsedMs(constructStarted)).as("construction must not block on the DHT marker check (#858); "
                                                    + "the pre-#858 code awaited the full 30 s marker timeout here "
                                                    + "-- see the PR's mutation-probe evidence for a run against "
                                                    + "the reverted hunk")
                                                .isLessThan(CONSTRUCTION_BOUND_MS);
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void start_writesDhtMarkerPostFormation_absentBeforePresentAfter_readThroughNodesOwnDhtClient() {
        var provider = (SecretsProvider) path -> Promise.success(Map.of(SECRET_PATH, VALID_AES256_KEY).get(path));
        var environment = Option.some(EnvironmentIntegration.environmentIntegration(Option.none(), Option.some(provider), Option.none()));
        var encryption = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of(ACTIVE_KEY_ID, "${secrets:" + SECRET_PATH + "}"),
                                                                                      ACTIVE_KEY_ID,
                                                                                      false));
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024, 64L * 1024 * 1024,
                                                tempDir.resolve("artifacts-disk").toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000, "60s", 5, "", true);

        node = AetherNode.aetherNode(minimalConfig(environment, encryption, artifactsConfig), () -> {})
                          .onFailure(cause -> fail("construction must not touch the DHT any more (#858), even with "
                                                  + "a keyring configured - " + cause.message()))
                          .unwrap();

        var check = node.storageSetups()
                        .get("artifacts")
                        .dhtMarkerCheck()
                        .unwrap();
        var client = node.dhtClient().unwrap();
        var markerKey = check.dhtKeyPrefix() + "/" + EncryptingStorageTier.MARKER_FILE_NAME;

        var beforeMarker = client.get(markerKey).await(MARKER_READ_BOUND).unwrap();
        assertThat(beforeMarker.isPresent()).as("the marker must not exist before cluster formation -- "
                                                + "verifyDhtMarker runs post-formation, from start(), never during "
                                                + "construction")
                                             .isFalse();

        node.start().await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed and write the DHT marker post-formation - " + cause.message()));

        var afterMarker = client.get(markerKey).await(MARKER_READ_BOUND).unwrap();
        assertThat(afterMarker.isPresent()).as("start() must have written the DHT marker for the encrypted "
                                              + "'artifacts' instance once cluster formation resolved")
                                            .isTrue();
        assertThat(new String(afterMarker.unwrap(), StandardCharsets.UTF_8)).isEqualTo(ACTIVE_KEY_ID);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment, Option<StorageEncryptionConfig> storageEncryption) {
        var self = NodeId.nodeId("dht-marker-post-formation-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.FULL).coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none()).configProvider(Option.none()).environment(environment)
                                .build().withStorageEncryption(storageEncryption);
    }

    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                                  Option<StorageEncryptionConfig> storageEncryption,
                                                  StorageConfig artifactsConfig) {
        var self = NodeId.nodeId("dht-marker-post-formation-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.FULL).coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none()).configProvider(Option.none()).environment(environment)
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(Map.of("artifacts", artifactsConfig))
                                .build().withStorageEncryption(storageEncryption);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
