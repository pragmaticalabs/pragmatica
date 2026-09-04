// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #253 owner condition 5: boot REFUSES rather than silently starting unencrypted when
/// `[storage.encryption]` is configured but its keyring cannot be resolved.
///
/// Scope is deliberately the FAST-FAIL paths only. `resolveStorageEncryptionKeyring` is private and
/// reachable only through the public `AetherNode.aetherNode(config)` entry point, which runs
/// `config.validate()` and then `createNode` -- where the keyring resolution is the literal first
/// statement, before any port is bound, thread started, or component constructed. So each test here
/// returns a synchronous `Result.failure` with nothing allocated and nothing to clean up, and no
/// `@AfterEach` shutdown is needed. A SUCCESSFUL boot is out of scope: it would bind real ports and
/// start real threads, which no test in this module does.
class AetherNodeStorageEncryptionBootTest {

    /// Contents are irrelevant to tests 1 and 2 -- both fail before any key reference is read. Test 3
    /// supplies its own config because there the reference shape is the thing under test.
    private static final Option<StorageEncryptionConfig> ENCRYPTION_CONFIGURED =
            Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of("k1", "${secrets:path/to/k1}"), "k1", false));

    /// The simplest `AetherNodeConfig` that passes `validate()`: `managementPort` is
    /// `MANAGEMENT_DISABLED`, which is exactly the condition under which `validate()` skips its
    /// "at least one core node required" check (see `AetherNodeConfig#validate`), so an empty
    /// `coreNodes` list is legal here. Every other stage takes the same static-factory default the
    /// in-process host (`EmberCluster`) uses. Nothing in this config is reachable on the path under
    /// test -- the keyring check runs before any of it is consumed.
    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                                   Option<StorageEncryptionConfig> storageEncryption) {
        return AetherNodeConfig.builder()
                                .self(NodeId.nodeId("storage-encryption-boot-test").unwrap())
                                .coreNodes(List.of())
                                .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig())
                                .tls(Option.none())
                                .quicTls(TlsConfig.selfSignedServer())
                                .certificateProvider(Option.none())
                                .configProvider(Option.none())
                                .environment(environment)
                                .build()
                                .withStorageEncryption(storageEncryption);
    }

    private static Option<EnvironmentIntegration> environmentWith(Option<SecretsProvider> secrets) {
        return Option.some(EnvironmentIntegration.environmentIntegration(Option.none(), secrets, Option.none()));
    }

    @Test
    void aetherNode_failsFast_whenStorageEncryptionConfiguredWithNoEnvironmentAtAll() {
        AetherNode.aetherNode(minimalConfig(Option.none(), ENCRYPTION_CONFIGURED))
                   .onSuccess(_ -> fail("boot must refuse: storage encryption is configured with no environment integration"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(AetherNode.NoSecretsProviderForStorageEncryption.class));
    }

    /// A DIFFERENT path through the same fold than the test above: the environment is present, so
    /// `config.environment()` is non-empty and only `.flatMap(EnvironmentIntegration::secrets)`
    /// collapses it. A guard written as an `environment().isEmpty()` check alone would pass test 1
    /// and fail here.
    @Test
    void aetherNode_failsFast_whenEnvironmentPresentButProvidesNoSecrets() {
        AetherNode.aetherNode(minimalConfig(environmentWith(Option.none()), ENCRYPTION_CONFIGURED))
                   .onSuccess(_ -> fail("boot must refuse: the environment integration provides no SecretsProvider"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(AetherNode.NoSecretsProviderForStorageEncryption.class));
    }

    /// With a `SecretsProvider` actually present the presence check is satisfied, so the only way to
    /// reach a `MalformedSecretRef` is for `AetherNode` to have delegated to
    /// `StorageEncryption.resolveKeyring` -- which is what this pins. Asserting the specific cause
    /// type matters: a bare `isFailure()` would also be satisfied by the presence check still firing.
    @Test
    void aetherNode_failsFast_whenSecretsProviderPresentButKeyRefMalformed() {
        SecretsProvider provider = _ -> Promise.success("irrelevant");
        var malformed = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of("k1", "not-a-secrets-ref"),
                                                                                     "k1",
                                                                                     false));

        AetherNode.aetherNode(minimalConfig(environmentWith(Option.some(provider)), malformed))
                   .onSuccess(_ -> fail("boot must refuse: the configured key is not a '${secrets:<path>}' reference"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StorageEncryption.MalformedSecretRef.class));
    }
}
