// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.config.cluster.NodeRole;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1019 — the ticket's symptom, pinned at the surface that produces it.
///
/// Round 1 of #1019 shipped the fix with no test that runs `aether cluster init` and then READS what it
/// wrote. The round-1 review (B1) demonstrated the gap by re-introducing the exact defect one layer
/// below the CLI — `count = Math.min(5, core)` in `ClusterConfigGenerator.appendRoles`, which is #1019
/// verbatim — and watching all 826 of the module's tests stay green. The unit tests covered
/// `CoreWorkerSplit`'s arithmetic, and nothing covered whether that arithmetic REACHED the file.
///
/// So these drive the real command through `picocli`, parse the file back with the SAME parser
/// `aether cluster bootstrap` uses, and assert on the three numbers the ticket is about:
///
///   - `derivedCoreCount()` — what `BootstrapPhaseProvision.provisionCloudWithCompute` reads to decide
///     how many VMs to create, and the number #1019 says was silently capped at 5;
///   - `[source.X.core] count` — the same value as written, so a generator-side clamp is visible;
///   - `[cluster.core] min` / `max` — what `BootstrapOverlayGenerator.clusterSection` stamps into each
///     node's own config as `nodes`, and therefore the quorum basis each node boots with.
///
/// A test that built `ClusterConfigAnswers` itself would not do this job: it would construct the very
/// object whose construction is under test, and stay green through any defect in the CLI's flag
/// handling or the generator. The entry point has to be the command line.
class ClusterInitSizingRoundTripTest {

    private static ClusterBootstrapConfig parsed(Path file) {
        String content;

        try {
            content = Files.readString(file);
        } catch (Exception e) {
            throw new AssertionError("could not read generated config: " + e.getMessage(), e);
        }

        return ClusterBootstrapConfigParser.parse(content)
                                           .fold(cause -> { throw new AssertionError("generated config did not parse: " + cause.message()); },
                                                 config -> config);
    }

    private static int coreCount(ClusterBootstrapConfig config) {
        return config.sources()
                     .values()
                     .stream()
                     .flatMap(source -> java.util.stream.Stream.ofNullable(source.roles().get(NodeRole.CORE)))
                     .mapToInt(role -> role.count().or(0))
                     .sum();
    }

    private static int workerCount(ClusterBootstrapConfig config) {
        return config.sources()
                     .values()
                     .stream()
                     .flatMap(source -> java.util.stream.Stream.ofNullable(source.roles().get(NodeRole.WORKER)))
                     .mapToInt(role -> role.count().or(0))
                     .sum();
    }

    private static boolean hasWorkerTable(ClusterBootstrapConfig config) {
        return config.sources()
                     .values()
                     .stream()
                     .anyMatch(source -> source.roles().containsKey(NodeRole.WORKER));
    }

    /// Runs the command with stderr captured, so a refusal's MESSAGE can be asserted rather than only
    /// its exit code — several of the behaviours under test here differ from their neighbours only in
    /// what they say (`--worker-nodes` refused on ssh vs accepted elsewhere; the host-count guard vs
    /// the worker-negative guard, which refuse the same inputs with different reasons).
    private record Run(int exitCode, String stderr) {}

    private static Run run(String... args) {
        var captured = new ByteArrayOutputStream();
        var original = System.err;

        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

            var exit = new CommandLine(new ClusterCommand()).execute(args);

            return new Run(exit, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
        }
    }

    @Nested
    class WhatInitWrites {

        /// `worker = ABSENT` is a DIFFERENT input from `--worker-nodes 0` and is exercised separately:
        /// absent means zero by policy (`ClusterInitCommand#requestedSplit`), so a defect that dropped
        /// the flag entirely would leave the explicit-zero case passing.
        private static final int ABSENT = -1;
        private static final List<Integer> WORKER_CASES = List.of(ABSENT, 0, 2, 40);

