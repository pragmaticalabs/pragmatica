// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1519 — after #1390 every node aborted at boot with "Durable control storage requires
/// cluster.consensus_path or an explicit artifacts storage path", because no shipped template set
/// either. Cores now run in-memory Rabia (owner ruling, session 28), so a node assembles from the
/// config the published image ships — which sets neither — with nothing added.
class ShippedConfigBootTest {
    /// The file `docker/aether-node/Dockerfile` COPYs to `/app/aether.toml` in the published image.
    private static final String SHIPPED_CONFIG = "aether/docker/aether-node/aether.toml";

    private AetherNode node;

    /// #912: the shipped config sets no storage path, so the node resolves the production default root. It is
    /// pointed here instead of `/data/aether`: where `/data` is writable this test used to write `artifacts`,
    /// `content` and a live stream WAL there, shared by every later run (found by a canary run of the module).
    @TempDir
    Path storageRoot;

    private String previousRoot;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(_ -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
        restoreDefaultRoot();
    }

    private void rootDefaultStorageInTempDir() {
        previousRoot = System.getProperty(StorageConfig.DEFAULT_ROOT_PROPERTY);
        System.setProperty(StorageConfig.DEFAULT_ROOT_PROPERTY, storageRoot.toString());
    }

    private void restoreDefaultRoot() {
        if (previousRoot == null) {
            System.clearProperty(StorageConfig.DEFAULT_ROOT_PROPERTY);
        } else {
            System.setProperty(StorageConfig.DEFAULT_ROOT_PROPERTY, previousRoot);
        }
    }

    /// Non-vacuity: the file really is the shipped one (parsed, `[app-http] enabled = true`), and it
    /// really carries #1519's precondition — no `cluster.consensus_path`, no artifacts storage path.
    @Test
    void shippedConfig_setsNeitherConsensusPathNorArtifactsStorage() throws IOException {
        var shipped = shippedConfigPath();

        assertThat(shippedConfig(shipped).appHttp().enabled()).as("the parse reached the shipped file").isTrue();
        assertThat(shippedConfig(shipped).storage()).as("no explicit artifacts storage path").doesNotContainKey("artifacts");
        assertThat(Files.readString(shipped)).as("no consensus path").doesNotContain("consensus_path");
    }

    @Test
    void aetherNode_assemblesFromShippedConfig_withoutConsensusPathOrArtifactsStorage() {
        rootDefaultStorageInTempDir();
        var shipped = shippedConfigPath();
        var provider = ConfigurationProvider.builder()
                                            .withTomlFile(shipped)
                                            .build();

        node = AetherNode.aetherNode(nodeConfig(provider, shippedConfig(shipped)), () -> {})
                         .onFailure(cause -> fail("a node must assemble from the shipped config (#1519): " + cause.message()))
                         .unwrap();

        assertThat(node.self().id()).startsWith("shipped-config-boot-");
        assertThat(storageRoot.resolve("stream-segments").resolve(node.self().id()))
            .as("#912: the default storage root the shipped config resolves is the injected one, not /data/aether")
            .exists();
    }

    private static AetherNodeConfig nodeConfig(ConfigurationProvider provider, AetherConfig shipped) {
        var self = NodeId.nodeId("shipped-config-boot-" + UUID.randomUUID()).unwrap();
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
                               .configProvider(Option.some(provider))
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(shipped.storage())
                               .build();
    }

    private static AetherConfig shippedConfig(Path shipped) {
        return ConfigLoader.load(shipped)
                           .onFailure(cause -> fail("the shipped node config did not load: " + cause.message()))
                           .unwrap();
    }

    private static Path shippedConfigPath() {
        var current = codeSourceLocation();

        while (current != null) {
            if (Files.isRegularFile(current.resolve(SHIPPED_CONFIG))) {
                return current.resolve(SHIPPED_CONFIG);
            }

            current = current.getParent();
        }

        throw new AssertionError("repository root not found above " + codeSourceLocation());
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(ShippedConfigBootTest.class.getProtectionDomain()
                                                      .getCodeSource()
                                                      .getLocation()
                                                      .toURI());
        } catch (Exception e) {
            throw new AssertionError("cannot locate the test's own code source", e);
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
