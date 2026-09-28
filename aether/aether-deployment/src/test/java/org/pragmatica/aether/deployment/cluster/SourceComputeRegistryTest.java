// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class SourceComputeRegistryTest {
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "cloud"
        provider = "hetzner"
        credentials = "east-token"
        region = "east-region"
        [source.east.core]
        count = 3
        instance_type = "small"
        [source.west]
        type = "cloud"
        provider = "hetzner"
        credentials = "west-token"
        region = "west-region"
        [source.west.worker]
        count = 2
        instance_type = "large"
        """;

    @Test
    void exactSourceProducesOwnCredentialsAndRegionAndUnknownFailsBeforeFactory() {
        var configs = new ArrayList<CloudConfig>();
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value(CONFIG)),
                                                                   config -> {
                                                                       configs.add(config);

                                                                       return Result.success(EnvironmentIntegration.withCompute(new NoopProvider()));
                                                                   });

        assertThat(registry.resolve(SourceName.sourceName("west").unwrap()).isSuccess()).isTrue();
        assertThat(registry.resolve(SourceName.sourceName("east").unwrap()).isSuccess()).isTrue();
        assertThat(registry.resolve(SourceName.sourceName("west").unwrap()).isSuccess()).isTrue();
        assertThat(registry.resolve(SourceName.sourceName("unknown").unwrap()).isFailure()).isTrue();
        assertThat(configs).hasSize(2);
        assertThat(configs.get(0).credentials()).containsEntry("api_token", "west-token");
        assertThat(configs.get(0).compute()).containsEntry("region", "west-region");
        assertThat(configs.get(1).credentials()).containsEntry("api_token", "east-token");
        assertThat(configs.get(1).compute()).containsEntry("region", "east-region");
    }

    @Test
    void missingCommittedConfigurationDoesNotUseLocalBootProvider() {
        var registry = SourceComputeRegistry.sourceComputeRegistry(Option::none,
                                                                   _ -> {
                                                                       org.assertj.core.api.Assertions.fail("Factory must not be invoked");

                                                                       return EnvironmentError.operationNotSupported("unreachable").result();
                                                                   });

        assertThat(registry.resolve(SourceName.DEFAULT).isFailure()).isTrue();
    }

    @Test
    void unresolvedCredentialNeverReachesFactory() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value(CONFIG.replace("west-token",
                                                                                                          "${env:AETHER_TEST_SOURCE_UNSET_49183}"))),
                                                                   config -> {
                                                                       calls.incrementAndGet();

                                                                       return Result.success(EnvironmentIntegration.withCompute(new NoopProvider()));
                                                                   });

        assertThat(registry.resolve(SourceName.sourceName("west").unwrap()).isFailure()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void bindingIsStableAcrossRoleSizingAndChangesWithAccountReference() {
        var original = org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser.parse(CONFIG)
                                                                                        .unwrap()
                                                                                        .sources()
                                                                                        .get("west");
        var resized = org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser.parse(CONFIG.replace("large",
                                                                                                             "larger"))
                                                                                       .unwrap()
                                                                                       .sources()
                                                                                       .get("west");
        var rebound = org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser.parse(CONFIG.replace("west-token",
                                                                                                             "other-token"))
                                                                                       .unwrap()
                                                                                       .sources()
                                                                                       .get("west");
        var binding = SourceComputeRegistry.sourceBinding(original).unwrap();

        assertThat(binding).hasSize(64).doesNotContain("west-token");
        assertThat(SourceComputeRegistry.sourceBinding(resized).unwrap()).isEqualTo(binding);
        assertThat(SourceComputeRegistry.sourceBinding(rebound).unwrap()).isNotEqualTo(binding);
    }

    @Test
    void fleetInventorySelectsOnlySourcesDeclaringRequestedRole() {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value(CONFIG)));

        assertThat(registry.sources(java.util.Map.of("aether-role", "worker")).unwrap()).containsExactly(SourceName.sourceName("west").unwrap());
        assertThat(registry.sources(java.util.Map.of("aether-role", "core")).unwrap()).containsExactly(SourceName.sourceName("east").unwrap());
    }

    @Test
    void durableBindingIncludesResolvedProtectedAccountWithoutConstructingProvider() {
        var first = protectedRegistry("account-one");
        var second = protectedRegistry("account-two");
        var source = SourceName.sourceName("west").unwrap();

        assertThat(first.binding(source).unwrap()).hasSize(64).isNotEqualTo(second.binding(source).unwrap());
    }

    @Test
    void boundResolutionRejectsChangedAccountBeforeCreatingProvider() {
        var current = new java.util.concurrent.atomic.AtomicReference<>(value(CONFIG));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(current.get()), config -> {
            calls.incrementAndGet();
            return Result.success(EnvironmentIntegration.withCompute(new NoopProvider()));
        });
        var source = SourceName.sourceName("west").unwrap();
        var originalBinding = registry.binding(source).unwrap();
        current.set(value(CONFIG.replace("west-token", "different-account")));
        assertThat(registry.resolve(source, originalBinding).isFailure()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(registry.resolve(source, registry.binding(source).unwrap()).isSuccess()).isTrue();
        assertThat(calls).hasValue(1);
    }

    /// #1551: a self-bootstrapped cluster commits only the BootstrapModule seed (`tomlContent=""`). The
    /// unbound siblings (`resolve(source)`, `sources`, `isAvailable`) read that as "no operator config" and
    /// use the local provider; the bound path (`binding`, `resolve(source, binding)`) must agree, or every
    /// scale-up and auto-heal provision fails before reaching a provider.
    @Test
    void binding_bootstrapSeedWithLocalProvider_bindsAndResolvesToTheLocalProvider() {
        var local = new NoopProvider();
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value("")),
                                                                   Option.<ComputeProvider>some(local),
                                                                   _ -> org.pragmatica.lang.utils.Causes.cause("Factory must not run for the local provider")
                                                                                                        .result());
        var binding = registry.binding(SourceName.DEFAULT);

        assertThat(binding.isSuccess()).as("bound path on the bootstrap seed: %s", binding).isTrue();
        assertThat(registry.resolve(SourceName.DEFAULT, binding.unwrap())
                           .unwrap()).isSameAs(local)
                                     .isSameAs(registry.resolve(SourceName.DEFAULT).unwrap());
    }

    /// The seed fallback exists only where the unbound sibling has one: without a local provider the bound
    /// path still refuses rather than inventing a provider.
    @Test
    void binding_bootstrapSeedWithoutLocalProvider_refuses() {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value("")),
                                                                   _ -> org.pragmatica.lang.utils.Causes.cause("Factory must not run")
                                                                                                        .result());

        assertThat(registry.binding(SourceName.DEFAULT).isFailure()).isTrue();
        assertThat(registry.resolve(SourceName.DEFAULT, "local").isFailure()).isTrue();
    }

    /// #1561 (v1561 probe 4, pins `localBound`'s equality): on the seed, only the fixed `local` binding resolves
    /// to the local provider; a reservation bound to anything else — an operator account's binding — refuses
    /// instead of silently re-targeting the local provider.
    @Test
    void resolve_bootstrapSeedWithLocalProvider_foreignBindingRefuses() {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value("")),
                                                                   Option.<ComputeProvider>some(new NoopProvider()),
                                                                   _ -> org.pragmatica.lang.utils.Causes.cause("factory must not run")
                                                                                                        .result());

        assertThat(registry.resolve(SourceName.DEFAULT, "0".repeat(64)).isFailure()).isTrue();
    }

    /// #1561: the refusal on a seeded cluster without a local provider names the actual condition, not an
    /// absent configuration.
    @Test
    void sources_bootstrapSeedWithoutLocalProvider_refusalNamesTheSeedCondition() {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value("   \n\t")),
                                                                   _ -> org.pragmatica.lang.utils.Causes.cause("factory must not run")
                                                                                                        .result());

        assertThat(registry.sources(java.util.Map.of()).fold(org.pragmatica.lang.Cause::message, _ -> "success"))
            .isEqualTo(EnvironmentError.operationNotSupported(SourceComputeRegistry.NO_OPERATOR_SOURCES_NO_LOCAL_PROVIDER).message());
    }

    private static SourceComputeRegistry protectedRegistry(String credential) {
        var source = org.pragmatica.config.source.MapConfigSource.mapConfigSource("test",
                                                                                  java.util.Map.of("cloud.sources.west.provider",
                                                                                                   "hetzner",
                                                                                                   "cloud.sources.west.credentials.api_token",
                                                                                                   credential))
                                                                 .unwrap();
        var provider = org.pragmatica.config.ConfigurationProvider.configurationProvider(source);

        return SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(value(CONFIG)),
                                                           Option.none(),
                                                           Option.some(provider),
                                                           _ -> org.pragmatica.lang.utils.Causes.cause("Factory must not run for binding")
                                                                                                .result());
    }

    private record NoopProvider() implements ComputeProvider {
        @Override
        public org.pragmatica.lang.Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("create").promise();
        }

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> terminate(InstanceId id) {
            return EnvironmentError.operationNotSupported("terminate").promise();
        }

        @Override
        public org.pragmatica.lang.Promise<List<InstanceInfo>> listInstances() {
            return org.pragmatica.lang.Promise.success(List.of());
        }

        @Override
        public org.pragmatica.lang.Promise<InstanceInfo> instanceStatus(InstanceId id) {
            return EnvironmentError.operationNotSupported("status").promise();
        }
    }

    private static ClusterConfigValue value(String toml) {
        return new ClusterConfigValue(toml, "test", "1.0.0", List.of(), 3, 9, "test", 1L, 1L);
    }
}
