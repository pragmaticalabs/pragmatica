// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.cluster.CloudCredentialSchema;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.ReplacementNodeConfigComposer;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #2059 — a cloud source composes a node TOML its provider's REAL integration factory accepts, and a source
/// missing a credential key is refused at compose time with the key named.
///
/// Nothing here hands the factory a TOML literal: the source definition goes through the real
/// [ClusterBootstrapConfigParser] and the real composers ([NodeConfigBuilder], [ReplacementNodeConfigComposer]);
/// the composed document is written out and read back by the real [ConfigLoader] (what the node does at boot);
/// its `[cloud]` section is then handed to [EnvironmentIntegrationFactory#createFromConfig].
class CloudCredentialComposeTest {
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\\nMIIEvQIBADANBgkq\\n-----END PRIVATE KEY-----";

    /// Per provider: the source-level fields, then the `node_config.cloud.credentials` entries.
    private static final Map<String, Map<String, String>> SOURCE_FIELDS = Map.of(
        "hetzner", Map.of("credentials", "hcloud-token"),
        "aws", Map.of("region", "eu-west-1"),
        "gcp", Map.of("zone", "europe-west1-b"),
        "azure", Map.of("region", "westeurope"));

    private static final Map<String, Map<String, String>> NODE_CREDENTIALS = Map.of(
        "hetzner", Map.of(),
        "aws", Map.of("access_key_id", "AKIAEXAMPLE", "secret_access_key", "secret"),
        "gcp", Map.of("project_id", "proj", "service_account_email", "sa@proj.iam", "private_key_pem", PEM),
        "azure", Map.of("tenant_id", "t", "client_id", "c", "client_secret", "s", "subscription_id", "sub", "resource_group", "rg"));

    @Test
    void everyProvider_withFullCredentials_composesNodeTomlTheRealFactoryAccepts() {
        for (var provider : SOURCE_FIELDS.keySet()) {
            assertThat(created(provider, fieldsOf(provider), nodeCredentialsOf(provider)))
                .as("%s: integration created from the composed node TOML", provider)
                .isEqualTo(Option.some(provider));
        }
    }

    @Test
    void everyProvider_replacementComposerAlsoYieldsTomlTheRealFactoryAccepts() {
        for (var provider : SOURCE_FIELDS.keySet()) {
            var config = parse(provider, fieldsOf(provider), nodeCredentialsOf(provider));
            var source = config.sources().get("s");
            var composed = ReplacementNodeConfigComposer.compose(config, source, NodeRole.CORE, Option.none(), List.of());

            assertThat(integrationProvider(composed)).as("%s: CTM replacement path", provider).isEqualTo(Option.some(provider));
        }
    }

    @Test
    void everyProvider_missingAnyRequiredKey_isRefusedAtComposeTimeNamingTheKey() {
        for (var provider : SOURCE_FIELDS.keySet()) {
            for (var key : CloudCredentialSchema.requiredKeys(provider)) {
                var fields = new LinkedHashMap<>(fieldsOf(provider));
                var credentials = new LinkedHashMap<>(nodeCredentialsOf(provider));

                fields.remove(sourceFieldFor(key));
                credentials.remove(key);
                var composed = compose(provider, fields, credentials);

                assertThat(composed.isFailure()).as("%s without %s is refused", provider, key).isTrue();
                composed.onFailure(cause -> assertThat(cause.message()).contains(key).contains(provider));
            }
        }
    }

    @Test
    void everyProvider_replacementComposerRefusesAMissingKey_namingIt() {
        for (var provider : SOURCE_FIELDS.keySet()) {
            var key = CloudCredentialSchema.requiredKeys(provider).getFirst();
            var fields = new LinkedHashMap<>(fieldsOf(provider));
            var credentials = new LinkedHashMap<>(nodeCredentialsOf(provider));

            fields.remove(sourceFieldFor(key));
            credentials.remove(key);
            var config = parse(provider, fields, credentials);
            var composed = ReplacementNodeConfigComposer.compose(config, config.sources().get("s"), NodeRole.CORE, Option.none(), List.of());

            assertThat(composed.isFailure()).as("%s replacement without %s is refused", provider, key).isTrue();
            composed.onFailure(cause -> assertThat(cause.message()).contains(key));
        }
    }

