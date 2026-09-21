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
import org.pragmatica.aether.node.lifecycle.NodeState;
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
import org.pragmatica.storage.EncryptionError;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #849, the real-boundary pins (adopted from rev1413's probe, 2026-09-22): a REAL single-node
/// `AetherNode.start()` -- the shape `AetherNodeDhtMarkerPostFormationBootTest` uses for `artifacts`
/// -- proving the STREAMS DHT marker check is reached by the node's own post-formation loop in both
/// directions, and that readiness waits on it. `StorageFactoryEncryptionTest`'s streams cases drive
/// `StorageFactory`'s functions over a test-built check list and cannot see `AetherNode`'s private
/// loop diverge; these can. At the base `d444d22c6` the first two are red: `start()` succeeds over
/// the marked namespace, and no marker is written.
class AetherNodeStreamsDhtMarkerBootTest {
    private static final String SECRET_PATH = "path/to/k1";
    private static final String ACTIVE_KEY_ID = "k1";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String STREAMS_MARKER_KEY = "stream-segments/" + EncryptingStorageTier.MARKER_FILE_NAME;
    private static final TimeSpan START_BOUND = timeSpan(15).seconds();
    private static final TimeSpan MARKER_READ_BOUND = timeSpan(5).seconds();
    private static final long ACTIVE_BOUND_MS = 30_000;

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

    /// Reverse direction on the real boundary: a `stream-segments` marker seeded into the node's own DHT,
    /// `streams_encrypted` off (no `[storage.encryption]` at all) -> `start()` must fail with
    /// `EncryptedTierRequiresKeyring` NAMING `streams`, and the node must never report ACTIVE.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void start_failsNamingStreams_whenStreamSegmentsMarkerPresentAndNoKeyring() {
        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none()), () -> {})
                          .onFailure(cause -> fail("construction must succeed - " + cause.message()))
                          .unwrap();

        var pendingBeforeStart = StorageFactory.pendingDhtAdmissions(node.storageSetups());

        node.dhtClient()
            .unwrap()
            .put(STREAMS_MARKER_KEY, ACTIVE_KEY_ID.getBytes(StandardCharsets.UTF_8))
            .await(MARKER_READ_BOUND)
            .onFailure(cause -> fail("PRECONDITION: seeding the streams marker failed - " + cause.message()));

        node.start()
            .await(START_BOUND)
            .onSuccess(_ -> fail("a stream-segments marker present with no keyring must fail start() -- if this "
                                 + "passes, the streams check is constructed but never reached by start()"))
            .onFailure(cause -> {
                assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class);
                assertThat(((EncryptionError.EncryptedTierRequiresKeyring) cause).instanceName()).isEqualTo("streams");
                assertThat(((EncryptionError.EncryptedTierRequiresKeyring) cause).keyId()).isEqualTo(ACTIVE_KEY_ID);
            });

        assertThat(node.nodeLifecycle().currentState()).as("a node whose streams DHT tier was refused must never report ACTIVE")
                                                      .isNotEqualTo(NodeState.ACTIVE);
        assertThat(node.storageSetups().get("streams").dhtAdmissionPending()).as("the gate is resolved (with the refusal), "
                                                                                 + "not left pending")
                                                                             .isFalse();
        assertThat(pendingBeforeStart).as("before start(), the streams gate must be among the pending admissions -- the "
                                          + "readiness component reads this list")
                                      .contains("streams");
    }

    /// Forward direction on the real boundary: `streams_encrypted = true` -> `start()` writes
    /// `stream-segments/.encryption-enabled` = active key id, absent before, present after.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void start_writesStreamSegmentsMarker_whenStreamsEncrypted() {
        var provider = (SecretsProvider) path -> Promise.success(Map.of(SECRET_PATH, VALID_AES256_KEY).get(path));
        var environment = Option.some(EnvironmentIntegration.environmentIntegration(Option.none(), Option.some(provider), Option.none()));
        var encryption = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of(ACTIVE_KEY_ID, "${secrets:" + SECRET_PATH + "}"),
                                                                                      ACTIVE_KEY_ID,
                                                                                      true));

        node = AetherNode.aetherNode(minimalConfig(environment, encryption), () -> {})
                          .onFailure(cause -> fail("construction must succeed with streams_encrypted=true - " + cause.message()))
                          .unwrap();
        var client = node.dhtClient().unwrap();

        assertThat(client.get(STREAMS_MARKER_KEY).await(MARKER_READ_BOUND).unwrap().isPresent()).as("no streams marker before start()")
                                                                                                 .isFalse();

        node.start().await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed and write the streams DHT marker - " + cause.message()));

        var after = client.get(STREAMS_MARKER_KEY).await(MARKER_READ_BOUND).unwrap();

        assertThat(after.isPresent()).as("start() must have stamped stream-segments once formation resolved").isTrue();
        assertThat(new String(after.unwrap(), StandardCharsets.UTF_8)).isEqualTo(ACTIVE_KEY_ID);
        assertThat(awaitActive(node)).as("an admitted encrypted-streams boot reaches ACTIVE").isEqualTo(NodeState.ACTIVE);
    }

    /// Control: unmarked namespace, plain boot -> admitted, reaches ACTIVE. Guards against the opposite
    /// failure: a streams gate that is constructed but never resolved would hold readiness forever.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void start_reachesActiveAndWritesNoMarker_whenNamespaceUnmarkedAndStreamsNotEncrypted() {
        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none()), () -> {})
                          .onFailure(cause -> fail("construction must succeed - " + cause.message()))
                          .unwrap();

        node.start().await(START_BOUND).onFailure(cause -> fail("plain start() must succeed - " + cause.message()));

        assertThat(awaitActive(node)).isEqualTo(NodeState.ACTIVE);
        assertThat(node.dhtClient().unwrap().get(STREAMS_MARKER_KEY).await(MARKER_READ_BOUND).unwrap().isPresent()).as("a plain boot stamps nothing")
                                                                                                                    .isFalse();
        assertThat(StorageFactory.pendingDhtAdmissions(node.storageSetups())).isEmpty();
    }

    private static NodeState awaitActive(AetherNode candidate) {
        var deadline = System.currentTimeMillis() + ACTIVE_BOUND_MS;

        while (candidate.nodeLifecycle().currentState() != NodeState.ACTIVE && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return candidate.nodeLifecycle().currentState();
    }

    private AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                           Option<StorageEncryptionConfig> storageEncryption) {
        var artifactsConfig = HermeticStorage.storageConfigAt(HermeticStorage.uncreatableRootIn(tempDir), storageEncryption.isPresent());
        var self = NodeId.nodeId("streams-dht-marker-boot-" + UUID.randomUUID()).unwrap();
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
