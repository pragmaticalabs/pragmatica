// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.ClusterConfig;
import org.pragmatica.aether.config.DhtReplicationConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.config.NodeConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.config.TlsConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.environment.GossipKeyMaterial;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1200 — the config path by which an operator-provisioned (or leader-provisioned) node receives the cluster's CURRENT gossip key.
/// `AETHER_GOSSIP_KEY` / `AETHER_GOSSIP_KEY_FILE`, `<keyId>:<base64 of 32 bytes>`, read like the cluster secret (#828).
class MainProvisionedGossipKeyTest {
    private static final byte[] KEY = filled((byte) 0x33);
    private static final String ENCODED = Base64.getEncoder().encodeToString(KEY);
    private static final String DELIVERED = "17:" + ENCODED;

    @Test
    void provisionedGossipKey_nothingSet_isAbsent_andTheNodeKeepsItsDerivedKey() {
        var resolved = Main.provisionedGossipKey(Map.<String, String> of()::get);

        assertThat(resolved.isSuccess()).isTrue();
        assertThat(resolved.unwrap().isEmpty()).isTrue();
    }

    @Test
    void provisionedGossipKey_plainVariable_isParsed() {
        var material = Main.provisionedGossipKey(Map.of("AETHER_GOSSIP_KEY", DELIVERED)::get).unwrap().unwrap();

        assertThat(material.keyId()).isEqualTo(17);
        assertThat(material.key()).isEqualTo(KEY);
    }

    @Test
    void provisionedGossipKey_fileVariable_isReadAndParsed() throws Exception {
        var file = Files.createTempFile("gossip-key-", ".txt");

        try {
            Files.writeString(file, DELIVERED + "\n");

            var material = Main.provisionedGossipKey(Map.of("AETHER_GOSSIP_KEY_FILE", file.toString())::get).unwrap().unwrap();

            assertThat(material.keyId()).isEqualTo(17);
            assertThat(material.key()).isEqualTo(KEY);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /// A malformed key must stop the boot. Falling back to the derived key would strand the node on a key the rotated cluster rejects, silently,
    /// which is the defect this delivery exists to remove.
    @Test
    void provisionedGossipKey_malformed_isRefused_withoutEchoingTheValue() {
        var resolved = Main.provisionedGossipKey(Map.of("AETHER_GOSSIP_KEY", "17:not-base64-" + ENCODED)::get);

        assertThat(resolved.isFailure()).isTrue();
        resolved.onFailure(cause -> assertThat(cause.message()).doesNotContain(ENCODED));
    }

    @Test
    void provisionedGossipKey_unreadableFile_isRefused_notIgnored() {
        assertThat(Main.provisionedGossipKey(Map.of("AETHER_GOSSIP_KEY_FILE", "/nonexistent/gossip-key")::get).isFailure()).isTrue();
    }

    /// The delivered key is the bundle provider's ONLY gossip key: it is what the SWIM transport and the boot-time reflection probe both encrypt
    /// under, and no derived key is offered beside it.
    @Test
    void resolveTls_deliveredKey_isTheProvidersOnlyGossipKey() {
        var material = GossipKeyMaterial.gossipKeyMaterial(DELIVERED).unwrap();

        var bundle = new Main(new String[0]).resolveTls(NodeId.nodeId("gossip-key-provisioned-test").unwrap(),
                                                        List.of(),
                                                        configWith("gossip-key-test-cluster-secret"),
                                                        Option.none(),
                                                        Option.some(material))
                                            .unwrap();
        var gossipKey = bundle.provider().currentGossipKey().unwrap();

        assertThat(gossipKey.keyId()).isEqualTo(17);
        assertThat(gossipKey.key()).isEqualTo(KEY);
        assertThat(bundle.provider().previousGossipKey().isEmpty()).as("the derived key is never accepted beside it").isTrue();
        assertThat(bundle.provider().nextGossipKey().isEmpty()).isTrue();
    }

    /// Control: with no delivered key the same call yields the cluster-secret-derived key, so the assertion above can fail.
    @Test
    void resolveTls_noDeliveredKey_keepsTheDerivedKey() {
        var bundle = new Main(new String[0]).resolveTls(NodeId.nodeId("gossip-key-derived-test").unwrap(),
                                                        List.of(),
                                                        configWith("gossip-key-test-cluster-secret"),
                                                        Option.none(),
                                                        Option.none())
                                            .unwrap();
        var gossipKey = bundle.provider().currentGossipKey().unwrap();

        assertThat(gossipKey.keyId()).isNotEqualTo(17);
        assertThat(gossipKey.key()).isNotEqualTo(KEY);
    }

    private static Option<AetherConfig> configWith(String clusterSecret) {
        return Option.some(AetherConfig.aetherConfig(ClusterConfig.clusterConfig(Environment.DOCKER),
                                                     NodeConfig.nodeConfig(Environment.DOCKER),
                                                     Option.some(TlsConfig.tlsConfig(clusterSecret)),
                                                     Option.none(),
                                                     Option.none(),
                                                     TtmConfig.ttmConfig(),
                                                     SliceConfig.sliceConfig(),
                                                     AppHttpConfig.appHttpConfig(),
                                                     BackupConfig.backupConfig(Environment.DOCKER),
                                                     DhtReplicationConfig.dhtReplicationConfig(),
                                                     TimeoutsConfig.timeoutsConfig()).unwrap());
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        Arrays.fill(key, value);

        return key;
    }
}