    @Test
    void everyProvider_missingKey_failsValidationBeforeAnyNodeIsProvisioned() {
        for (var provider : SOURCE_FIELDS.keySet()) {
            var key = CloudCredentialSchema.requiredKeys(provider).getFirst();
            var fields = new LinkedHashMap<>(fieldsOf(provider));
            var credentials = new LinkedHashMap<>(nodeCredentialsOf(provider));

            fields.remove(sourceFieldFor(key));
            credentials.remove(key);

            assertThat(ClusterBootstrapConfigValidator.validate(parse(provider, fields, credentials)).isFailure())
                .as("%s: validate refuses a source without %s", provider, key)
                .isTrue();
        }
    }

    @Test
    void everyProvider_fullCredentials_passValidation() {
        for (var provider : SOURCE_FIELDS.keySet()) {
            var validated = ClusterBootstrapConfigValidator.validate(parse(provider, fieldsOf(provider), nodeCredentialsOf(provider)));

            assertThat(validated.isSuccess())
                .as("%s: a fully credentialed source validates (%s)", provider, validated.fold(Cause::message, config -> "ok"))
                .isTrue();
        }
    }

    @Test
    void aws_scalarCredentials_isNotMisreadAsAnAccessKey() {
        var fields = new LinkedHashMap<>(fieldsOf("aws"));

        fields.put("credentials", "just-one-string");
        var credentials = new LinkedHashMap<>(nodeCredentialsOf("aws"));

        credentials.remove("access_key_id");

        assertThat(compose("aws", fields, credentials).isFailure()).as("one scalar cannot stand in for access_key_id").isTrue();
    }

    private static Option<String> created(String provider, Map<String, String> fields, Map<String, String> credentials) {
        return integrationProvider(compose(provider, fields, credentials));
    }

    private static Option<String> integrationProvider(Result<TomlDocument> composed) {
        var loaded = composed.map(TomlWriter::toToml)
                             .flatMap(ConfigLoader::loadFromString)
                             .flatMap(config -> config.cloud().toResult(org.pragmatica.lang.utils.Causes.cause("no [cloud] in composed TOML")))
                             .flatMap(EnvironmentIntegrationFactory::createFromConfig);

        return loaded.fold(cause -> Option.none(), integration -> Option.some(providerOf(composed)));
    }

    private static String providerOf(Result<TomlDocument> composed) {
        return composed.unwrap().getString("cloud", "provider").unwrap();
    }

    private static Result<TomlDocument> compose(String provider, Map<String, String> fields, Map<String, String> credentials) {
        var config = parse(provider, fields, credentials);
        var ctx = BootstrapContext.bootstrapContext(config,
                                                    BootstrapState.initialState(config.cluster().name(), "h", "now"),
                                                    List.of(),
                                                    List.of());

        return NodeConfigBuilder.compose(ctx, config.sources().get("s"), 0, NodeRole.CORE, Option.none(), Option.some("secret"));
    }

    private static ClusterBootstrapConfig parse(String provider, Map<String, String> fields, Map<String, String> credentials) {
        var toml = new StringBuilder("""
            config_version = "1.0.0"

            [cluster]
            name = "c2059"
            version = "1.0.0"

            [source.s]
            type = "cloud"
            """);

        toml.append("provider = \"").append(provider).append("\"\n");
        fields.forEach((key, value) -> toml.append(key).append(" = \"").append(value).append("\"\n"));
        toml.append("\n[source.s.core]\ncount = 3\ninstance_type = \"t\"\nimage = \"i\"\n");
        toml.append("\n[runtime.default]\ntype = \"container\"\nimage = \"ghcr.io/pragmaticalabs/aether-node:1.0.0\"\n");
        if (!credentials.isEmpty()) {
            toml.append("\n[source.s.node_config.cloud.credentials]\n");
            credentials.forEach((key, value) -> toml.append(key).append(" = \"").append(value).append("\"\n"));
        }

        return ClusterBootstrapConfigParser.parse(toml.toString()).unwrap();
    }

    private static Map<String, String> fieldsOf(String provider) {
        return SOURCE_FIELDS.get(provider);
    }

    private static Map<String, String> nodeCredentialsOf(String provider) {
        return NODE_CREDENTIALS.get(provider);
    }

    /// The source-level field that supplies a credential key, when it is not written under `node_config`.
    private static String sourceFieldFor(String key) {
        return switch (key) {
            case "api_token" -> "credentials";
            case "zone" -> "zone";
            case "region", "location" -> "region";
            default -> "";
        };
    }
}
