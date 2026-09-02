// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.BootstrapOverlayGenerator;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;


class BootstrapOverlayGeneratorTest {

    private static final String DOCKER_BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "test-cluster"
            version = "1.0.0"

            [operations.ports]
            management = 5160
            cluster = 6000
            app_http = 8070

            [source.dev]
            type = "docker"

            [source.dev.core]
            count = 3
            """;

    private static final String CLOUD_BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            zone = "fsn1"

            [source.eu-1.core]
            count = 3
            """;

    @Test
    void overlay_dockerSetsClusterAndCloudCompute() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    some("api-key-xyz"),
                                                    some("999"),
                                                    empty(),
                                                    NodeRole.CORE);

        assertEquals("test-cluster", doc.getString("cluster", "name").unwrap());
        assertEquals("api-key-xyz", doc.getString("cloud.compute", "api_key").unwrap());
        assertEquals("999", doc.getString("cloud.compute", "docker_gid").unwrap());
        assertEquals(5160, doc.getInt("cloud.compute", "management_port_base").unwrap());
        assertEquals(8070, doc.getInt("cloud.compute", "app_port_base").unwrap());
        assertEquals(6000, doc.getInt("cloud.compute", "cluster_port").unwrap());
        assertEquals("/var/run/docker.sock", doc.getString("cloud.compute", "socket_path").unwrap());
    }

    @Test
    void overlay_emitsClusterPortsSoMainParsePortReadsOperatorValues() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertEquals(5160, doc.getInt("cluster.ports", "management").unwrap(),
                     "cluster.ports.management must surface operator port so Main.parseManagementPort " +
                     "(via ConfigLoader.portsFromDocument) reads it");
        assertEquals(6000, doc.getInt("cluster.ports", "cluster").unwrap(),
                     "cluster.ports.cluster must surface operator port for the same reason");
    }

    @Test
    void overlay_omitsNodeBlockEntirely() {
        // [node] does not exist in Main's TOML schema (AetherConfig has no
        // [node].id / [node].role / [node].zone). Per-node identity flows via
        // env vars and CLI flags emitted by UserDataTemplate.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("node"),
                    "[node] block must NOT be emitted — Main never reads it and we'd be lying about schema");
    }

    @Test
    void overlay_omitsClusterPeersField() {
        // Main.parsePeers reads --peers= flag or CLUSTER_PEERS env var, never
        // [cluster].peers. Emitting it would be dead config that misleads operators.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertTrue(doc.getString("cluster", "peers").isEmpty(),
                   "[cluster].peers must NOT be emitted — Main has no such schema field");
    }

    @Test
    void overlay_dockerOmitsApiKeyAndGidWhenNotProvided() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    1,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertTrue(doc.getString("cloud.compute", "api_key").isEmpty());
        assertTrue(doc.getString("cloud.compute", "docker_gid").isEmpty());
        assertEquals(6001, doc.getInt("cloud.compute", "cluster_port").unwrap());
    }

    @Test
    void overlay_cloudSetsProviderRegionAndTls() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        // Top-level [cloud] provider is what ConfigLoader.populateCloudConfig reads.
        // Without it, lifecycleManager.isCloudManaged() is false and runtime auto-scale fails.
        assertEquals("hetzner", doc.getString("cloud", "provider").unwrap());
        assertTrue(doc.getString("cloud.compute", "provider").isEmpty(),
                   "provider belongs in [cloud], not [cloud.compute]");
        assertEquals("eu-central", doc.getString("cloud.compute", "region").unwrap());
        assertEquals("seed-secret", doc.getString("tls", "cluster_secret").unwrap());
    }

    @Test
    void overlay_cloudPropagatesCredentialsForRuntimeAutoScale() {
        var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"
                credentials = "secret-token"

                [source.eu-1.core]
                count = 3
                instance_type = "cx33"
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertEquals("secret-token", doc.getString("cloud.credentials", "api_token").unwrap(),
                     "cloud.credentials.api_token must propagate so runtime CTM can authenticate when " +
                     "auto-provisioning new nodes during /api/cluster/scale");
        assertEquals("cx33", doc.getString("cloud.compute", "server_type").unwrap(),
                     "cloud.compute.server_type must come from the CORE role's instance_type");
    }

    @Test
    void overlay_cloudRendersImageFromCoreRole() {
        // #459 — the CORE role's [source...] image (VM snapshot id) must be rendered into the node
        // overlay's [cloud.compute] image, so a running leader's config.image() carries it and both
        // bootstrap seeds AND CTM auto-heal replacements boot from the operator's prepared snapshot
        // rather than the provider's hardcoded default.
        var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"

                [source.eu-1.core]
                count = 3
                image = "174523891"
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertEquals("174523891", doc.getString("cloud.compute", "image").unwrap(),
                     "cloud.compute.image must come from the CORE role's [source...] image so replacements inherit it");
    }

    @Test
    void overlay_cloudOmitsImageWhenCoreRoleHasNone() {
        // No [source...] image → the overlay omits [cloud.compute] image entirely, so config.image()
        // stays empty and the provider's loud hardcoded default applies (rather than an empty image
        // reaching the create request).
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertTrue(doc.getString("cloud.compute", "image").isEmpty(),
                   "image must be omitted when the CORE role sets none");
    }

    private static final String CLOUD_MULTI_ROLE_IMAGES = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            image = "core-snap-1"

            [source.eu-1.worker]
            count = 2
            image = "worker-snap-2"

            [source.eu-1.spot]
            count = 1
            """;

    @Test
    void overlay_cloudRendersWorkerImageForWorkerNode() {
        // RFC-0016 W2 — a WORKER node's overlay must carry the WORKER role's own [source...] image,
        // so a worker leader's config.image() (tier-2) re-provisions from the worker's snapshot.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_MULTI_ROLE_IMAGES).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.WORKER);

        assertEquals("worker-snap-2", doc.getString("cloud.compute", "image").unwrap(),
                     "cloud.compute.image must come from the WORKER role's own image, not the core's");
    }

    @Test
    void overlay_cloudRendersCoreImageForCoreNode_notSiblingWorkerImage() {
        // RFC-0016 W2 — a CORE node's overlay must carry the CORE role's own image even when a worker
        // sibling declares a different one (no cross-role leakage between per-role overlays).
        var config = ClusterBootstrapConfigParser.parse(CLOUD_MULTI_ROLE_IMAGES).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertEquals("core-snap-1", doc.getString("cloud.compute", "image").unwrap(),
                     "cloud.compute.image must come from the CORE role's own image, not the worker sibling's");
    }

    @Test
    void overlay_cloudOmitsImageForRoleWithoutOwnImage_evenWhenSiblingHasOne() {
        // RFC-0016 W2 — no cross-role fallback: the SPOT role sets no image, so its overlay omits
        // [cloud.compute] image entirely even though core/worker siblings declare one. The provider's
        // loud default then applies for spot nodes rather than a sibling's snapshot.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_MULTI_ROLE_IMAGES).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.SPOT);

        assertTrue(doc.getString("cloud.compute", "image").isEmpty(),
                   "image must be omitted for a role that sets none, never inherited from a sibling role");
    }

    @Test
    void overlay_cloudOmitsCredentialsWhenAbsent() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("cloud.credentials"));
    }

    @Test
    void overlay_dockerOmitsTopLevelCloudSection() {
        // Docker source-type defaults (aether-docker.toml) supply `[cloud] provider = "docker"`,
        // so the overlay must NOT emit a top-level [cloud] for docker — the source-type default is authoritative.
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("cloud"),
                    "docker overlay must not emit top-level [cloud] — comes from aether-docker.toml default");
        assertFalse(doc.hasSection("cloud.credentials"));
    }

    @Test
    void overlay_cloudOmitsTlsWhenSecretAbsent() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("tls"));
    }

    @Test
    void overlay_emitsAsyncUrlForNativeProtocolDatabase() {
        var toml = DOCKER_BASE + """

                [source.dev.databases]
                main = "postgresql://forge:forge@db:5432/forge"
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertEquals("postgresql://forge:forge@db:5432/forge",
                     doc.getString("database.main", "async_url").unwrap());
    }

    @Test
    void overlay_emitsJdbcUrlForJdbcSchemeDatabase() {
        var toml = DOCKER_BASE + """

                [source.dev.databases]
                legacy = "jdbc:oracle:thin:@//oracle.corp:1521/LEGACY"
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertEquals("jdbc:oracle:thin:@//oracle.corp:1521/LEGACY",
                     doc.getString("database.legacy", "jdbc_url").unwrap());
        assertTrue(doc.getString("database.legacy", "async_url").isEmpty());
    }

    @Test
    void overlay_emitsMultipleDatabaseSectionsOnePerDeclaration() {
        var toml = DOCKER_BASE + """

                [source.dev.databases]
                primary = "postgresql://primary:5432/app"
                analytics = "jdbc:postgresql://analytics:5432/dw"
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertEquals("postgresql://primary:5432/app", doc.getString("database.primary", "async_url").unwrap());
        assertEquals("jdbc:postgresql://analytics:5432/dw", doc.getString("database.analytics", "jdbc_url").unwrap());
    }

    @Test
    void overlay_preservesPlaceholdersInDatabaseUrl() {
        var toml = DOCKER_BASE + """

                [source.dev.databases]
                main = "postgresql://${secrets:db-user}:${secrets:db-pass}@${env:DB_HOST}/app"
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertEquals("postgresql://${secrets:db-user}:${secrets:db-pass}@${env:DB_HOST}/app",
                     doc.getString("database.main", "async_url").unwrap());
    }

    @Test
    void overlay_omitsDatabaseSectionsWhenNoneDeclared() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("database"));
        assertFalse(doc.hasSection("database.main"));
    }

    @Test
    void overlay_sshSourceOmitsCloudComputeAndTlsSections() {
        var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "ssh-cluster"
                version = "1.0.0"

                [source.bare]
                type = "ssh"

                [source.bare.core]
                count = 1
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("bare");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("ignored-secret"),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("cloud.compute"));
        assertFalse(doc.hasSection("tls"));
    }

    @Test
    void overlay_cloudRendersSshKeyIdsCommaJoinedWhenThreaded() {
        // #442 — the resolved operator SSH key ids must land in [cloud.compute] ssh_key_ids as a
        // comma-joined SCALAR (not a TOML array), because the node reads the section via
        // TomlDocument.getSection which stringifies values and HetznerEnvironmentIntegrationFactory
        // splits on ",". This is what lets a leader resolve keys from config (no API lookup) and
        // provision replacements WITH the key, closing the PAM wall.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE,
                                                    List.of(113681412L, 999L));

        assertEquals("113681412,999", doc.getString("cloud.compute", "ssh_key_ids").unwrap(),
                     "ssh_key_ids must be the comma-joined scalar HetznerEnvironmentIntegrationFactory parses");
    }

    @Test
    void overlay_cloudOmitsSshKeyIdsWhenNoneResolved() {
        // Empty resolved ids (keyless bootstrap, or the backward-compatible 6-arg overlay) must
        // omit ssh_key_ids entirely so config.sshKeyIds() stays empty and the provider's
        // name-prefix fallback takes over rather than parsing an empty string.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE,
                                                    List.of());

        assertTrue(doc.getString("cloud.compute", "ssh_key_ids").isEmpty(),
                   "ssh_key_ids must be omitted when no keys were resolved");
    }

    @Test
    void overlay_sixArgOverloadOmitsSshKeyIds() {
        // The legacy 6-arg overload must remain byte-compatible: no ssh_key_ids emitted.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"),
                                                    NodeRole.CORE);

        assertTrue(doc.getString("cloud.compute", "ssh_key_ids").isEmpty(),
                   "6-arg overlay must not emit ssh_key_ids (preserves prior behavior)");
    }

    @Test
    void overlay_dockerIgnoresSshKeyIds() {
        // Docker sources render dockerComputeSection, which has no ssh_key_ids concept; threading
        // ids must never leak into a docker overlay.
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE,
                                                    List.of(1L, 2L));

        assertTrue(doc.getString("cloud.compute", "ssh_key_ids").isEmpty(),
                   "docker overlay must never carry ssh_key_ids");
    }

    @Test
    void overlay_forgeSourceOmitsCloudComputeAndTlsSections() {
        var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "forge-cluster"
                version = "1.0.0"

                [source.dev]
                type = "forge"

                [source.dev.core]
                count = 1
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    0,
                                                    empty(),
                                                    empty(),
                                                    empty(),
                                                    NodeRole.CORE);

        assertFalse(doc.hasSection("cloud.compute"));
        assertFalse(doc.hasSection("tls"));
        // cluster always present
        assertEquals("forge-cluster", doc.getString("cluster", "name").unwrap());
    }
}
