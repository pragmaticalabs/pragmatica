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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1599 — the wiring through `RemoteRepository` itself, against a local HTTP server and a local repository
/// in a temp directory (`maven.repo.local`, which the locator reads first, so `~/.m2` is never touched).
/// A download is cached with its sidecar, and a cached jar that no longer matches it is fetched again
/// rather than loaded. Mutation that reddens the second test: drop the `ArtifactCache.usable` check from
/// the cache-hit branch of `resolveArtifact`.
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
        assertThat(cachedJar().resolveSibling("demo-1.0.0.jar.sha256")).exists();
        assertThat(jarRequests.get()).isEqualTo(1);
    }

    @Test
    void tornCachedJar_isFetchedAgainInsteadOfLoaded() throws IOException {
        assertThat(repository().locate(artifact()).await(timeSpan(10).seconds()).isSuccess()).isTrue();
        Files.write(cachedJar(), Arrays.copyOf(JAR, JAR.length / 2));

        var location = repository().locate(artifact()).await(timeSpan(10).seconds());

        assertThat(location.isSuccess()).as("located: %s", location).isTrue();
        assertThat(jarRequests.get()).as("#1599: the torn cached jar was re-fetched, not loaded").isEqualTo(2);
        assertThat(Files.readAllBytes(cachedJar())).isEqualTo(JAR);
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
