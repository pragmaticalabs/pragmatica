// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// #336 REGRESSION GATE — the unresolved-`${env:}` CTM provisioning defect and its position-based
/// resolution fix.
///
/// At bootstrap, `ClusterBootstrapCommand.parseFromRaw` resolves `${env:...}` placeholders to build
/// the parsed config, but persists the ORIGINAL UNRESOLVED raw TOML to KV (`new ParsedConfig(config,
/// raw)` — `raw`, not the resolved string; then `BootstrapPhaseFormation.storeClusterConfig`
/// persists `ctx.rawTomlContent()`). This is INTENTIONAL per spec REQ-KV-03.
///
/// Later, the leader's `ClusterTopologyManager` provisions scale-up / auto-heal replacement nodes by
/// re-parsing that PERSISTED UNRESOLVED TOML and composing node config WITH NO `${env:}`
/// re-resolution of its own:
///   `ClusterTopologyManagerRecord#renderReplacementUserData` → `renderFromToml`
///   → `ClusterBootstrapConfigParser.parse(toml)` → `renderFromConfig` → `renderFromSource`
///   → `ReplacementNodeConfigComposer.compose(config, source, secret)` + `NodeUserDataRenderer.render`.
///
/// The CLI `ConfigReferenceResolver` is a package-private type in `aether/cli`, structurally
/// unreachable from `aether-config` / `aether-deployment`. So WITHOUT the fix a replacement's
/// composed config carries LITERAL `${env:PG_HOST}` / `${env:PG_USER}` / `${env:PG_PASSWORD}` and
/// the node breaks at startup.
///
/// THE FIX ([PlaceholderConfigResolver]): the leader runs with its OWN per-node overlay RESOLVED to
/// literals (same compose pipeline, rendered by the CLI from the resolved config). Both documents
/// share the SAME TOML structure, so for every composed leaf still holding a placeholder the
/// resolver substitutes the resolved source's value at the SAME TOML PATH — generic (every ref) and
/// position-based (the leader cannot resolve by var-name, those are lost once the raw TOML is
/// persisted). It writes NOTHING to KV.
///
/// [Characterization] retains the CASE proving the UNRESOLVED path (no resolved source) STILL leaks
/// placeholders — the leak the fix exists to close. [Fixed] proves that, given a resolvedSource
/// `TomlDocument` whose `[database]` creds and `async_url` are resolved to literals, the resolver
/// produces an overlay with NO `${env:}` / `${secrets:}` placeholders and the literal values present
/// at those paths — both at the composer level and through the rendered cloud-init user-data.
class UnresolvedEnvReplacementProvisioningTest {

    private static final String SOURCE_NAME = "hetzner-eu";

    private static final String RESOLVED_PG_USER = "aether_app";
    private static final String RESOLVED_PG_PASSWORD = "s3cr3t-resolved-pw";
    private static final String RESOLVED_PG_HOST = "10.0.0.42";
    private static final String RESOLVED_PG_DB = "aether";
    private static final String RESOLVED_ASYNC_URL = "postgresql://" + RESOLVED_PG_HOST + ":5432/" + RESOLVED_PG_DB;

    /// The headline REQ-4.2.7 cloud credential (`${env:HCLOUD_TOKEN}`) rendered to its literal — the
    /// value the leader's RESOLVED overlay carries at `[source.hetzner-eu].credentials`, which
    /// BootstrapOverlayGenerator composes into the flat `cloud.credentials` section under `api_token`.
    private static final String RESOLVED_HCLOUD_TOKEN = "hcloud-resolved-token";
    private static final String UNRESOLVED_HCLOUD_TOKEN = "${env:HCLOUD_TOKEN}";

