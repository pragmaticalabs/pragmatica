// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CloudCredentialSchema;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #2059 — [CloudCredentialSchema#requiredKeys] is pinned against the REAL provider factories (the source of
/// truth), in both directions: the full key set is accepted, and dropping any single listed key is refused as
/// missing credentials. A key the schema omits that a factory requires would fail the first; a key the schema
/// lists that no factory requires would fail the second.
class CloudCredentialSchemaTest {
    private static final List<String> PROVIDERS = List.of("hetzner", "aws", "gcp", "azure");

    @Test
    void requiredKeys_allPresent_factoryAccepts() {
        for (var provider : PROVIDERS) {
            assertThat(create(provider, full(provider)).isSuccess())
                .as("%s accepts exactly the schema's keys", provider)
                .isTrue();
        }
    }

    @Test
    void requiredKeys_droppingAnyOne_factoryRefusesAsMissingCredentials() {
        for (var provider : PROVIDERS) {
            for (var key : CloudCredentialSchema.requiredKeys(provider)) {
                var credentials = full(provider);

                credentials.remove(key);

                var created = create(provider, credentials);

                assertThat(created.isFailure()).as("%s without %s", provider, key).isTrue();
                created.onFailure(cause -> assertThat(cause).as("%s without %s", provider, key).isInstanceOf(EnvironmentError.CredentialsMissing.class));
            }
        }
    }

    private static Map<String, String> full(String provider) {
        var credentials = new HashMap<String, String>();

        CloudCredentialSchema.requiredKeys(provider).forEach(key -> credentials.put(key, "value-of-" + key));

        return credentials;
    }

    private static Result<?> create(String provider, Map<String, String> credentials) {
        return CloudConfig.cloudConfig(provider, credentials, Map.of()).flatMap(EnvironmentIntegrationFactory::createFromConfig);
    }
}
