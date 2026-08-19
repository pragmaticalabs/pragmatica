// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class BootstrapPhaseProvisionStampsSourcesTest {

    private static final String SOURCE_NAME = "hetzner-eu";

    /// Section headers are DERIVED from the parser's own `SOURCE_PREFIX`, never re-spelled here. A
    /// hand-written fixture is what let #521 survive a green suite: it invented a plural `[sources.<name>]`
    /// header, so it agreed with the buggy miner while no real config did. Built this way, the fixture
    /// cannot disagree with the grammar.
    private static final String SOURCE_HEADER = sectionHeader(SOURCE_NAME);

    private static final String CORE_ROLE_HEADER = sectionHeader(SOURCE_NAME + ".core");

    private static String sectionHeader(String suffix) {
        return "[" + ClusterBootstrapConfigParser.SOURCE_PREFIX + suffix + "]";
    }

    private static final String RAW_TOML = """
        [cluster]
        name = "prod"

        %s
        type = "cloud"
        provider = "hetzner"
        credentials = "${env:HCLOUD_TOKEN_PROD}"
        region = "eu-central"

        %s
        count = 3
        """.formatted(SOURCE_HEADER, CORE_ROLE_HEADER);

    /// The repo's live Hetzner cluster config — the very artifact `aether cluster bootstrap` is pointed at,
    /// and the shape that stranded five paid VMs on 2026-07-24. Read from disk rather than transcribed, so
    /// the miner is exercised against real operator input and any future grammar drift fails here.
    private static final Path REAL_CLUSTER_CONFIG = Path.of("..",
                                                            "tests",
                                                            "integration",
                                                            "env",
                                                            "cloud-hetzner-jvm.toml");

    private static String realClusterConfig() throws Exception {
        assertTrue(Files.exists(REAL_CLUSTER_CONFIG),
                   "Expected the repo's Hetzner cluster config at " + REAL_CLUSTER_CONFIG.toAbsolutePath()
                   + " — if it moved, re-point this test rather than replacing it with a hand-written fixture");

        return Files.readString(REAL_CLUSTER_CONFIG);
    }

    private static SourceProfile cloudHetznerSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("hetzner-eu"),
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
                                                                 sourceNameOrDefault("hetzner-eu"),
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

        var stamped = BootstrapPhaseProvision.stampSourceHandle(initial, RAW_TOML, sourceNameOrDefault("hetzner-eu"), source, "hetzner");
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

        var stamped = BootstrapPhaseProvision.stampSourceHandle(initial, RAW_TOML, sourceNameOrDefault("hetzner-eu"), source, "hetzner");
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
        var docker = SourceProfile.sourceProfile(sourceNameOrDefault("local"),
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
                                                                 sourceNameOrDefault("local"),
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
            [source.alpha]
            type = "cloud"
            provider = "hetzner"
            credentials = "${env:TOKEN_A}"

            [source.beta]
            type = "cloud"
            provider = "aws"
            credentials = "${env:TOKEN_B}"
            """;

        var alphaVars = BootstrapPhaseProvision.extractEnvVarNames(multiSource, "alpha");
        var betaVars = BootstrapPhaseProvision.extractEnvVarNames(multiSource, "beta");

        assertEquals("TOKEN_A", alphaVars.get("api_token"));
        assertEquals("TOKEN_B", betaVars.get("api_token"));
    }

    // --- #521: mined against the repo's REAL cluster config, not a transcription of it ---

    @Test
    void extractEnvVarNames_recoversCredentialEnvVarName_fromRealRepoClusterConfig() throws Exception {
        var envVars = BootstrapPhaseProvision.extractEnvVarNames(realClusterConfig(), SOURCE_NAME);

        assertFalse(envVars.isEmpty(),
                    "A cloud source declaring credentials = \"${env:HCLOUD_TOKEN}\" MUST yield a non-empty "
                    + "mapping — an empty one is what left every persisted handle unusable for destroy");
        assertEquals("HCLOUD_TOKEN", envVars.get("api_token"),
                     "The env-var NAME the operator wrote in the real config must be recovered");
    }

    @Test
    void stampSourceHandle_recordsNonEmptyCredentialMapping_forRealRepoClusterConfig() throws Exception {
        var stamped = BootstrapPhaseProvision.stampSourceHandle(BootstrapState.initialState("gs-dryrun", "h", "now"),
                                                                 realClusterConfig(),
                                                                 sourceNameOrDefault(SOURCE_NAME),
                                                                 cloudHetznerSource(),
                                                                 "hetzner");

        assertFalse(stamped.sources()
                           .get(SOURCE_NAME)
                           .credentialEnvVars()
                           .isEmpty(),
                    "The single assertion that would have caught #521: a cloud source bootstrapped from a "
                    + "${env:...} credential must stamp a NON-EMPTY credential env-var mapping");
    }

    @Test
    void extractEnvVarNames_findsCredential_whenHeaderCarriesTrailingComment() {
        var withComment = """
            %s   # primary EU pool
            type = "cloud"
            credentials = "${env:HCLOUD_TOKEN}"
            """.formatted(SOURCE_HEADER);

        var envVars = BootstrapPhaseProvision.extractEnvVarNames(withComment, SOURCE_NAME);

        assertEquals("HCLOUD_TOKEN", envVars.get("api_token"),
                     "A TOML table header may carry a trailing comment");
    }

    @Test
    void extractEnvVarNames_ignoresHeaderMentionedInsideComment() {
        var commentedOut = """
            # %s was retired; credentials = "${env:STALE_TOKEN}"

            %s
            type = "cloud"
            credentials = "${env:AWS_TOKEN}"
            """.formatted(SOURCE_HEADER, sectionHeader("aws-us"));

        var envVars = BootstrapPhaseProvision.extractEnvVarNames(commentedOut, SOURCE_NAME);

        assertTrue(envVars.isEmpty(),
                   "A header mentioned inside a comment is not the section — mining it would attribute a "
                   + "stale credential to a source that is not declared");
    }

    @Test
    void stampSourceHandle_credentialMappingSurvivesPersistAndLoad() throws Exception {
        // The acceptance shape of #521: what bootstrap stamps must still be there when a LATER
        // `aether cluster destroy` process reads the state file back off disk.
        var stamped = BootstrapPhaseProvision.stampSourceHandle(BootstrapState.initialState("gs-dryrun", "h", "now"),
                                                                 realClusterConfig(),
                                                                 sourceNameOrDefault(SOURCE_NAME),
                                                                 cloudHetznerSource(),
                                                                 "hetzner");

        var reloaded = BootstrapState.fromJson(stamped.toJson());

        reloaded.onFailure(cause -> fail("state must round-trip through JSON: " + cause.message()))
                .onSuccess(state -> assertEquals("HCLOUD_TOKEN",
                                                 state.sources()
                                                      .get(SOURCE_NAME)
                                                      .credentialEnvVars()
                                                      .get("api_token"),
                                                 "the api_token env-var NAME must survive persist -> load, "
                                                 + "or destroy cannot re-derive the provisioning token"));
    }
}