    /// Minimal cloud TOML modeled on cloud-hetzner-b.toml — `${env:...}` placeholders in the
    /// node_config.database section, parsed WITHOUT ConfigReferenceResolver (exactly as the CTM
    /// re-parses the persisted raw TOML).
    private static final String CLOUD_TOML = """
        config_version = "1.0.0"

        [cluster]
        name = "cloud-test-b"
        version = "1.0.0"

        [source.hetzner-eu]
        type = "cloud"
        provider = "hetzner"
        region = "nbg1"
        credentials = "${env:HCLOUD_TOKEN}"

        [source.hetzner-eu.core]
        count = 5
        instance_type = "cx33"
        image = "ubuntu-22.04"

        [source.hetzner-eu.node_config.database]
        username = "${env:PG_USER}"
        password = "${env:PG_PASSWORD}"
        async_url = "postgresql://${env:PG_HOST}:5432/${env:PG_DB}"

        [runtime.default]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc2-candidate"
        """;

    /// The leader's RESOLVED counterpart of [#CLOUD_TOML]: byte-identical STRUCTURE, but the
    /// `${env:...}` placeholders rendered to literals — exactly what the CLI wrote into the leader's
    /// own per-node overlay at bootstrap. Composed through the SAME pipeline so its leaf paths align
    /// position-for-position with the unresolved overlay the CTM hands the resolver.
    private static final String RESOLVED_CLOUD_TOML = """
        config_version = "1.0.0"

        [cluster]
        name = "cloud-test-b"
        version = "1.0.0"

        [source.hetzner-eu]
        type = "cloud"
        provider = "hetzner"
        region = "nbg1"
        credentials = "hcloud-resolved-token"

        [source.hetzner-eu.core]
        count = 5
        instance_type = "cx33"
        image = "ubuntu-22.04"

        [source.hetzner-eu.node_config.database]
        username = "%s"
        password = "%s"
        async_url = "%s"

        [runtime.default]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc2-candidate"
        """.formatted(RESOLVED_PG_USER, RESOLVED_PG_PASSWORD, RESOLVED_ASYNC_URL);

    @Nested
    class Characterization {

        /// PROVES the #336 leak at the composer level: WITHOUT a resolved source the merged config the
        /// CTM hands the renderer still holds the literal `${env:PG_PASSWORD}` (and the other refs)
        /// because compose runs no `${env:}` resolution. This is the bug the fix exists to close.
        @Test
        void compose_persistedUnresolvedToml_leaksLiteralEnvPlaceholders() {
            ClusterBootstrapConfigParser.parse(CLOUD_TOML)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(this::assertComposeLeaksPlaceholders);
        }

        private void assertComposeLeaksPlaceholders(ClusterBootstrapConfig config) {
            var source = config.sources().get(SOURCE_NAME);

            ReplacementNodeConfigComposer.compose(config, source, Option.none())
                                         .onFailure(cause -> Assertions.fail(cause.message()))
                                         .onSuccess(UnresolvedEnvReplacementProvisioningTest::assertComposedDatabaseUnresolved);
        }

        /// PROVES the #336 leak at the renderer level WITHOUT the fix: the cloud-init user-data a
        /// provisioned node would actually receive (heredoc'd aether.toml) carries the literal
        /// `${env:...}` text — the single-quoted heredoc delimiter performs no shell expansion either,
        /// so the broken placeholders land verbatim on the VM's disk.
        @Test
        void render_persistedUnresolvedToml_leaksLiteralEnvPlaceholdersInUserData() {
            ClusterBootstrapConfigParser.parse(CLOUD_TOML)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> assertRenderLeaksPlaceholders(config, compose(config)));
        }

