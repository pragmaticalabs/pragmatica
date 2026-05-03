// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;

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
                                                    empty());

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
                                                    empty());

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
                                                    some("seed-secret"));

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
                                                    some("seed-secret"));

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
                                                    empty());

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
                                                    some("seed-secret"));

        assertEquals("hetzner", doc.getString("cloud.compute", "provider").unwrap());
        assertEquals("eu-central", doc.getString("cloud.compute", "region").unwrap());
        assertEquals("seed-secret", doc.getString("tls", "cluster_secret").unwrap());
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
                                                    empty());

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
                                                    empty());

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
                                                    empty());

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
                                                    empty());

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
                                                    empty());

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
                                                    empty());

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
                                                    some("ignored-secret"));

        assertFalse(doc.hasSection("cloud.compute"));
        assertFalse(doc.hasSection("tls"));
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
                                                    empty());

        assertFalse(doc.hasSection("cloud.compute"));
        assertFalse(doc.hasSection("tls"));
        // cluster always present
        assertEquals("forge-cluster", doc.getString("cluster", "name").unwrap());
    }
}
