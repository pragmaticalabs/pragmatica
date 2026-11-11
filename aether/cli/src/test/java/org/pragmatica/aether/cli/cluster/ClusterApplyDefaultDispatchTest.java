// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// #686: the default dispatch of `aether cluster apply` reaches the orchestrator. The command, the orchestrator, the ledger and the
/// commit are the real ones; the cluster is a fake HTTP server and the waves are a stub, so no cloud or Docker is touched.
class ClusterApplyDefaultDispatchTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("dispatch686").unwrap();
    private static final long STORED_VERSION = 7L;

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    @TempDir
    Path dir;

    private HttpServer cluster;
    private String originalEndpoint;
    private int commitStatus = 200;
    private final List<String> events = new ArrayList<>();
    private final List<String> commitBodies = new ArrayList<>();

    private record Outcome(int exitCode, String stdout, String stderr) {}

    @BeforeEach
    void fakeCluster() throws Exception {
        cluster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cluster.createContext("/", exchange -> {
            var isApply = "POST".equals(exchange.getRequestMethod());
            var body = isApply
                       ? commitBody(exchange)
                       : storedBody();

            exchange.sendResponseHeaders(isApply ? commitStatus : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        cluster.start();
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        ClusterHttpClient.ENDPOINT_OVERRIDE.set("http://127.0.0.1:" + cluster.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        cluster.stop(0);
    }

    private byte[] commitBody(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        commitBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        events.add("commit");

        return "{\"error\":\"refused\"}".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] storedBody() {
        return ("{\"tomlContent\": \"" + toml(1).replace("\"", "\\\"").replace("\n", "\\n") + "\", \"configVersion\": " + STORED_VERSION + "}")
               .getBytes(StandardCharsets.UTF_8);
    }

    private static String toml(int count) {
        return """
               config_version = "1.0.0"
               [cluster]
               name = "dispatch686"
               version = "1.0.0"
               [source.local]
               type = "docker"
               [source.local.core]
               count = %d
               """.formatted(count);
    }

    private Path desiredFile(int count) throws Exception {
        return Files.writeString(dir.resolve("aether-cluster.toml"), toml(count));
    }

    private Outcome run(ClusterApplyCommand command, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;

        try (var capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             var capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);

            var exit = new CommandLine(command).execute(args);

            return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> waves(AtomicReference<ApplyPhase> phaseSeen,
                                                                                                                       boolean succeed) {
        return (plan, stored, desired, recorder) -> {
            events.add("waves");
            phaseSeen.set(ApplyState.read(CLUSTER).unwrap().unwrap().phase());

            return succeed
                   ? Result.success(ApplyResult.applyResult(plan, 2, 0, 0))
                   : Causes.cause("wave 2 failed").result();
        };
    }

    private ApplyPhase ledgerPhase() {
        return ApplyState.read(CLUSTER).unwrap().map(ApplyState::phase).or((ApplyPhase) null);
    }

    @Test
    void defaultApply_runsTheWavesUnderTheLedger_thenCommitsOnceFencedByTheVersionItPlannedAgainst() throws Exception {
        var phaseSeen = new AtomicReference<ApplyPhase>();

        var outcome = run(new ClusterApplyCommand(waves(phaseSeen, true)), "--yes", desiredFile(3).toString());

        assertThat(outcome.stderr()).isEmpty();
        assertThat(outcome.exitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outcome.stdout()).contains("Apply complete: 2 added");
        assertThat(events).as("the waves run first, the commit follows, once").containsExactly("waves", "commit");
        assertThat(phaseSeen.get()).as("the ledger was written before the waves ran").isEqualTo(ApplyPhase.RUNNING);
        assertThat(commitBodies.getFirst()).contains("\"expectedVersion\":" + STORED_VERSION).contains("count = 3");
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).as("a committed apply closes its ledger").isFalse();
    }

    @Test
    void defaultApply_failureMidWave_sendsNoCommit_keepsTheLedger_andIsResumable() throws Exception {
        var outcome = run(new ClusterApplyCommand(waves(new AtomicReference<>(), false)), "--yes", desiredFile(3).toString());

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("wave 2 failed");
        assertThat(commitBodies).as("the config is committed only after the waves succeed").isEmpty();
        assertThat(ledgerPhase()).isEqualTo(ApplyPhase.RUNNING);

        var versions = new ArrayList<Long>();
        var desired = ClusterBootstrapConfigParser.parse(toml(3)).unwrap();
        var stored = ClusterBootstrapConfigParser.parse(toml(1)).unwrap();
        var resumed = ApplyOrchestrator.resume(desired,
                                               stored,
                                               ApplyTestCommits.recording(versions),
                                               _ -> Causes.cause("no provider").result(),
                                               (plan, from, to, recorder) -> Result.success(ApplyResult.applyResult(plan, 2, 0, 0)),
                                               () -> Result.success(Option.none()));

        assertThat(resumed.isSuccess()).as("--resume continues the halted apply: %s", resumed).isTrue();
        assertThat(versions).as("the resumed commit is fenced by the version the LEDGER recorded").containsExactly(STORED_VERSION);
        assertThat(ledgerPhase()).as("a resumed, committed apply closes its ledger").isNull();
    }

    @Test
    void defaultApply_commitRefusedByTheCluster_marksTheLedgerAborted_andPointsAtRollback() throws Exception {
        commitStatus = 409;

        var outcome = run(new ClusterApplyCommand(waves(new AtomicReference<>(), true)), "--yes", desiredFile(3).toString());

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--rollback");
        assertThat(ledgerPhase()).isEqualTo(ApplyPhase.ABORTED);
    }

    @Test
    void defaultApply_commitWithNoDefinitiveAnswer_keepsTheLedgerCommitting_andPointsAtResume() throws Exception {
        commitStatus = 503;

        var outcome = run(new ClusterApplyCommand(waves(new AtomicReference<>(), true)), "--yes", desiredFile(3).toString());

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--resume");
        assertThat(ledgerPhase()).isEqualTo(ApplyPhase.COMMITTING);
    }

    @Test
    void dryRun_plansAgainstTheStoredConfig_runsNoWaves_commitsNothing_writesNoLedger() throws Exception {
        var outcome = run(new ClusterApplyCommand(waves(new AtomicReference<>(), true)), "--dry-run", desiredFile(3).toString());

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outcome.stdout()).contains("Dry-run complete.");
        assertThat(events).isEmpty();
        assertThat(ledgerPhase()).isNull();
    }
}