        /// One row of the ticket's table, driven from the command line and read back from the file.
        /// `as(...)` carries the case into the failure message, since the whole row runs under one test.
        private void assertRoundTrip(int core, int worker, Path tmp) {
            var output = tmp.resolve("core" + core + "-worker" + worker + ".toml");
            var expectedWorker = Math.max(worker, 0);
            var args = worker == ABSENT
                       ? new String[]{"init", "--target", "docker", "--name", "sizing-test",
                                      "--core-nodes", String.valueOf(core),
                                      "--output", output.toString()}
                       : new String[]{"init", "--target", "docker", "--name", "sizing-test",
                                      "--core-nodes", String.valueOf(core),
                                      "--worker-nodes", String.valueOf(worker),
                                      "--output", output.toString()};
            var label = "core=" + core + " worker=" + (worker == ABSENT ? "absent" : worker);
            var outcome = run(args);

            assertThat(outcome.exitCode()).as(label + " exit code (stderr: " + outcome.stderr() + ")")
                                          .isEqualTo(0);

            var config = parsed(output);

            // The number cloud provisioning reads. #1019's symptom is this being smaller than `core`.
            assertThat(config.derivedCoreCount()).as(label + " derivedCoreCount").isEqualTo(core);
            assertThat(coreCount(config)).as(label + " [source.X.core] count").isEqualTo(core);

            // What each node boots with as its quorum basis, via BootstrapOverlayGenerator.
            assertThat(config.coreTopology().min().or(-1)).as(label + " [cluster.core] min").isEqualTo(core);
            assertThat(config.coreTopology().max().or(-1)).as(label + " [cluster.core] max").isEqualTo(core);

            // The worker tier is emitted only when non-empty, and never folded into the core count.
            assertThat(hasWorkerTable(config)).as(label + " worker sub-table present").isEqualTo(expectedWorker > 0);
            assertThat(workerCount(config)).as(label + " worker count").isEqualTo(expectedWorker);

            ClusterBootstrapConfigValidator.validate(config)
                                           .onFailure(cause -> fail(label + " failed bootstrap validation: " + cause.message()));
        }

        @Test
        void init_fiveCores_writeBothTiersAsGiven(@TempDir Path tmp) {
            WORKER_CASES.forEach(worker -> assertRoundTrip(5, worker, tmp));
        }

        /// 7 is the recommended size and the one #1019 says was unreachable: the old deriver mapped
        /// every requested total onto at most 5 cores.
        @Test
        void init_sevenCores_writeBothTiersAsGiven(@TempDir Path tmp) {
            WORKER_CASES.forEach(worker -> assertRoundTrip(7, worker, tmp));
        }

        @Test
        void init_nineCores_writeBothTiersAsGiven(@TempDir Path tmp) {
            WORKER_CASES.forEach(worker -> assertRoundTrip(9, worker, tmp));
        }

        /// The worker tier is not bounded by the consensus maximum — 40 workers beside 9 cores is legal,
        /// and is the shape #1019 says capacity should take once the core tier is at its ceiling.
        @Test
        void init_workerTierIsNotBoundedByTheConsensusMaximum(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");

            assertThat(run("init", "--target", "docker", "--name", "wide",
                           "--core-nodes", "9", "--worker-nodes", "40",
                           "--output", output.toString()).exitCode()).isEqualTo(0);

            var config = parsed(output);

            assertThat(config.derivedCoreCount()).isEqualTo(9);
            assertThat(workerCount(config)).isEqualTo(40);
        }
    }

    @Nested
    class Refusals {

        @Test
        void init_coreAboveTheMaximum_isRefused(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var result = run("init", "--target", "docker", "--name", "too-big",
                             "--core-nodes", "11", "--output", output.toString());

            assertThat(result.exitCode()).isNotEqualTo(0);
            assertThat(result.stderr()).contains("core must be at most 9");
            assertThat(Files.exists(output)).isFalse();
        }

        @Test
        void init_coreBelowTheMinimum_isRefused(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var result = run("init", "--target", "docker", "--name", "too-small",
                             "--core-nodes", "3", "--output", output.toString());

            assertThat(result.exitCode()).isNotEqualTo(0);
            assertThat(result.stderr()).contains("at least 5 core nodes");
        }

        /// #1019 round-1 review, S3. `ClusterInitCommand#buildAnswersForTarget` routes
        /// `case DOCKER, FORGE` through the SAME `requestedSplit()`, so forge enforces the same
        /// minimum — the remedy round 1 printed here sent the reader straight back to this error.
        ///
        /// Asserted as an ABSENCE on purpose, and paired with a positive control below: the failure
        /// mode being pinned is a message that offers a remedy which does not work, and only the
        /// absence of that sentence distinguishes the fixed message from the broken one.
        @Test
        void tooFewCoreNodes_offersNoRemedyThatReTriggersTheSameError(@TempDir Path tmp) {
            var forge = run("init", "--target", "forge", "--name", "local-dev",
                            "--core-nodes", "3", "--output", tmp.resolve("forge.toml").toString());

            assertThat(forge.exitCode()).isNotEqualTo(0);
            // Control: this IS the TooFewCoreNodes message, so the absence below is about its content.
            assertThat(forge.stderr()).contains("at least 5 core nodes");
            assertThat(forge.stderr()).doesNotContain("--target forge");

            var docker = run("init", "--target", "docker", "--name", "local-dev",
                             "--core-nodes", "3", "--output", tmp.resolve("docker.toml").toString());

            assertThat(docker.stderr()).doesNotContain("--target forge");
        }

