// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository.maven;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.pragmatica.http.JdkHttpOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1599 — the wiring through `RemoteRepository` itself, against a local HTTP server and a local repository
/// in a temp directory (`maven.repo.local`, which the locator reads first, so `~/.m2` is never touched).
/// A download is cached with its sidecar, and a cached jar that no longer matches its checksum — the node's own mark or a
/// Maven one — is refused, never loaded and never deleted or fetched over (v1617 M1, #1725 ruling). Mutation that
/// reddens the torn-jar test: drop the `ArtifactCache.check` from the cache-hit branch of `resolveArtifact`.
class RemoteRepositoryCacheWiringTest {
    private static final byte[] JAR = "PK\u0003\u0004 served jar bytes for the wiring test".getBytes(StandardCharsets.UTF_8);
    private static final String JAR_PATH = "/org/example/demo/1.0.0/demo-1.0.0.jar";

    @TempDir
    Path localRepo;

    private final AtomicInteger jarRequests = new AtomicInteger();
    private HttpServer server;
    private String previousRepoProperty;

    @BeforeEach
    void setUp() throws IOException {
        previousRepoProperty = System.getProperty("maven.repo.local");
        System.setProperty("maven.repo.local", localRepo.toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var sha256 = ArtifactCache.digest(JAR, "SHA-256").unwrap().getBytes(StandardCharsets.US_ASCII);
        server.createContext(JAR_PATH, exchange -> {
            var path = exchange.getRequestURI().getPath();
            var body = path.endsWith(".sha256") ? sha256 : JAR;
            if (!path.endsWith(".sha256")) {
                jarRequests.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        if (previousRepoProperty == null) {
            System.clearProperty("maven.repo.local");
        } else {
            System.setProperty("maven.repo.local", previousRepoProperty);
        }
    }

    @Test
    void download_isCachedWithItsSidecar() throws IOException {
        var location = repository().locate(artifact()).await(timeSpan(10).seconds());

        assertThat(location.isSuccess()).as("located: %s", location).isTrue();
        assertThat(Files.readAllBytes(cachedJar())).isEqualTo(JAR);
        assertThat(cachedJar().resolveSibling("demo-1.0.0.jar.aether-sha256")).exists();
        assertThat(jarRequests.get()).isEqualTo(1);
    }

    @Test
    void tornCachedJar_isRefusedInsteadOfLoaded() throws IOException {
        assertThat(repository().locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        Files.write(cachedJar(), Arrays.copyOf(JAR, JAR.length / 2));

        var location = repository().locate(artifact()).await(timeSpan(10).seconds());

        assertThat(location.isFailure()).as("#1599: the torn cached jar is not loaded: %s", location).isTrue();
    }

    /// #1725 ruling — an operator runs `mvn install` over a jar the node wrote, so its bytes no longer match the node's
    /// mark. Resolution refuses it by name and the operator's bytes survive: nothing is fetched over them. Mutation that
    /// reddens it: route the mismatch back to `downloadAndCache` in `resolveArtifact` (the pre-ruling refetch).
    @Test
    void operatorInstallOverANodeMarkedJar_isRefused_andItsBytesSurvive() throws IOException {
        assertThat(repository().locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        var operatorBuild = "PK\u0003\u0004 the operator's own mvn install of the same coordinates".getBytes(StandardCharsets.UTF_8);
        Files.write(cachedJar(), operatorBuild);

        var location = repository().locate(artifact()).await(timeSpan(10).seconds());

        assertThat(location.isFailure()).as("refused, not loaded or refetched: %s", location).isTrue();
        location.onFailure(cause -> assertThat(cause).isInstanceOf(RemoteRepository.RemoteRepositoryError.CachedArtifactChecksumMismatch.class)
                                                     .extracting(c -> c.message())
                                                     .asString()
                                                     .contains(cachedJar().toString()));
        assertThat(jarRequests.get()).as("nothing is fetched over it").isEqualTo(1);
        assertThat(Files.readAllBytes(cachedJar())).as("the operator's bytes survive").isEqualTo(operatorBuild);
        assertThat(cachedJar().resolveSibling("demo-1.0.0.jar.aether-sha256")).exists();
    }

    /// v1617 M1 — a jar in the local repository that fails Maven's `.sha1` and was not written by the node: the resolve
    /// fails with the typed refusal, nothing is downloaded, and the jar (maybe the operator's own build) is untouched.
    @Test
    void foreignJarFailingItsMavenChecksum_isRefused_notDeletedOrOverwritten() throws IOException {
        var local = "PK\u0003\u0004 a locally installed, never-published build".getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(cachedJar().getParent());
        Files.write(cachedJar(), local);
        Files.writeString(cachedJar().resolveSibling("demo-1.0.0.jar.sha1"), "0000000000000000000000000000000000000000");

        var location = repository().locate(artifact()).await(timeSpan(10).seconds());

        assertThat(location.isFailure()).as("the mismatched foreign jar is not loaded: %s", location).isTrue();
        location.onFailure(cause -> assertThat(cause).isInstanceOf(RemoteRepository.RemoteRepositoryError.CachedArtifactChecksumMismatch.class));
        assertThat(jarRequests.get()).as("nothing is fetched over it").isZero();
        assertThat(Files.readAllBytes(cachedJar())).as("M1: the operator's jar is untouched").isEqualTo(local);
    }

    /// #1097 — each download builds its own JDK `HttpClient`, whose selector thread outlived the download.
    /// The test holds every client the downloads were given, so a revert leaves them un-terminated
    /// however much the GC runs. A global thread count could not: an unreachable client's selector also
    /// exits when it is collected, so a GC during the wait passed the revert.
    @Test
    void download_releasesItsHttpClient_soNoSelectorThreadSurvives() throws IOException, InterruptedException {
        var issued = new java.util.concurrent.CopyOnWriteArrayList<JdkHttpOperations>();
        var repository = RemoteRepository.remoteRepository("wiring-test",
                                                           "http://127.0.0.1:" + server.getAddress().getPort(),
                                                           RemoteRepository.DEFAULT_HTTP_TIMEOUT,
                                                           () -> {
                                                               var ops = JdkHttpOperations.jdkHttpOperations();
                                                               issued.add(ops);
                                                               return ops;
                                                           });

        assertThat(repository.locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        Files.deleteIfExists(cachedJar());
        assertThat(repository.locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        assertThat(issued).as("instrument check: each download asked for its own client").hasSize(2);

        var deadline = System.nanoTime() + 10_000_000_000L;

        while (!issued.stream().allMatch(ops -> ops.client().isTerminated()) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(issued).as("every download's client is terminated once its download settled")
                          .allMatch(ops -> ops.client().isTerminated());
    }

    /// CONTROL — an intact cached jar is a cache hit: no second download.
    @Test
    void intactCachedJar_isACacheHit() {
        assertThat(repository().locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        assertThat(repository().locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();

        assertThat(jarRequests.get()).isEqualTo(1);
    }

    private RemoteRepository repository() {
        return RemoteRepository.remoteRepository("wiring-test", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Artifact artifact() {
        return Artifact.artifact("org.example:demo:1.0.0").unwrap();
    }

    private Path cachedJar() {
        return localRepo.resolve("org/example/demo/1.0.0/demo-1.0.0.jar");
    }
}
