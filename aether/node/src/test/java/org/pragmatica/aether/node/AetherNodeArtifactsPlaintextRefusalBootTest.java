// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
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
import org.pragmatica.config.ConfigService;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.LocalDiskTier;

import java.nio.file.Path;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #253 BLOCKING #1 (2026-09-04 ruling), pinned through the REAL boot path rather than only through
/// `StorageFactoryEncryptionTest`'s direct `StorageFactory.createAll` unit coverage: a configured
/// `[storage.artifacts]` instance whose disk directory already holds unmarked plaintext blocks, with
/// encryption requested and a resolvable keyring, must abort `AetherNode.aetherNode(...)` naming
/// "artifacts" and [EncryptionError.EnablingOverExistingPlaintext] -- never boot successfully on the
/// old drop-and-substitute-a-plaintext-default behaviour this ruling retired.
///
/// Unlike `AetherNodeStorageEncryptionBootTest` (whose fast-fail scope returns before any port is
/// bound, per its own class doc), this failure fires inside `AetherNode.assembleNode`, reached only
/// after `RabiaNode.rabiaNode(...)` has already constructed real QUIC contexts against a real bound
/// port -- so this test follows `AetherNodeContentStorageWarnBootTest`'s real-boot config shape
/// (self-inclusive `coreNodes`, `TlsConfig.selfSignedMutual()`, an ephemeral `freePort()`) even though
/// the outcome under test is failure, not a successful boot.
class AetherNodeArtifactsPlaintextRefusalBootTest {
    private static final byte[] PLAINTEXT = "artifacts-legacy-plaintext-block-253".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET_PATH = "path/to/k1";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);

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

        // AetherNode.createResourceProviderFacade sets these as process-wide static singletons only
        // when configProvider is present; this test supplies Option.none() (the "artifacts" refusal
        // fires long before that branch), but clearing unconditionally costs nothing and keeps this
        // test inert with respect to fixture bleed regardless of future wiring changes.
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_refusesBoot_whenArtifactsDiskAlreadyHoldsPlaintextAndEncryptionRequested() {
        var artifactsDir = tempDir.resolve("artifacts-legacy-disk");
        seedRawPlaintextBlock(artifactsDir);

        SecretsProvider provider = path -> Promise.success(Map.of(SECRET_PATH, VALID_AES256_KEY).get(path));
        var encryption = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of("k1", "${secrets:" + SECRET_PATH + "}"),
                                                                                       "k1",
                                                                                       false));
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024,
                                                64L * 1024 * 1024,
                                                artifactsDir.toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000,
                                                "60s",
                                                5,
                                                "",
                                                true);

        var config = minimalConfig(environmentWith(Option.some(provider)), encryption, artifactsConfig);

        AetherNode.aetherNode(config, () -> {})
                  .onSuccess(booted -> {
                      node = booted;
                      fail("boot must refuse: 'artifacts' disk directory holds plaintext blocks with no encryption marker");
                  })
                  .onFailure(cause -> {
                      assertThat(cause.message()).as("the failure must name the failing instance")
                                                 .contains("artifacts");
                      assertThat(cause.source().isPresent()).as("StorageFactory.createOne wraps the underlying cause")
                                                            .isTrue();
                      assertThat(cause.source().unwrap()).isInstanceOf(EncryptionError.EnablingOverExistingPlaintext.class);
                  });
    }

    /// Seeds `dir` with a block written through the RAW, unwrapped disk tier -- exactly what a prior
    /// unencrypted boot of the `artifacts` instance would have left behind.
    private static void seedRawPlaintextBlock(Path dir) {
        LocalDiskTier.localDiskTier(dir, 64L * 1024 * 1024)
                     .unwrap()
                     .put(BlockId.blockId(PLAINTEXT).unwrap(), PLAINTEXT)
                     .await()
                     .onFailure(cause -> fail("seeding a raw plaintext block failed: " + cause.message()));
    }

    /// Mirrors `AetherNodeContentStorageWarnBootTest#minimalConfig`'s real-assembly shape (self in
    /// `coreNodes`, mutual QUIC TLS, an ephemeral port) so execution runs past `RabiaNode` construction
    /// into `AetherNode.assembleNode`, where the `[storage.artifacts]` refusal under test fires. No
    /// `configProvider` is supplied -- the refusal fires before the SPI/content-storage branch that
    /// requires one.
    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                                   Option<StorageEncryptionConfig> storageEncryption,
                                                   StorageConfig artifactsConfig) {
        var self = NodeId.nodeId("artifacts-plaintext-refusal-boot-test").unwrap();
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
                                .withStorageEncryption(storageEncryption);
    }

    /// Ephemeral free port for the self node's cluster address. Small open/close race is acceptable
    /// for a single test process.
    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Option<EnvironmentIntegration> environmentWith(Option<SecretsProvider> secrets) {
        return Option.some(EnvironmentIntegration.environmentIntegration(Option.none(), secrets, Option.none()));
    }
}
