// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// B1 against a REAL Docker daemon, resolved through the real `ProviderResolver` (no factory override, no fake): the
/// wave's Docker add must create the container, pass its own label check and record it, with AETHER_CLUSTER_NAME unset.
/// Skipped where there is no usable daemon, no local alpine image to derive from, or the variable is exported.
class WaveExecutorDockerDaemonTest {
    private final String suffix = randomSuffix();
    private final ClusterName cluster = ClusterName.clusterName("p2daemon" + suffix).unwrap();
    private final String image = "aether-p2-daemon-test:" + suffix;
    /// The test's OWN network, passed to the provider as `network_name`: the shared default `aether-network` is used by
    /// the bootstrap tests, and removing it here could pull it from under a concurrent run.
    private final String network = "aether-p2-net-" + suffix;

    private static String randomSuffix() {
        var bytes = new byte[6];

        new java.security.SecureRandom().nextBytes(bytes);

        return HexFormat.of().formatHex(bytes);
    }

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private static String run(String stdin, String... command) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();

            if (stdin != null) {
                process.getOutputStream().write(stdin.getBytes());
            }

            process.getOutputStream().close();

            var output = new String(process.getInputStream().readAllBytes()).strip();

            return process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    @BeforeEach
    void requireADaemonAndABaseImage() {
        Assumptions.assumeTrue(System.getenv("AETHER_CLUSTER_NAME") == null, "AETHER_CLUSTER_NAME is exported: the test would not reproduce B1");
        Assumptions.assumeTrue(run(null, "docker", "version", "--format", "{{.Server.Version}}") != null, "no usable Docker daemon");

        var base = run(null, "docker", "images", "--format", "{{.Repository}}:{{.Tag}}", "alpine");

        Assumptions.assumeTrue(base != null && !base.isBlank(), "no local alpine image to derive the test image from");
        Assumptions.assumeTrue(run("FROM " + base.lines().findFirst().orElseThrow() + "\nCMD [\"sleep\", \"300\"]\n", "docker", "build", "-q", "-t", image, "-") != null,
                               "could not build the test image offline");

        Assumptions.assumeTrue(run(null, "docker", "network", "create", network) != null, "could not create the test network");
        ProviderResolver.dockerComputeOverrides = java.util.Map.of("network_name", network);
    }

    @AfterEach
    void clean() {
        var ids = run(null, "docker", "ps", "-aq", "--filter", "label=aether.cluster=" + cluster.value());

        if (ids != null && !ids.isBlank()) {
            var command = new java.util.ArrayList<>(List.of("docker", "rm", "-f"));

            command.addAll(ids.lines().toList());
            run(null, command.toArray(String[]::new));
        }

        run(null, "docker", "rmi", "-f", image);

        ProviderResolver.dockerComputeOverrides = java.util.Map.of();
        run(null, "docker", "network", "rm", network);
    }

    @Test
    void dockerAdd_throughTheRealResolverWithNoClusterEnvironment_createsRecordsAndLabelsTheContainer() {
        var desired = ClusterBootstrapConfigParser.parse("""
                config_version = "1.0.0"
                [cluster]
                name = "%s"
                version = "1.0.0"
                [runtime.coreapp]
                type = "docker"
                image = "%s"
                [source.local]
                type = "docker"
                [source.local.core]
                count = 1
                runtime = "coreapp"
                """.formatted(cluster.value(), image)).unwrap();
        var plan = DiffPlan.diffPlan(List.of(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 1)), List.of(), List.of(), List.of());
        var recorder = ledger.recorder(cluster, List.of(ApplyAction.actionKey(plan.additions().getFirst())));

        WaveExecutor.execute(plan, desired, desired, recorder).onFailure(cause -> fail(cause.message()));

        var onDisk = ledger.ledger(cluster);

        assertThat(onDisk.resources()).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo(ApplyResource.State.CREATED);
            assertThat(row.provider()).isEqualTo("docker");
            assertThat(row.serverId().or("")).hasSize(64);
        });

        var labelled = run(null, "docker", "ps", "-aq", "--no-trunc", "--filter", "label=aether-apply=" + onDisk.applyId());

        assertThat(labelled).as("the container carries the apply label").isEqualTo(onDisk.resources().getFirst().serverId().or(""));
        assertThat(run(null, "docker", "inspect", "--format", "{{json .NetworkSettings.Networks}}", labelled)).as("the container joined the test's own network, not the shared default")
                .contains("\"" + network + "\"")
                .doesNotContain("\"aether-network\"");
    }
}