        private void assertRenderLeaksPlaceholders(ClusterBootstrapConfig config, TomlDocument composed) {
            var userData = renderUserData(config, composed);

            assertThat(userData)
                .as("rendered cloud-init user-data carries the unresolved ${env:} secret (#336 leak)")
                .contains("${env:PG_PASSWORD}");
            assertThat(userData)
                .as("rendered cloud-init user-data carries the unresolved ${env:} host (#336 leak)")
                .contains("${env:PG_HOST}")
                .contains("${env:PG_USER}")
                .contains("${env:PG_DB}");
        }
    }

    @Nested
    class Fixed {

        /// #336 FIX, composer level: resolving the composed (placeholder) overlay against the leader's
        /// RESOLVED source overlay at the same TOML path yields an overlay with NO `${env:}`
        /// placeholders and the leader's literal credentials present at the database paths.
        @Test
        void resolve_composedAgainstResolvedSource_substitutesLiteralsAtSamePath() {
            resolvePair()
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(UnresolvedEnvReplacementProvisioningTest::assertDatabaseResolved);
        }

        /// #336 FIX, renderer level: the cloud-init user-data rendered from the RESOLVED overlay
        /// carries NO `${env:}` / `${secrets:}` placeholders and embeds the leader's literal
        /// credentials — what a CTM-provisioned replacement now boots with, instead of crashing on
        /// placeholders and never joining.
        @Test
        void render_resolvedOverlay_carriesNoPlaceholdersAndEmbedsLiterals() {
            ClusterBootstrapConfigParser.parse(CLOUD_TOML)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> resolvePair().onFailure(cause -> Assertions.fail(cause.message()))
                                                  .onSuccess(resolved -> assertRenderResolved(config, resolved)));
        }

        private void assertRenderResolved(ClusterBootstrapConfig config, TomlDocument resolved) {
            var userData = renderUserData(config, resolved);

            assertThat(userData)
                .as("rendered cloud-init user-data carries NO ${env:} placeholders after #336 resolution")
                .doesNotContain("${env:")
                .doesNotContain("${secrets:");
            assertThat(userData)
                .as("rendered cloud-init user-data embeds the leader's resolved literal credentials (#336)")
                .contains(RESOLVED_PG_USER)
                .contains(RESOLVED_PG_PASSWORD)
                .contains(RESOLVED_ASYNC_URL);
            assertThat(userData)
                .as("rendered cloud-init user-data embeds the leader's resolved cloud credential, not the placeholder (#336 — REQ-4.2.7)")
                .contains(RESOLVED_HCLOUD_TOKEN)
                .doesNotContain(UNRESOLVED_HCLOUD_TOKEN);
        }

        /// #336 — when NO resolved counterpart exists for a placeholder path the resolver leaves the
        /// placeholder in place rather than throwing or blanking it (best-effort, prior behavior
        /// preserved for that leaf). Modeled with an EMPTY resolved source.
        @Test
        void resolve_missingSourcePath_leavesPlaceholderInPlace() {
            ClusterBootstrapConfigParser.parse(CLOUD_TOML)
                .flatMap(config -> ReplacementNodeConfigComposer.compose(config, config.sources().get(SOURCE_NAME), Option.none()))
                .flatMap(composed -> PlaceholderConfigResolver.resolve(composed, TomlDocument.EMPTY))
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(UnresolvedEnvReplacementProvisioningTest::assertComposedDatabaseUnresolved);
        }
    }

    /// Compose the UNRESOLVED overlay (placeholders intact) and the leader's RESOLVED overlay
    /// (literals) through the SAME pipeline, then run the position-based [PlaceholderConfigResolver]
    /// — the exact pair the CTM render path now feeds the resolver.
    private static Result<TomlDocument> resolvePair() {
        return ClusterBootstrapConfigParser.parse(CLOUD_TOML)
            .flatMap(config -> ReplacementNodeConfigComposer.compose(config, config.sources().get(SOURCE_NAME), Option.none()))
            .flatMap(composed -> ClusterBootstrapConfigParser.parse(RESOLVED_CLOUD_TOML)
                                                             .flatMap(resolvedConfig -> ReplacementNodeConfigComposer.compose(resolvedConfig,
                                                                                                                              resolvedConfig.sources().get(SOURCE_NAME),
                                                                                                                              Option.none()))
                                                             .flatMap(resolvedSource -> PlaceholderConfigResolver.resolve(composed, resolvedSource)));
    }

    private static TomlDocument compose(ClusterBootstrapConfig config) {
        return ReplacementNodeConfigComposer.compose(config, config.sources().get(SOURCE_NAME), Option.none())
                                            .onFailure(cause -> Assertions.fail(cause.message()))
                                            .or(TomlDocument.EMPTY);
    }

    private static String renderUserData(ClusterBootstrapConfig config, TomlDocument overlay) {
        return NodeUserDataRenderer.render(config,
                                           config.sources().get(SOURCE_NAME),
                                           NodeRole.CORE,
                                           "node-replacement-0",
                                           0,
                                           "",
                                           config.cluster().name(),
                                           overlay,
                                           List.of(),
                                           List.of());
    }

    /// The composed `TomlDocument` is the merge of DefaultNodeConfig defaults + the source's
    /// `node_config` operator override + the bootstrap overlay. The database fields originate from
    /// the unresolved `[source.X.node_config.database]` table, so they MUST read back as the
    /// literal placeholder strings — confirming no `${env:}` resolution happened. The cloud
    /// credential (`[source.X].credentials`, composed by BootstrapOverlayGenerator into the flat
    /// `cloud.credentials` section under `api_token`) leaks the SAME way — REQ-4.2.7's headline
    /// secret.
    private static void assertComposedDatabaseUnresolved(TomlDocument composed) {
        assertThat(composed.getString("database", "password"))
            .as("composed [database].password retains literal ${env:PG_PASSWORD} (#336 leak)")
            .isEqualTo(Option.some("${env:PG_PASSWORD}"));
        assertThat(composed.getString("database", "username"))
            .as("composed [database].username retains literal ${env:PG_USER} (#336 leak)")
            .isEqualTo(Option.some("${env:PG_USER}"));
        assertThat(composed.getString("database", "async_url").or(""))
            .as("composed [database].async_url retains literal ${env:PG_HOST}/${env:PG_DB} (#336 leak)")
            .contains("${env:PG_HOST}")
            .contains("${env:PG_DB}");
        assertThat(composed.getString("cloud.credentials", "api_token"))
            .as("composed cloud.credentials.api_token retains literal ${env:HCLOUD_TOKEN} (#336 leak — REQ-4.2.7)")
            .isEqualTo(Option.some(UNRESOLVED_HCLOUD_TOKEN));
    }

    /// After #336 resolution the database fields read back as the leader's RESOLVED literals at the
    /// SAME paths — and NO `${env:}` placeholder survives anywhere in those leaves. The cloud
    /// credential at `cloud.credentials.api_token` is resolved the same way (REQ-4.2.7), proving the
    /// position-based resolver fixes the headline cloud secret, not just `[database]`.
    private static void assertDatabaseResolved(TomlDocument resolved) {
        assertThat(resolved.getString("database", "password"))
            .as("resolved [database].password is the leader's literal (#336)")
            .isEqualTo(Option.some(RESOLVED_PG_PASSWORD));
        assertThat(resolved.getString("database", "username"))
            .as("resolved [database].username is the leader's literal (#336)")
            .isEqualTo(Option.some(RESOLVED_PG_USER));
        assertThat(resolved.getString("database", "async_url"))
            .as("resolved [database].async_url is the leader's literal (#336)")
            .isEqualTo(Option.some(RESOLVED_ASYNC_URL));
        assertThat(resolved.getString("database", "password").or(""))
            .as("resolved [database].password carries no leftover ${env:} (#336)")
            .doesNotContain("${env:");
        assertThat(resolved.getString("database", "async_url").or(""))
            .as("resolved [database].async_url carries no leftover ${env:} (#336)")
            .doesNotContain("${env:");
        assertThat(resolved.getString("cloud.credentials", "api_token"))
            .as("resolved cloud.credentials.api_token is the leader's literal token (#336 — REQ-4.2.7)")
            .isEqualTo(Option.some(RESOLVED_HCLOUD_TOKEN));
        assertThat(resolved.getString("cloud.credentials", "api_token").or(""))
            .as("resolved cloud.credentials.api_token carries no leftover ${env: (#336 — REQ-4.2.7)")
            .doesNotContain("${env:");
    }
}
