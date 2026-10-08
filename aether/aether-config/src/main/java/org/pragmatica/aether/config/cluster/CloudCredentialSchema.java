// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The `[cloud.credentials]` keys each provider's `*EnvironmentIntegrationFactory.validateCredentials`
/// requires, and the one derivation of a source's credential map from its definition (#2059).
///
/// The factories are the source of truth for [#requiredKeys]: a key listed here that a factory does not
/// read is dead weight, and a key a factory reads that is missing here reaches boot as a fail-closed
/// refusal (#2058) instead of a compose-time error that names it. `CloudCredentialSchemaTest` pins the
/// lists against the real factories.
///
/// A source supplies provider-specific keys in `[source.<name>.node_config.cloud.credentials]`; the
/// source's `region` / `zone` fill `region` (aws), `zone` (gcp) and `location` (azure). The scalar
/// `credentials` field is one value, so it can only be the hetzner `api_token`.
public interface CloudCredentialSchema {
    static List<String> requiredKeys(String provider) {
        return switch (provider) {
            case "hetzner" -> List.of("api_token");
            case "aws" -> List.of("access_key_id", "secret_access_key", "region");
            case "gcp" -> List.of("project_id", "service_account_email", "private_key_pem", "zone");
            case "azure" -> List.of("tenant_id",
                                    "client_id",
                                    "client_secret",
                                    "subscription_id",
                                    "resource_group",
                                    "location");
            default -> List.of();
        };
    }

    /// The credential map a node of `source` is configured with: the operator's
    /// `node_config` `[cloud.credentials]`, overlaid with the scalar `credentials` and the source location.
    static Map<String, String> credentials(SourceProfile source, String provider) {
        var credentials = new HashMap<String, String>(source.nodeConfig()
                                                            .map(config -> config.getSection("cloud.credentials"))
                                                            .or(Map.of()));

        if (provider.equals("hetzner")) {
            source.credentials()
                  .filter(value -> !value.isBlank())
                  .onPresent(value -> credentials.put("api_token", value));
        }

        applyLocation(credentials, source, provider);

        return Map.copyOf(credentials);
    }

    /// Refuses a cloud source whose credential map lacks a key its provider's factory requires, naming each.
    static Result<SourceProfile> validate(SourceProfile source) {
        return source.type() == SourceType.CLOUD
               ? source.provider()
                       .map(provider -> check(source,
                                              provider.value()))
                       .or(Result.success(source))
               : Result.success(source);
    }

    private static Result<SourceProfile> check(SourceProfile source, String provider) {
        var credentials = credentials(source, provider);
        var missing = requiredKeys(provider).stream()
                                  .filter(key -> credentials.getOrDefault(key, "")
                                                            .isBlank())
                                  .toList();

        return missing.isEmpty()
               ? Result.success(source)
               : new ClusterConfigError.CredentialsIncomplete(source.name().value(),
                                                              provider,
                                                              missing).result();
    }

    private static void applyLocation(Map<String, String> credentials, SourceProfile source, String provider) {
        switch (provider) {
            case "aws" -> source.region().onPresent(value -> credentials.put("region", value));
            case "gcp" -> source.zone().orElse(Option.from(source.effectiveZones().stream().findFirst())).onPresent(value -> credentials.put("zone",
                                                                                                                                             value));
            case "azure" -> source.region().onPresent(value -> credentials.put("location", value));
            default -> {}
        }
    }
}
