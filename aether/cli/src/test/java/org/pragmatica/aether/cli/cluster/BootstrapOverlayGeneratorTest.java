/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
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
    void overlay_dockerSetsClusterAndNodeAndCloudCompute() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");
        var peers = List.of("node-1:host-a:6000", "node-2:host-b:6001", "node-3:host-c:6002");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    peers,
                                                    some("api-key-xyz"),
                                                    some("999"),
                                                    empty());

        assertEquals("test-cluster", doc.getString("cluster", "name").unwrap());
        assertEquals("node-1:host-a:6000,node-2:host-b:6001,node-3:host-c:6002",
                     doc.getString("cluster", "peers").unwrap());
        assertEquals("node-1", doc.getString("node", "id").unwrap());
        assertEquals("core", doc.getString("node", "role").unwrap());
        assertEquals("api-key-xyz", doc.getString("cloud.compute", "api_key").unwrap());
        assertEquals("999", doc.getString("cloud.compute", "docker_gid").unwrap());
        assertEquals(5160, doc.getInt("cloud.compute", "management_port_base").unwrap());
        assertEquals(8070, doc.getInt("cloud.compute", "app_port_base").unwrap());
        assertEquals(6000, doc.getInt("cloud.compute", "cluster_port").unwrap());
        assertEquals("/var/run/docker.sock", doc.getString("cloud.compute", "socket_path").unwrap());
    }

    @Test
    void overlay_dockerOmitsApiKeyAndGidWhenNotProvided() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    "node-2",
                                                    1,
                                                    NodeRole.CORE,
                                                    List.of(),
                                                    empty(),
                                                    empty(),
                                                    empty());

        assertTrue(doc.getString("cloud.compute", "api_key").isEmpty());
        assertTrue(doc.getString("cloud.compute", "docker_gid").isEmpty());
        assertEquals(6001, doc.getInt("cloud.compute", "cluster_port").unwrap());
    }

    @Test
    void overlay_cloudSetsProviderRegionZoneAndTls() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of("node-1:1.2.3.4:6000"),
                                                    empty(),
                                                    empty(),
                                                    some("seed-secret"));

        assertEquals("hetzner", doc.getString("cloud.compute", "provider").unwrap());
        assertEquals("eu-central", doc.getString("cloud.compute", "region").unwrap());
        assertEquals("fsn1", doc.getString("node", "zone").unwrap());
        assertEquals("seed-secret", doc.getString("tls", "cluster_secret").unwrap());
    }

    @Test
    void overlay_cloudOmitsTlsWhenSecretAbsent() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
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
                                                    "node-1",
                                                    0,
                                                    NodeRole.CORE,
                                                    List.of(),
                                                    empty(),
                                                    empty(),
                                                    empty());

        assertFalse(doc.hasSection("cloud.compute"));
        assertFalse(doc.hasSection("tls"));
        // cluster + node still always present
        assertEquals("forge-cluster", doc.getString("cluster", "name").unwrap());
        assertEquals("node-1", doc.getString("node", "id").unwrap());
    }

    @Test
    void overlay_workerRoleSurfacedAsString() {
        var config = ClusterBootstrapConfigParser.parse(DOCKER_BASE).unwrap();
        var source = config.sources().get("dev");

        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    source,
                                                    "node-w1",
                                                    5,
                                                    NodeRole.WORKER,
                                                    List.of(),
                                                    empty(),
                                                    empty(),
                                                    empty());

        assertEquals("worker", doc.getString("node", "role").unwrap());
    }
}