        /// #1019 round-1 review, M2: with the refusal removed, `--worker-nodes` was accepted on ssh and
        /// silently ignored — which is the shape the change exists to stop.
        @Test
        void ssh_workerNodesFlag_isRefusedRatherThanIgnored(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var result = run("init", "--target", "ssh", "--name", "ssh-cluster",
                             "--hosts", "10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5,10.0.0.6,10.0.0.7",
                             "--ssh-user", "aether", "--ssh-key", "/tmp/id_ed25519",
                             "--core-nodes", "5", "--worker-nodes", "2",
                             "--output", output.toString());

            assertThat(result.exitCode()).isNotEqualTo(0);
            assertThat(result.stderr()).contains("--worker-nodes");
            assertThat(result.stderr()).contains("does not apply to a ssh target");
            assertThat(Files.exists(output)).isFalse();
        }

        /// #1019 round-1 review, S4 — the behaviour change `cli.md` was contradicting. Before #1019 an
        /// ssh batch run needed no count; it is now required, and the message has to name the flag.
        @Test
        void ssh_withoutCoreNodes_isRefusedNamingTheFlag(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var result = run("init", "--target", "ssh", "--name", "ssh-cluster",
                             "--hosts", "10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5",
                             "--ssh-user", "aether", "--ssh-key", "/tmp/id_ed25519",
                             "--output", output.toString());

            assertThat(result.exitCode()).isNotEqualTo(0);
            assertThat(result.stderr()).contains("Required field missing or invalid: --core-nodes");
        }

        /// #1019 round-1 review, N3. The host-count guard and `CoreWorkerSplit`'s `worker >= 0` rule
        /// refuse this SAME input, so only the message distinguishes them — which is why the round-1
        /// mutation of this guard (M3) reddened nothing. Asserting the text is the whole point.
        @Test
        void ssh_coreExceedingHostCount_isRefusedNamingBothNumbers(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var result = run("init", "--target", "ssh", "--name", "ssh-cluster",
                             "--hosts", "10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5",
                             "--ssh-user", "aether", "--ssh-key", "/tmp/id_ed25519",
                             "--core-nodes", "7",
                             "--output", output.toString());

            assertThat(result.exitCode()).isNotEqualTo(0);
            assertThat(result.stderr()).contains("--core-nodes 7 exceeds the 5 host(s) given in --hosts");
        }
    }

    @Nested
    class SshRemainder {

        /// ssh derives nothing either: the worker tier is the remainder of `--hosts`, and the parsed
        /// file has to show it. `hosts` are written per role, so the assertion is on the host lists.
        @Test
        void ssh_workerTierIsTheHostRemainder(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");

            assertThat(run("init", "--target", "ssh", "--name", "ssh-cluster",
                           "--hosts", "10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5,10.0.0.6,10.0.0.7",
                           "--ssh-user", "aether", "--ssh-key", "/tmp/id_ed25519",
                           "--core-nodes", "5",
                           "--output", output.toString()).exitCode()).isEqualTo(0);

            var config = parsed(output);

            assertThat(config.coreTopology().min().or(-1)).isEqualTo(5);
            assertThat(config.coreTopology().max().or(-1)).isEqualTo(5);
            assertThat(hasWorkerTable(config)).isTrue();
        }
    }

    @Nested
    class ConfigsInitWritesAreBootstrappable {

        /// Every core size `init` accepts must also pass the validator `aether cluster bootstrap` runs.
        /// This is the pair of bounds meeting: the CLI's maximum and `ClusterBootstrapConfigValidator`'s
        /// new CL-04 ceiling are the same number, so the largest cluster `init` will author is exactly
        /// the largest one bootstrap accepts — with no gap in either direction.
        @Test
        void everyAcceptedCoreSize_passesTheBootstrapValidator(@TempDir Path tmp) {
            List.of(5, 7, 9)
                .forEach(core -> {
                    var output = tmp.resolve("boundary-" + core + ".toml");

                    assertThat(run("init", "--target", "docker", "--name", "boundary",
                                   "--core-nodes", String.valueOf(core),
                                   "--output", output.toString()).exitCode())
                             .as("core " + core + " must be authorable")
                             .isEqualTo(0);

                    ClusterBootstrapConfigValidator.validate(parsed(output))
                                                   .onFailure(cause -> fail("core " + core + " is authorable but not bootstrappable: " + cause.message()));
                });
        }
    }
}
