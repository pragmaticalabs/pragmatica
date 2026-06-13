// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapPhaseProvisionStampsSourcesTest {

    private static final String RAW_TOML = """
        [cluster]
        name = "prod"

        [sources.hetzner-eu]
        type = "cloud"
        provider = "hetzner"
        credentials = "${env:HCLOUD_TOKEN_PROD}"
        region = "eu-central"

        [sources.hetzner-eu.roles.core]
        count = 3
        """;

    private static SourceProfile cloudHetznerSource() {
        return SourceProfile.sourceProfile("hetzner-eu",
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.some("resolved-token-value"),
                                           Option.some("eu-central"),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    @Test
    void stampSourceHandle_recordsProviderRegionAndApiTokenEnvVarName() {
        var initial = BootstrapState.initialState("prod", "h", "now");
        var source = cloudHetznerSource();

        var stamped = BootstrapPhaseProvision.stampSourceHandle(initial,
                                                                 RAW_TOML,
                                                                 "hetzner-eu",
                                                                 source,
                                                                 "hetzner");

        var handle = stamped.sources().get("hetzner-eu");
        assertNotNull(handle, "Source handle must be stamped for cloud sources");
        assertEquals("hetzner", handle.provider(),
                     "Provider must be the resolved cloud provider name (hetzner), not the source type 'cloud'");
        assertEquals("eu-central", handle.region().or(""),
                     "Region must be lifted from SourceProfile.region()");
        assertEquals("HCLOUD_TOKEN_PROD",
                     handle.credentialEnvVars().get("api_token"),
                     "credentialEnvVars must record the env-var NAME the operator wrote in the TOML, "
                     + "not the resolved token value (secret stays out of the persisted state)");
    }

    @Test
    void stampSourceHandle_recordsEnvVarUnderAllAliases_soDownstreamFactoryCanReadAny() {
        // `ProviderResolver.buildCloudConfig` echoes the credential value under
        // api_token / access_key / credentials_file because the per-provider factory
        // picks the alias it expects. The stamped handle must do the same.
        var initial = BootstrapState.initialState("prod", "h", "now");
        var source = cloudHetznerSource();

        var stamped = BootstrapPhaseProvision.stampSourceHandle(initial, RAW_TOML, "hetzner-eu", source, "hetzner");
        var envVars = stamped.sources().get("hetzner-eu").credentialEnvVars();

        assertEquals("HCLOUD_TOKEN_PROD", envVars.get("api_token"));
        assertEquals("HCLOUD_TOKEN_PROD", envVars.get("access_key"));
        assertEquals("HCLOUD_TOKEN_PROD", envVars.get("credentials_file"));
    }

    @Test
    void stampSourceHandle_doesNotPersistResolvedSecret() {
        // The persisted handle's credentialEnvVars values must look like env-var names,
        // never the secret value. This is the load-bearing safety contract.
        var initial = BootstrapState.initialState("prod", "h", "now");
        var source = cloudHetznerSource();

        var stamped = BootstrapPhaseProvision.stampSourceHandle(initial, RAW_TOML, "hetzner-eu", source, "hetzner");
        var envVars = stamped.sources().get("hetzner-eu").credentialEnvVars();

        for (var value : envVars.values()) {
            assertFalse(value.equals("resolved-token-value"),
                         "MUST NOT persist the resolved secret value");
            assertTrue(value.matches("^[A-Z_][A-Z0-9_]*$"),
                        "Persisted value must be an env-var name (uppercase identifier), got: " + value);
        }
    }

    @Test
    void stampSourceHandle_skipsNonCloudSources() {
        // Docker / SSH / Forge sources have no env-var credentials worth recording for
        // cleanup; the legacy fallback path handles them.
        var docker = SourceProfile.sourceProfile("local",
                                                  SourceType.DOCKER,
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  LoadBalancerMode.NONE,
                                                  List.of(),
                                                  Option.empty(),
                                                  Map.of(),
                                                  Map.of(),
                                                  List.of());

        var stamped = BootstrapPhaseProvision.stampSourceHandle(BootstrapState.initialState("p", "h", "n"),
                                                                 RAW_TOML,
                                                                 "local",
                                                                 docker,
                                                                 "docker");

        assertNull(stamped.sources().get("local"),
                    "Docker source must not produce a cleanup handle (legacy path handles cleanup)");
    }

    @Test
    void extractEnvVarNames_findsCredentialPattern_inNamedSourceStanza() {
        var envVars = BootstrapPhaseProvision.extractEnvVarNames(RAW_TOML, "hetzner-eu");

        assertEquals("HCLOUD_TOKEN_PROD", envVars.get("api_token"),
                     "Env-var name in the named stanza must be recovered");
    }

    @Test
    void extractEnvVarNames_returnsEmpty_whenStanzaMissing() {
        var envVars = BootstrapPhaseProvision.extractEnvVarNames(RAW_TOML, "missing-source");

        assertTrue(envVars.isEmpty(),
                   "Unknown source name must yield empty env-var map (no spurious matches)");
    }

    @Test
    void extractEnvVarNames_returnsEmpty_whenRawTomlNullOrBlank() {
        assertTrue(BootstrapPhaseProvision.extractEnvVarNames(null, "x").isEmpty());
        assertTrue(BootstrapPhaseProvision.extractEnvVarNames("", "x").isEmpty());
    }

    @Test
    void extractEnvVarNames_doesNotCrossStanzaBoundaries() {
        // If two sources are present, mining for source A must NOT match source B's
        // credentials line.
        var multiSource = """
            [sources.alpha]
            type = "cloud"
            provider = "hetzner"
            credentials = "${env:TOKEN_A}"

            [sources.beta]
            type = "cloud"
            provider = "aws"
            credentials = "${env:TOKEN_B}"
            """;

        var alphaVars = BootstrapPhaseProvision.extractEnvVarNames(multiSource, "alpha");
        var betaVars = BootstrapPhaseProvision.extractEnvVarNames(multiSource, "beta");

        assertEquals("TOKEN_A", alphaVars.get("api_token"));
        assertEquals("TOKEN_B", betaVars.get("api_token"));
    }
}
