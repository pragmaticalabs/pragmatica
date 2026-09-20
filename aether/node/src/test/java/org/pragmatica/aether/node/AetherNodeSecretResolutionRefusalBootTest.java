// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #904: an operator configured a resource provider AND the secrets it needs cannot be resolved.
/// Before this pin `AetherNode.createResourceProviderFacade` logged the failure and booted the node
/// on `noOpResourceProviderFacade()` with an empty `nodeComposite` -- provisioning silently
/// disabled, every `ConfigurationSection` slice failing later with a cause that blamed the operator
/// for "not configuring" a provider they had configured. The #830 rule: a configured capability
/// that cannot be built is a boot failure naming its cause, never a degraded boot.
///
/// Pinned through the REAL boot path, like `AetherNodeArtifactsPlaintextRefusalBootTest`: the branch
/// fires inside `AetherNode.assembleNode`, so the config follows
/// `AetherNodeContentStorageWarnBootTest#minimalConfig`'s real-assembly shape.
class AetherNodeSecretResolutionRefusalBootTest {
    private static final String SECRET_PATH = "vault/db/password";
    private static final String CONFIG_KEY = "database.password";

    private AetherNode node;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        // Process-wide singletons set by AetherNode.createResourceProviderFacade on its populated branch.
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_refusesBoot_whenConfiguredProviderSecretsCannotBeResolved() {
        SecretsProvider failing = path -> Causes.cause("secrets backend unreachable: " + path).promise();
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(CONFIG_KEY, "${secrets:" + SECRET_PATH + "}"))
                                                  .build();
        var environment = Option.some(EnvironmentIntegration.environmentIntegration(Option.none(),
                                                                                    Option.some(failing),
                                                                                    Option.none()));
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(environment, Option.none(), configProvider, tempDir);

        AetherNode.aetherNode(config, () -> {})
                  .onSuccess(booted -> {
                      node = booted;
                      fail("#904: boot must REFUSE when the configured resource provider's secrets cannot be "
                           + "resolved -- it booted with provisioning silently disabled instead");
                  })
                  .onFailure(cause -> {
                      assertThat(cause.message()).as("the refusal attributes the failure to secret resolution, "
                                                     + "naming the config key and the secret path")
                                                 .contains("secret")
                                                 .contains(CONFIG_KEY)
                                                 .contains(SECRET_PATH);
                      assertThat(cause.message()).as("and it must NOT blame the operator for not configuring a "
                                                     + "provider they did configure")
                                                 .doesNotContain("not configured");
                      assertThat(cause.source().isPresent()).as("the underlying ConfigError is carried as the source")
                                                            .isTrue();
                      assertThat(cause.source().unwrap()).isInstanceOf(ConfigError.SecretResolutionFailed.class);
                  });
    }

    /// #904 round 2 (SF-1): the SIBLING arm -- a `${secrets:...}` placeholder in the configured
    /// provider and NO `SecretsProvider` at all. Before this pin the placeholder passed through
    /// literally (`ConfigService.getString("database.password")` answered
    /// `${secrets:vault/db/password}`) and the node booted; the failure surfaced later as an auth
    /// error naming nothing about secrets. Reachable in production through `Main.resolveEnvironment`'s
    /// log-and-`Option.none()` and through a node.toml without `[cloud]` -- this test takes exactly
    /// that shape (`environment` absent). Same rule as `NoSecretsProviderForStorageEncryption`.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_refusesBoot_whenPlaceholderPresentAndNoSecretsProviderConfigured() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(CONFIG_KEY, "${secrets:" + SECRET_PATH + "}"))
                                                  .build();
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(), Option.none(), configProvider, tempDir);

        AetherNode.aetherNode(config, () -> {})
                  .onSuccess(booted -> {
                      node = booted;
                      fail("#904: boot must REFUSE when a ${secrets:...} placeholder has no SecretsProvider to "
                           + "resolve it -- it booted with the placeholder passed through as the literal value instead");
                  })
                  .onFailure(cause -> {
                      assertThat(cause.message()).as("the refusal names the config key, the secret path, and the "
                                                     + "missing secrets provider as the reason")
                                                 .contains("secret")
                                                 .contains(CONFIG_KEY)
                                                 .contains(SECRET_PATH)
                                                 .contains("no secrets provider");
                      assertThat(cause.source().isPresent()).as("the underlying ConfigError is carried as the source")
                                                            .isTrue();
                      assertThat(cause.source().unwrap()).isInstanceOf(ConfigError.SecretResolutionFailed.class);
                  });
    }

    /// Control for the arm above, in the shape Ember/Forge boot with: `EnvironmentIntegration`
    /// present but its `secrets()` empty, and a provider carrying NO placeholder. That must keep
    /// booting, and the literal value must be served unchanged -- the refusal is keyed on a
    /// placeholder being present, never on the mere absence of a `SecretsProvider`.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_boots_whenNoSecretsProviderAndNoPlaceholder() {
        var literal = "plain-literal-value";
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(CONFIG_KEY, literal))
                                                  .build();
        var environment = Option.some(EnvironmentIntegration.environmentIntegration(Option.none(),
                                                                                    Option.none(),
                                                                                    Option.none()));
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(environment, Option.none(), configProvider, tempDir);

        AetherNode.aetherNode(config, () -> {})
                  .onFailure(cause -> fail("control: a provider with no ${secrets:...} placeholder and no "
                                           + "SecretsProvider must still boot, but it refused: " + cause.message()))
                  .onSuccess(booted -> {
                      node = booted;
                      assertThat(ConfigService.instance().flatMap(service -> service.getString(CONFIG_KEY)))
                          .as("the literal value is served unchanged through the node composite")
                          .isEqualTo(Option.some(literal));
                  });
    }
}
