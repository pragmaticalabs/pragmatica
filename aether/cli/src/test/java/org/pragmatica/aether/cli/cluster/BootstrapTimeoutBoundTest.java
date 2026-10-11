// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// `aether cluster bootstrap --wait --timeout N`: an operator reads it as "give up after N seconds", but N only bounded the final status poll while
/// node health and quorum formation ran on the config's own 300 s and 600 s. An explicit `--timeout` now caps those waits too (never raising them);
/// without it the config's timeouts stand.
class BootstrapTimeoutBoundTest {
    private static final String CONFIG = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [operations.tls]
            auto_generate = false

            [runtime.default]
            type = "container"
            image = "aether-node:1.0.0"

            [source.d]
            type = "docker"

            [source.d.core]
            count = 3
            """;

    private static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig config() {
        return ClusterBootstrapConfigParser.parse(CONFIG).unwrap();
    }

    @AfterEach
    void reset() {
        BootstrapWaitCap.seconds = Option.none();
        ClusterBootstrapCommand.bootstrapInvoker = ClusterBootstrapOrchestrator::bootstrap;
    }

    @Test
    void cap_lowersAWait_neverRaisesOne_andIsAbsentByDefault() {
        assertThat(BootstrapWaitCap.cappedMs(300_000L)).as("no cap").isEqualTo(300_000L);
        BootstrapWaitCap.seconds = Option.some(60);
        assertThat(BootstrapWaitCap.cappedMs(300_000L)).isEqualTo(60_000L);
        assertThat(BootstrapWaitCap.cappedMs(10_000L)).as("a config wait below the cap is kept").isEqualTo(10_000L);
    }

    /// F6: `--timeout` is a RUNTIME cap. It must never reach the config the orchestrator hashes, or a timed-out bootstrap resumed with a longer
    /// `--timeout` (or none) is refused as "Config has changed".
    @Test
    void timeout_neverEntersTheHashedConfig_soAResumeWithAnotherTimeoutMatches(@TempDir Path dir) throws Exception {
        var toml = dir.resolve("cluster.toml");
        Files.writeString(toml, CONFIG);
        var hashes = new ArrayList<String>();
        var caps = new ArrayList<Option<Integer>>();

        ClusterBootstrapCommand.bootstrapInvoker = (config, resume, fullCheck, keys, keepOnFailure, raw) -> {
            hashes.add(ClusterBootstrapOrchestrator.computeConfigHash(config));
            caps.add(BootstrapWaitCap.seconds);

            return Causes.cause("stub").result();
        };
        for (var extra : List.of(List.of("--timeout", "60"), List.of("--timeout", "600"), List.<String> of())) {
            var args = new ArrayList<>(List.of("--wait", "--yes", toml.toString()));

            args.addAll(extra);
            new CommandLine(new ClusterBootstrapCommand()).execute(args.toArray(String[]::new));
            assertThat(BootstrapWaitCap.seconds.isPresent()).as("the cap does not outlive a call made with " + extra).isFalse();
        }

        assertThat(hashes).hasSize(3);
        assertThat(hashes).as("the hashed config is identical whatever --timeout says").containsOnly(hashes.getFirst());
        assertThat(caps).containsExactly(Option.some(60), Option.some(600), Option.none());
    }

    /// G6: the cap is applied where the waits are computed. A formation against a node that never answers ends at the cap, not the config's 300 s.
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void formation_appliesTheCap_toTheHealthWait() {
        BootstrapWaitCap.seconds = Option.some(1);
        var out = new ByteArrayOutputStream();
        var original = System.out;

        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            var ctx = BootstrapContext.bootstrapContext(config(),
                                                        BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                        List.of(),
                                                        List.of(NodeAddress.nodeAddress("n", "127.0.0.1", Option.none(), Option.some(1))));

            BootstrapPhaseFormation.execute(ctx);
        } finally {
            System.setOut(original);
        }

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("(timeout: 1s)");
    }

    /// H4: the QUORUM wait is capped where it is computed too, not only the health wait. Health answers at once, quorum never forms.
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void formation_appliesTheCap_toTheQuorumWait() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {
            var body = "{\"quorum\":false,\"nodeCount\":1,\"status\":\"healthy\"}".getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        BootstrapWaitCap.seconds = Option.some(1);
        var out = new ByteArrayOutputStream();
        var original = System.out;

        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            var ctx = BootstrapContext.bootstrapContext(config(),
                                                        BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                        List.of(),
                                                        List.of(NodeAddress.nodeAddress("n", "127.0.0.1", Option.none(), Option.some(server.getAddress().getPort()))));

            BootstrapPhaseFormation.execute(ctx);
        } finally {
            System.setOut(original);
            server.stop(0);
        }

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Waiting for quorum").contains("timeout: 1s)");
    }

    /// H4/G6 without waiting anything out: BOTH formation waits are capped where they are computed, so an uncapped one is an assertion failure and
    /// not a 10-minute hang. (The two tests above still drive the real poll loops; their timeout runs on a separate thread so a hang is a failure too.)
    @Test
    void formationWaits_areBothCapped_andBothUncappedWithoutACap() {
        var defaults = BootstrapPhaseFormation.formationWaits(config());

        assertThat(defaults.healthMs()).as("no cap: the config's health wait").isEqualTo(300_000L);
        assertThat(defaults.quorumMs()).as("no cap: the config's quorum wait").isEqualTo(600_000L);
        BootstrapWaitCap.seconds = Option.some(7);
        var capped = BootstrapPhaseFormation.formationWaits(config());

        assertThat(capped.healthMs()).isEqualTo(7_000L);
        assertThat(capped.quorumMs()).isEqualTo(7_000L);
    }

    /// G6: the REAL command wiring. `--wait --timeout 1` runs the command, whose call-site sets the cap, and the stand-in orchestrator then runs the
    /// REAL formation against a node that never answers. With the cap applied it fails in about a second; without it the health wait is 300 s and the
    /// separate-thread timeout fails the test.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTimeoutOnTheCommand_reachesTheRealFormationWaits(@TempDir Path dir) throws Exception {
        var toml = dir.resolve("cluster.toml");
        var out = new ByteArrayOutputStream();
        var original = System.out;

        Files.writeString(toml, CONFIG);
        ClusterBootstrapCommand.bootstrapInvoker = (config, resume, fullCheck, keys, keepOnFailure, raw) -> {
            var ctx = BootstrapContext.bootstrapContext(config,
                                                        BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                        List.of(),
                                                        List.of(NodeAddress.nodeAddress("n", "127.0.0.1", Option.none(), Option.some(1))));

            return BootstrapPhaseFormation.execute(ctx).map(_ -> null);
        };
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            new CommandLine(new ClusterBootstrapCommand()).execute("--wait", "--yes", "--timeout", "1", toml.toString());
        } finally {
            System.setOut(original);
        }

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("(timeout: 1s)");
    }

    @Test
    void timeoutGiven_isTrueOnlyForAnExplicitTimeoutOnAWaitBootstrap() {
        assertThat(parsed("--wait", "--timeout", "60").timeoutGiven()).isTrue();
        assertThat(parsed("--wait").timeoutGiven()).as("the 300 default is not a request").isFalse();
        assertThat(parsed("--timeout", "60").timeoutGiven()).as("without --wait there is no wait to bound").isFalse();
    }

    private static ClusterBootstrapCommand parsed(String... args) {
        var command = new ClusterBootstrapCommand();
        var all = new String[args.length + 2];

        all[0] = "cluster.toml";
        all[1] = "--yes";
        System.arraycopy(args, 0, all, 2, args.length);
        new CommandLine(command).parseArgs(all);

        return command;
    }
}
