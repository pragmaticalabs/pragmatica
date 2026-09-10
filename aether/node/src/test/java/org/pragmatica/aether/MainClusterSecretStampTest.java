// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #980 — pins `Main`'s cluster-secret stamp, the line that puts the secret where
/// `BootstrapAdminKeyLeg` can derive the bootstrap admin key from it.
///
/// It was measured unpinned: replacing the stamped value with `Option.empty()` left all 1,299
/// `aether/node` tests green. The whole feature rested on a line nothing defended — a refactor of it
/// would pass CI and silently return cloud bootstrap to the `401` that #980 exists to fix.
///
/// **Scope, stated because it is narrower than it looks.** This pins the stamp's VALUE — that the
/// secret handed to the node config is the one the operator configured, and the same one the
/// certificate path uses. It does NOT pin the CALL to [Main#withResolvedClusterSecret] from `run()`;
/// deleting that line is still silent here. `run()` is the process entry point and is not drivable
/// in-JVM. `EmberBootstrapAdminKeyAuthTest` covers the equivalent link for the in-process path by
/// end-to-end authentication; for `Main` itself the residual gap closes only under a real node boot.
class MainClusterSecretStampTest {
    private static final String CONFIGURED_SECRET = "main-stamp-test-cluster-secret";
    /// The environment arm, stated explicitly. No test in this class reads the ambient environment.
    private static final Option<String> NO_ENV_SECRET = Option.none();

    /// THE regression. The stamped value must be the configured secret, not absent and not something
    /// else — the leg derives the cluster's ADMIN credential from exactly this.
    @Test
    void withResolvedClusterSecret_configuredSecret_isStampedOntoTheNodeConfig() {
        var stamped = Main.withResolvedClusterSecret(minimalConfig(), configWith(CONFIGURED_SECRET), NO_ENV_SECRET);

        assertThat(stamped.clusterSecret().isPresent())
            .describedAs("without the stamp the leg falls back to a random key and `aether cluster "
                         + "bootstrap` cannot re-derive it — the #980 defect")
            .isTrue();
        assertThat(stamped.clusterSecret().unwrap()).isEqualTo(CONFIGURED_SECRET);
    }

    /// The property the `Main` refactor exists to guarantee: the admin key and the CA/gossip material
    /// are derived from the SAME secret. Both read through `resolveClusterSecretValue`; re-inlining a
    /// separate reader into either path reddens this.
    @Test
    void withResolvedClusterSecret_agreesWithTheSecretTheCertificatePathUses() {
        var stamped = Main.withResolvedClusterSecret(minimalConfig(), configWith(CONFIGURED_SECRET), NO_ENV_SECRET);
        var certificatePathSecret = new String(Main.resolveClusterSecret(TlsConfig.tlsConfig(CONFIGURED_SECRET),
                                                                         NO_ENV_SECRET).unwrap(),
                                               StandardCharsets.UTF_8);

        assertThat(stamped.clusterSecret().unwrap())
            .describedAs("a node must never derive its certificate from one secret and its admin key "
                         + "from another")
            .isEqualTo(certificatePathSecret);
    }

    /// A blank configured secret must stay ABSENT rather than becoming `""`. An empty-string secret
    /// would be derived from, handing every such cluster the same publicly-computable ADMIN key.
    @Test
    void withResolvedClusterSecret_noSecretConfiguredAnywhere_leavesItAbsent() {
        var stamped = Main.withResolvedClusterSecret(minimalConfig(), configWith(""), NO_ENV_SECRET);

        assertThat(stamped.clusterSecret().isEmpty())
            .describedAs("a blank secret must not be stamped as an empty string")
            .isTrue();
    }

    /// Verifies — rather than asserts — the claim that `BootstrapAdminKeyLeg`'s random-key fallback is
    /// unreachable in production. `run()` calls exactly this method and `.expect`s the result, so a
    /// failure here aborts the boot before any `AetherNodeConfig` is stamped and before the leg can
    /// ever be built. Weakening that gate reddens this test.
    ///
    /// What this does NOT cover: that `run()` still `.expect`s it. That is a source-ordering fact
    /// (`Main.run`, the `resolveTls(...).expect(...)` line, ahead of the config assembly), not
    /// something reachable in-JVM.
    @Test
    void resolveTls_noClusterSecretAnywhere_failsSoTheNodeCannotBoot() {
        var result = new Main(new String[0]).resolveTls(NodeId.nodeId("no-secret-boot-gate-test").unwrap(),
                                                        List.of(),
                                                        configWith(""),
                                                        NO_ENV_SECRET);

        assertThat(result.isFailure())
            .describedAs("a node with no cluster secret must not boot; if it could, it would reach "
                         + "BootstrapAdminKeyLeg with an absent secret and mint a random ADMIN key the "
                         + "bootstrap CLI can never derive")
            .isTrue();
    }

    /// The same gate must PASS when a secret is configured — otherwise the test above would be
    /// satisfied by a `resolveTls` that fails for any reason at all, which is not what it claims.
    @Test
    void resolveTls_clusterSecretConfigured_succeeds() {
        var result = new Main(new String[0]).resolveTls(NodeId.nodeId("secret-present-boot-gate-test").unwrap(),
                                                        List.of(),
                                                        configWith(CONFIGURED_SECRET),
                                                        NO_ENV_SECRET);

        assertThat(result.isSuccess())
            .describedAs("positive control: with a secret configured the gate must let the boot proceed")
            .isTrue();
    }

    /// The `AETHER_CLUSTER_SECRET` arm, now stated rather than inherited. Before SF4 this source was
    /// read from the ambient environment, which forced the two tests above to be `assumeTrue`-guarded
    /// — and a skip reads as green, on the very assertion carrying the "the boot path is closed"
    /// argument. Injecting the source removes the guard AND lets the fallback arm be tested at all,
    /// which it never was.
    @Test
    void resolveClusterSecretValue_noConfiguredSecret_fallsBackToTheEnvironmentSource() {
        var stamped = Main.withResolvedClusterSecret(minimalConfig(),
                                                     configWith(""),
                                                     Option.some("secret-from-the-environment"));

        assertThat(stamped.clusterSecret().unwrap()).isEqualTo("secret-from-the-environment");
    }

    /// Precedence: an explicitly configured `[tls] cluster_secret` wins over the environment.
    @Test
    void resolveClusterSecretValue_configuredSecret_winsOverTheEnvironmentSource() {
        var stamped = Main.withResolvedClusterSecret(minimalConfig(),
                                                     configWith(CONFIGURED_SECRET),
                                                     Option.some("secret-from-the-environment"));

        assertThat(stamped.clusterSecret().unwrap()).isEqualTo(CONFIGURED_SECRET);
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

    /// Same shape as `AetherNodeStorageEncryptionBootTest#minimalConfig` — nothing here is on the path
    /// under test; the stamp reads only the `AetherConfig` argument.
    private static AetherNodeConfig minimalConfig() {
        return AetherNodeConfig.builder()
                               .self(NodeId.nodeId("main-cluster-secret-stamp-test").unwrap())
                               .coreNodes(List.of())
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(org.pragmatica.net.tcp.TlsConfig.selfSignedServer())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .build();
    }
}
