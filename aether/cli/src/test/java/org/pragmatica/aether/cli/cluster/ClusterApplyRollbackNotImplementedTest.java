// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.lang.Result;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Owner ruling (#686): `aether cluster apply --rollback` fails honestly until the wave rollout is
/// decided. The refusal comes BEFORE any side effect, and "before" is pinned with recording fakes rather
/// than inferred from the output: a mutant that reads the registry, reads the config, reads or writes
/// the apply state, or calls the cluster before refusing must fail one of these tests.
///
///  - registry: [ClusterTargetMixin#registryLoader] is replaced with a counting supplier;
///  - config file: the picocli `Path` converter hands the command a recording `Path` proxy, so any
///    access to the config (a `Files.*` call goes through `getFileSystem`/`toFile`/...) is counted;
///  - cluster: [ClusterHttpClient#ENDPOINT_OVERRIDE] points at a local HTTP server that counts requests;
///  - state store: the test home (see `CliTestHomeIsolationTest`) holds an apply-state file and the
///    whole `~/.aether` tree is snapshotted before and after.
class ClusterApplyRollbackNotImplementedTest {
    private static final String CONFIG = "/nonexistent/aether-cluster.toml";

    private final AtomicInteger registryReads = new AtomicInteger();
    private final AtomicInteger clusterRequests = new AtomicInteger();
    private final AtomicInteger stateAccesses = new AtomicInteger();
    private java.util.function.Function<org.pragmatica.aether.environment.ClusterName, Path> originalStateDirResolver;
    private final List<String> configAccesses = new ArrayList<>();
    private HttpServer fakeCluster;
    private Supplier<Result<ClusterRegistry>> originalRegistryLoader;
    private String originalEndpoint;
    private Path aetherDir;
    private String treeBefore;

    private record Outcome(int exitCode, String stdout, String stderr) {}

    @BeforeEach
    @SuppressWarnings("JBCT-EX-01")
    void setUp() throws Exception {
        aetherDir = Path.of(System.getProperty("user.home"), ".aether");
        Files.createDirectories(aetherDir.resolve("clusters").resolve("rollback-pin"));
        Files.writeString(aetherDir.resolve("clusters").resolve("rollback-pin").resolve("apply-state.json"), "{\"sentinel\": true}");

        originalRegistryLoader = ClusterTargetMixin.registryLoader;
        ClusterTargetMixin.registryLoader = () -> {
            registryReads.incrementAndGet();
            return Result.<ClusterRegistry>failure(org.pragmatica.lang.utils.Causes.cause("registry must not be read"));
        };

        fakeCluster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeCluster.createContext("/", exchange -> {
            clusterRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        fakeCluster.start();
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        ClusterHttpClient.ENDPOINT_OVERRIDE.set("http://127.0.0.1:" + fakeCluster.getAddress().getPort());

        originalStateDirResolver = ApplyState.stateDirResolver;
        ApplyState.stateDirResolver = name -> {
            stateAccesses.incrementAndGet();
            return originalStateDirResolver.apply(name);
        };

        treeBefore = snapshot(aetherDir);
    }

    @AfterEach
    @SuppressWarnings("JBCT-EX-01")
    void tearDown() throws Exception {
        ClusterTargetMixin.registryLoader = originalRegistryLoader;
        ApplyState.stateDirResolver = originalStateDirResolver;
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        fakeCluster.stop(0);
        Files.deleteIfExists(aetherDir.resolve("clusters").resolve("rollback-pin").resolve("apply-state.json"));
        Files.deleteIfExists(aetherDir.resolve("clusters").resolve("rollback-pin"));
    }

    @Test
    void rollback_isRefusedAsNotImplemented_withNonZeroExit() {
        var outcome = run("--rollback", CONFIG);

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--rollback is not implemented").contains("#686");
        assertThat(outcome.stdout()).doesNotContain("Apply complete");
    }

    @Test
    void rollback_refusalHasNoSideEffect_noRegistryNoConfigNoClusterNoStateAccess() {
        var outcome = run("--rollback", CONFIG);

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(registryReads.get()).as("registry reads").isZero();
        assertThat(configAccesses).as("config file accesses").isEmpty();
        assertThat(clusterRequests.get()).as("cluster requests").isZero();
        assertThat(stateAccesses.get()).as("apply-state reads, writes or deletes").isZero();
        assertThat(snapshot(aetherDir)).as("the ~/.aether tree, including apply-state files").isEqualTo(treeBefore);
    }

    /// `--cluster X` reads the registry to resolve X, and an unknown X fails with its own error. The refusal
    /// must win over both.
    @Test
    void rollback_withClusterOption_isRefusedBeforeTheRegistryIsRead() {
        var outcome = run("--cluster", "no-such-cluster", "--rollback", CONFIG);

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--rollback is not implemented").doesNotContain("no-such-cluster");
        assertThat(registryReads.get()).as("registry reads").isZero();
    }

    @Test
    void rollback_withResume_isStillRefused_withNoSideEffect() {
        var outcome = run("--rollback", "--resume", CONFIG);

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--rollback is not implemented");
        assertThat(registryReads.get()).isZero();
        assertThat(clusterRequests.get()).isZero();
        assertThat(stateAccesses.get()).isZero();
        assertThat(configAccesses).isEmpty();
    }

    /// Control: the fakes really are wired. Without the flag the command DOES reach for the registry, so a
    /// zero above means "not touched", not "unreachable".
    @Test
    void withoutRollback_theCommandReachesTheRegistry_andPrintsNoRefusal() {
        var outcome = run("--cluster", "some-cluster", CONFIG);

        assertThat(registryReads.get()).as("control: the registry loader is on the path").isPositive();
        assertThat(outcome.stderr()).doesNotContain("is not implemented");
    }

    /// Control: the state-store recorder is on the path of every apply-state read.
    @Test
    void stateRead_goesThroughTheRecorder() {
        ApplyState.read(org.pragmatica.aether.environment.ClusterName.clusterName("rollback-pin").unwrap());

        assertThat(stateAccesses.get()).as("control: an apply-state read is recorded").isPositive();
    }

    @Test
    void withoutRollback_theConfigRecorderSeesTheConfigRead() {
        var outcome = run("--yes", CONFIG);

        assertThat(configAccesses).as("control: the recording Path is on the config path").isNotEmpty();
        assertThat(outcome.stderr()).doesNotContain("is not implemented");
    }

    private Outcome run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;

        try (var capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             var capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);

            var commandLine = new CommandLine(new ClusterApplyCommand());

            commandLine.registerConverter(Path.class, this::recordingPath);

            var exit = commandLine.execute(args);

            return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /// A `Path` whose every method except identity ones is recorded before delegating.
    private Path recordingPath(String raw) {
        var real = Path.of(raw);

        return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class}, (proxy, method, methodArgs) -> {
            if (!List.of("toString", "hashCode", "equals").contains(method.getName())) {
                configAccesses.add(method.getName());
            }

            return method.invoke(real, methodArgs);
        });
    }

    /// Relative path + size + content hash of every file under `root`, sorted.
    @SuppressWarnings("JBCT-EX-01")
    private static String snapshot(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.sorted()
                       .map(path -> describe(root, path))
                       .collect(Collectors.joining("\n"));
        } catch (java.io.IOException e) {
            return "unreadable: " + e;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String describe(Path root, Path path) {
        try {
            return root.relativize(path) + (Files.isRegularFile(path) ? " " + Files.readString(path).hashCode() : "/");
        } catch (java.io.IOException e) {
            return path + " unreadable";
        }
    }

}
