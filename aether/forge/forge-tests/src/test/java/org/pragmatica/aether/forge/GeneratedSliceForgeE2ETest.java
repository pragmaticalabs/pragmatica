// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.jbct.init.SliceProjectInitializer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// End-to-end getting-started gate for the `jbct init` scaffold (issues #511 / #513 / #515).
///
/// This is the heavy leg of the two-stage init gate — the fast format/lint drift sensor lives in
/// `jbct-cli` (GeneratedSliceComplianceTest). Here we walk the FULL cold-user path:
///
///   1. `jbct init` a scaffold into a temp dir (the same generator the CLI drives).
///   2. `mvn clean install` it — this runs the scaffold's own `format-check` + `lint` + `package` +
///      blueprint install gates through real Maven (exactly what `./run-forge.sh` does first). A
///      template that drifts from the formatter/linter (#511) fails the build HERE.
///   3. Deploy the blueprint BY ARTIFACT COORDINATES — `groupId:artifactId:version:blueprint`, the
///      contract `run-forge.sh` and [ForgeServer] use (#513) — to an in-JVM cluster.
///   4. `GET /api/hello/World` on an app-HTTP port and assert `Hello, World!` — the cold user's very
///      first request, which used to end at `No route found` because the scaffold shipped a file-path
///      `--blueprint` and generate-blueprint remnants.
///
/// Non-vacuous by construction: {@link #deployByFilePath_isRejected_soTheOldContractCannotSilentlyReturn}
/// pins the pre-fix behaviour — a file-path "blueprint" must be REJECTED, not silently accepted.
///
/// Requires Maven on PATH and the platform artifacts in the local repo. Skipped (not failed) only
/// when Maven is unavailable; a scaffold that does not build (e.g. a #511 template regression) FAILS.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedSliceForgeE2ETest {
    private static final int BASE_PORT = 6500;
    private static final int BASE_MGMT_PORT = 6600;
    private static final int BASE_APP_HTTP_PORT = 6400;
    private static final String PLATFORM_VERSION = System.getProperty("project.version", "UNKNOWN");
    private static final String BLUEPRINT_COORDS = "org.example:hello:1.0.0-SNAPSHOT:blueprint";
    private static final String SLICE_ARTIFACT = "org.example:hello-hello-world:1.0.0-SNAPSHOT";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeTrue(mavenAvailable(), "Maven not on PATH — skipping scaffold e2e");
        var scaffold = generateAndBuildScaffold();

        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ge");
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader()
                                                                                    .isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);
        assertThat(scaffold).exists();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await();
        }
    }

    @Test
    void generatedScaffold_deployedByCoordinates_servesHelloWorld() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();
        var deployResponse = deployByArtifact(leaderPort, BLUEPRINT_COORDS);

        assertThat(deployResponse).as("blueprint deploy by coordinates must not error: %s", deployResponse)
                  .doesNotContain("\"error\"");
        await().atMost(DEPLOY_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::helloSliceActive);
        assertThat(helloResponse()).as("cold user's first request GET /api/hello/World").contains("Hello, World!");
    }

    @Test
    void deployByFilePath_isRejected_soTheOldContractCannotSilentlyReturn() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();
        var status = deployStatus(leaderPort, "target/blueprint.toml");

        assertThat(status).as("the pre-fix file-path --blueprint contract must be rejected (non-2xx), not accepted")
                  .isGreaterThanOrEqualTo(400);
    }

    private Path generateAndBuildScaffold() throws IOException, InterruptedException {
        var workDir = Files.createTempDirectory("jbct-init-e2e");
        var projectDir = workDir.resolve("hello");

        SliceProjectInitializer.sliceProjectInitializer(projectDir,
                                                        "org.example",
                                                        "hello",
                                                        "HelloWorld",
                                                        PLATFORM_VERSION,
                                                        PLATFORM_VERSION,
                                                        PLATFORM_VERSION)
                               .flatMap(SliceProjectInitializer::initialize)
                               .onFailure(cause -> {
                                              throw new AssertionError("Scaffold generation failed: " + cause.message());
                                          });
        var exitCode = runMaven(projectDir);

        assertThat(exitCode).as("scaffold `mvn install` must succeed — a template that fails its own "
                               + "format-check/lint gate (#511) breaks the build here; see " + projectDir.resolve("build.log"))
                  .isZero();

        return projectDir;
    }

    private static int runMaven(Path projectDir) throws IOException, InterruptedException {
        var process = new ProcessBuilder("mvn", "-q", "-B", "clean", "install", "-DskipTests").directory(projectDir.toFile())
                                                                                              .redirectErrorStream(true)
                                                                                              .redirectOutput(projectDir.resolve("build.log")
                                                                                                                        .toFile())
                                                                                              .start();

        return process.waitFor(10, TimeUnit.MINUTES)
               ? process.exitValue()
               : timedOut(process);
    }

    private static int timedOut(Process process) {
        process.destroyForcibly();

        return -1;
    }

    private static boolean mavenAvailable() {
        try {
            return new ProcessBuilder("mvn", "--version").redirectErrorStream(true)
                                                         .start()
                                                         .waitFor(1, TimeUnit.MINUTES);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String deployByArtifact(int port, String coords) {
        var body = "{\"artifact\":\"" + coords + "\"}";

        return post(port, "/api/blueprints/deploy", body);
    }

    private int deployStatus(int port, String coords) {
        var body = "{\"artifact\":\"" + coords + "\"}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/blueprints/deploy"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(30))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::statusCode)
                   .or(-1);
    }

    private boolean helloSliceActive() {
        return cluster.slicesStatus()
                      .stream()
                      .anyMatch(status -> status.artifact()
                                                .equals(SLICE_ARTIFACT) && status.state()
                                                                                 .equals(SliceState.ACTIVE.name()));
    }

    private String helloResponse() {
        var appPort = cluster.getAvailableAppHttpPorts().getFirst();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + appPort + "/api/hello/World"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body()
                                                       .contains("\"quorum\":true"))
                   .or(false);
    }

    private String post(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(30))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
