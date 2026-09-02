// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.BootstrapOverlayGenerator;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.config.toml.TomlDocument;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Option.empty;


class UserDataTemplateTest {

    private static final String CLOUD_BASE = """
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
            """;

    @Test
    void render_embedsComposedTomlInWriteFilesBlock() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var overlay = BootstrapOverlayGenerator.overlay(config,
                                                        source,
                                                        0,
                                                        empty(),
                                                        empty(),
                                                        empty(),
                                                        NodeRole.CORE);

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret-xyz",
                                             clusterName("prod-cluster").unwrap(),
                                             overlay);

        assertTrue(script.contains("cat > /opt/aether/config/aether.toml"),
                   "Should write composed config to /opt/aether/config/aether.toml");
        assertTrue(script.contains("[cluster]"), "Should include cluster section from composed TOML");
        assertTrue(script.contains("name = \"prod-cluster\""), "Should include cluster name");
        // Per-node identity is NOT in TOML — it travels via env vars / CLI flags.
        assertTrue(script.contains("AETHER_NODE_ID=\"node-1\""),
                   "Per-node identity must travel via the AETHER_NODE_ID shell variable");
    }

    @Test
    void render_includesContainerRunForContainerRuntime() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var overlay = TomlDocument.EMPTY;

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             overlay);

        assertTrue(script.contains("docker pull"), "Should pull image");
        assertTrue(script.contains("docker run"), "Should run container");
        assertTrue(script.contains("-v /opt/aether/config/aether.toml:/app/aether.toml:ro"),
                   "Must bind-mount composed config FILE over bundled /app/aether.toml so the image's "
                   + "hardcoded ENTRYPOINT --config=/app/aether.toml flag picks up the operator config");
        assertFalse(script.contains("-v /opt/aether/config:/config:ro"),
                    "Must NOT bind-mount the directory at /config — Main.findArg() returns first match "
                    + "and the entrypoint's --config=/app/aether.toml would win, ignoring our config");
        assertFalse(script.contains("--config /config/aether.toml"),
                    "Must NOT pass space-separated --config arg: Main.findArg(\"--config=\") only "
                    + "matches the =-form, so the flag would be silently dropped");
        assertFalse(script.contains("--config=/config/aether.toml"),
                    "Must NOT pass an extra trailing --config arg either: the bundled entrypoint already "
                    + "passes --config=/app/aether.toml first; appending another is dead code");
    }

    @Test
    void render_securesConfigOwnerOnlyAndChownsToContainerUser() {
        // #287: aether.toml carries cluster_secret so it must be owner-only (0600), not 0644. The
        // aether-node Dockerfile runs as user `aether` (uid 1000); the file is chowned to uid 1000 so
        // the read-only bind-mount stays readable to the node process without world-exposing the secret.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        var configWriteIdx = script.indexOf("AETHER_CONFIG\n");
        var chownIdx = script.indexOf("chown 1000:1000 /opt/aether/config/aether.toml");
        var chmodIdx = script.indexOf("chmod 600 /opt/aether/config/aether.toml");
        var dockerRunIdx = script.indexOf("docker run");

        assertTrue(chmodIdx > 0, "Must chmod 600 the composed config (cluster_secret at rest)");
        assertTrue(chownIdx > 0, "Must chown to uid 1000 so the in-container aether user can read it");
        assertTrue(configWriteIdx > 0 && chownIdx > configWriteIdx && chmodIdx > configWriteIdx,
                   "chown/chmod must happen after the heredoc write");
        assertTrue(dockerRunIdx > chmodIdx,
                   "chmod must happen before docker run so the bind-mount sees the secured file");
    }

    @Test
    void render_jvmModeUsesEqualsFormConfigArg() {
        // JVM-mode uses Main.findArg("--config=") which only matches the =-form. The
        // space-separated form was silently ignored, causing the node to fall back to
        // bundled defaults (Bug 13).
        var jvmToml = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [runtime.bare-metal]
                type = "jvm"

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"

                [source.eu-1.core]
                count = 3
                runtime = "bare-metal"
                """;
        var config = ClusterBootstrapConfigParser.parse(jvmToml).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("-jar /opt/aether/aether-node.jar"),
                   "JVM-mode must launch the downloaded jar");
        assertTrue(script.contains("--config=/opt/aether/config/aether.toml"),
                   "JVM-mode must use --config= (=-form) so Main.findArg picks it up");
        assertFalse(script.contains("--config /opt/aether/config/aether.toml"),
                    "JVM-mode must NOT use space-separated --config: Main.findArg(\"--config=\") "
                    + "would silently ignore it and the node would fall back to bundled defaults");
        assertFalse(script.contains("docker run"), "JVM-mode must not invoke docker run");
    }

    @Test
    void render_threadsPerNodeIdentityViaContainerEnvVars() {
        // Bug 14: per-node identity (NODE_ID, CLUSTER_PORT, MANAGEMENT_PORT, PEERS,
        // AETHER_CLUSTER_SECRET) MUST be exported into the container via -e flags
        // because Main.java reads them via env (NODE_ID/CLUSTER_PORT/MANAGEMENT_PORT/
        // CLUSTER_PEERS) or via the bundled Dockerfile entrypoint that converts PEERS
        // → --peers=. The bind-mounted aether.toml carries operator state only.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret-xyz",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("-e NODE_ID=\"${AETHER_NODE_ID}\""),
                   "docker run must export NODE_ID so Main.parseNodeId picks up the per-node id");
        assertTrue(script.contains("-e CLUSTER_PORT=\"${AETHER_CLUSTER_PORT}\""),
                   "docker run must export CLUSTER_PORT so the node binds the operator-supplied port");
        assertTrue(script.contains("-e MANAGEMENT_PORT=\"${AETHER_MANAGEMENT_PORT}\""),
                   "docker run must export MANAGEMENT_PORT so the management API binds the operator port");
        assertTrue(script.contains("-e PEERS=\"${AETHER_PEERS}\""),
                   "docker run must export PEERS — Dockerfile entrypoint converts it to --peers=");
        assertTrue(script.contains("-e AETHER_CLUSTER_SECRET=\"${AETHER_CLUSTER_SECRET}\""),
                   "docker run must export AETHER_CLUSTER_SECRET so TLS init resolves the seed");
    }

    @Test
    void render_emitsClusterNameFromAllowList_inContainerEnv() {
        // AETHER_CLUSTER_NAME is sourced from the threaded clusterName param (not host env),
        // baked into the docker-run -e flags via the IDENTITY_VARS allow-list.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret-xyz",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("-e AETHER_CLUSTER_NAME=\"prod-cluster\""),
                   "docker run must export AETHER_CLUSTER_NAME from the threaded cluster name");
        assertTrue(script.contains("-e AETHER_CLUSTER_SECRET=\"${AETHER_CLUSTER_SECRET}\""),
                   "AETHER_CLUSTER_SECRET must still be emitted (shell-var form) via the allow-list");
    }

    @Test
    void render_coreRole_stampsRoleLabelAndAetherRoleEnv() {
        // Wave 2 / W4 (cluster-topology-overhaul spec): the node's INTENDED role is threaded
        // explicitly — the aether-role label and the AETHER_ROLE env are sourced from the role
        // param, never hardcoded to core nor inherited from the bootstrapping host's env.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("-l aether-role=core"),
                   "container label must carry the threaded role");
        assertTrue(script.contains("-e AETHER_ROLE=\"core\""),
                   "AETHER_ROLE env must be sourced from the threaded role, not host env");
    }

    @Test
    void render_workerRole_stampsWorkerLabelAndAetherRoleEnv() {
        // Wave 2 / W4: a WORKER intent flows through end-to-end — the provisioned node
        // self-asserts role=worker via its AETHER_ROLE, so every role-scoped projection
        // (coreCountedMembers / quorum / heal / CORE_ONLY placement) classifies it correctly.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.WORKER,
                                             "node-w1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("-l aether-role=worker"),
                   "container label must carry the threaded worker role");
        assertTrue(script.contains("-e AETHER_ROLE=\"worker\""),
                   "AETHER_ROLE env must carry the threaded worker role");
        assertFalse(script.contains("-l aether-role=core"),
                    "the legacy hardcoded core label must be gone");
    }

    @Test
    void render_devModeLine_presentOnlyWhenEnvSet() {
        // Real assertion when the bootstrapping host exports AETHER_INSECURE_DEV_MODE; a
        // negative assertion otherwise (env unset => no dev-mode line). System.getenv cannot
        // be set in-process, so this branches on the ambient env.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        var devMode = System.getenv("AETHER_INSECURE_DEV_MODE");
        if (devMode != null && !devMode.isEmpty()) {
            assertTrue(script.contains("-e AETHER_INSECURE_DEV_MODE=\"" + devMode + "\""),
                       "dev-mode line must be emitted when AETHER_INSECURE_DEV_MODE is set");
        } else {
            assertFalse(script.contains("AETHER_INSECURE_DEV_MODE"),
                        "dev-mode line must be absent when AETHER_INSECURE_DEV_MODE is unset");
        }
    }

    @Test
    void render_threadsClusterAndManagementPortsFromOperatorConfig() {
        // Defense in depth: env vars carry the truth, but the cloud-init script
        // also exports shell variables sourced from operator config so logs and
        // diagnostics surface the actual ports.
        var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [operations.ports]
                cluster = 6000
                management = 5160
                app_http = 8070

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"

                [source.eu-1.core]
                count = 3
                """;
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("AETHER_CLUSTER_PORT=\"6000\""),
                   "Operator-supplied cluster port must be exported as AETHER_CLUSTER_PORT shell var");
        assertTrue(script.contains("AETHER_MANAGEMENT_PORT=\"5160\""),
                   "Operator-supplied management port must be exported as AETHER_MANAGEMENT_PORT shell var");
    }

    @Test
    void render_containerRuntime_resolvesRoutableAdvertiseHostAndPassesItConditionally() {
        // A CTM auto-heal replacement must advertise the VM's routable IP, not its hostname (which
        // peers cannot resolve, getting it SWIM-killed). The user-data resolves the host IP on the
        // VM (outside the container) via the provider-agnostic 'ip route get', never failing the
        // boot, then passes it into the container as -e AETHER_ADVERTISE_HOST ONLY when non-empty.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("AETHER_ADVERTISE_HOST=\"$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \\([0-9.]*\\).*/\\1/p' | head -n1)\" || true"),
                   "Must resolve the VM's routable IPv4 with a provider-agnostic command that never fails the boot");
        assertTrue(script.contains("if [ -n \"${AETHER_ADVERTISE_HOST}\" ]; then ADVERTISE_ENV=\"-e AETHER_ADVERTISE_HOST=${AETHER_ADVERTISE_HOST}\"; fi"),
                   "Must build the -e flag only when the resolved host is non-empty (runtime test)");
        assertTrue(script.contains("    ${ADVERTISE_ENV} \\\n"),
                   "docker run must expand the conditional advertise-host env so an unset IP adds no flag");
    }

    @Test
    void render_jvmRuntime_resolvesRoutableAdvertiseHostAndExportsItConditionally() {
        // Same routable-IP requirement on the bare-process path: resolve on the VM, then export
        // AETHER_ADVERTISE_HOST (the node reads it from env via Main.findAdvertiseHostOverride)
        // ONLY when non-empty, so an unset IP leaves the node's SWIM-reflection chain in charge.
        var jvmToml = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [runtime.bare-metal]
                type = "jvm"

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"

                [source.eu-1.core]
                count = 3
                runtime = "bare-metal"
                """;
        var config = ClusterBootstrapConfigParser.parse(jvmToml).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             clusterName("prod-cluster").unwrap(),
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("AETHER_ADVERTISE_HOST=\"$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \\([0-9.]*\\).*/\\1/p' | head -n1)\" || true"),
                   "JVM-mode must also resolve the VM's routable IPv4 with the provider-agnostic command");
        assertTrue(script.contains("if [ -n \"${AETHER_ADVERTISE_HOST}\" ]; then export AETHER_ADVERTISE_HOST; fi"),
                   "JVM-mode must export AETHER_ADVERTISE_HOST only when the resolved host is non-empty");
        assertFalse(script.contains("ADVERTISE_ENV"),
                    "JVM-mode must not use the container-only ADVERTISE_ENV docker-run construct");
    }
}
