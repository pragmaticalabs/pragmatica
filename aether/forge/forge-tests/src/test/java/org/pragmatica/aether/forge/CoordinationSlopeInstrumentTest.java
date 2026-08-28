/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #591 — validates the coordination-load INSTRUMENT against a live cluster.
///
/// `aether/tests/integration/coordination_slope.py` samples the shipping system's coordination load:
/// it differences `quic_messages_sent_total` + `quic_messages_received_total` per CORE node and reads
/// `cpu.usage` / `heap.used` alongside. The remote sweep this feeds (4 → 8 → 12 workers) is expensive
/// and infrequent, so a sampler that silently reports zeros — a renamed key, a moved endpoint, a
/// changed payload shape — would not be caught until the numbers were already in the book.
///
/// That is the failure family this repo kept hitting: a positive control that swallowed its own
/// rejected trigger and then indicted the reconciler; a reactor verdict that printed BUILD SUCCESS
/// over failing tests; a summary that read a previous run's XML. **A verification instrument gets
/// validated against its own failure modes before its output is trusted.** This test is that
/// validation, made repeatable instead of a one-off: it pins the sampler's assumptions to a REAL node
/// and then runs the sampler itself end to end.
///
/// Three nodes, not the sweep's twelve: the point is the instrument's contract with the management
/// API, which one core answers as well as twelve, and a 3-node in-JVM cluster stays far below the
/// ~8-node ceiling where SWIM probe-acks starve (`CommunityFormationProbeTest` is @Disabled above it).
///
/// Not `@Tag("Heavy")` deliberately — the endpoint contract is exactly what should break the build in
/// CI the day a DTO is renamed, and 3 nodes is the same cost as `ClusterFormationTest`. The leg that
/// shells out to the sampler is assumption-guarded so a machine without `python3` skips that half
/// rather than failing for an unrelated reason.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoordinationSlopeInstrumentTest {
    private static final Logger log = LoggerFactory.getLogger(CoordinationSlopeInstrumentTest.class);

    private static final int CORES = 3;
    private static final int BASE_PORT = 20000;
    private static final int BASE_MGMT_PORT = 20100;
    private static final int BASE_APP_HTTP_PORT = 20200;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(500);

    /// The two counters the sampler differences. Named here so a rename breaks THIS test with a clear
    /// message rather than silently flattening the slope in a remote run weeks later.
    private static final List<String> REQUIRED_TRANSPORT_KEYS =
        List.of("quic_messages_sent_total", "quic_messages_received_total");

    private static final List<String> REQUIRED_LOAD_KEYS = List.of("cpu.usage", "heap.used");

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "cslope");
        cluster.start().await().onFailure(cause -> {
            throw new AssertionError("Cluster start failed: " + cause.message());
        });
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    /// The sampler's contract with `GET /api/v1/metrics/transport`, checked on EVERY core because the
    /// route is LOCAL — each node answers for itself, which is what makes per-core sampling meaningful
    /// and what makes a single node's reply insufficient evidence.
    @Test
    void everyCoreServesTheTransportCountersTheSamplerDifferences() {
        for (var node : cluster.status().nodes()) {
            var body = get(node.mgmtPort(), "/api/v1/metrics/transport");

            assertThat(body)
                .as("node %s must answer /api/v1/metrics/transport", node.id())
                .isNotBlank();

            for (var key : REQUIRED_TRANSPORT_KEYS) {
                assertThat(body)
                    .as("node %s: /api/v1/metrics/transport must carry %s — the sampler differences it, and "
                        + "a missing key would make a busy node read as perfectly idle", node.id(), key)
                    .contains("\"" + key + "\"");
            }
        }
    }

    /// The sampler's contract with `GET /api/v1/metrics`: a `load` map keyed by node id, carrying the CPU
    /// and heap fields it reports alongside the message rate.
    @Test
    void everyCoreServesTheLoadFieldsTheSamplerReads() {
        for (var node : cluster.status().nodes()) {
            var body = get(node.mgmtPort(), "/api/v1/metrics");

            assertThat(body)
                .as("node %s: /api/v1/metrics must carry a load map", node.id())
                .contains("\"load\"");

            for (var key : REQUIRED_LOAD_KEYS) {
                assertThat(body)
                    .as("node %s: /api/v1/metrics load entries must carry %s", node.id(), key)
                    .contains("\"" + key + "\"");
            }
        }
    }

    /// Counters must be MONOTONIC across two reads. The sampler treats a decrease as a void sample
    /// (a node restarted mid-window) rather than reporting a negative rate; this pins that the
    /// underlying counters really are cumulative rather than per-interval, which is the assumption
    /// that makes differencing valid at all.
    @Test
    void transportCountersAreCumulativeSoDifferencingIsValid() {
        var port = cluster.status().nodes().getFirst().mgmtPort();
        var first = messagesTotal(port);

        await().pollDelay(Duration.ofSeconds(3)).timeout(Duration.ofSeconds(8)).until(() -> true);

        var second = messagesTotal(port);

        assertThat(second)
            .as("counters must not decrease between reads — the sampler differences them, which is only "
                + "meaningful for cumulative counters (first=%d second=%d)", first, second)
            .isGreaterThanOrEqualTo(first);
        log.info("CSLOPE-INSTRUMENT: cumulative check first={} second={} delta={}", first, second, second - first);
    }

    /// The sampler itself, end to end against the live cluster. Everything above pins its assumptions;
    /// this pins that the program built on them actually produces a row.
    @Test
    void samplerProducesAValidRowAgainstTheLiveCluster() throws Exception {
        var script = repoRoot().resolve("aether/tests/integration/coordination_slope.py");

        Assumptions.assumeTrue(Files.isRegularFile(script), "sampler not found at " + script);
        Assumptions.assumeTrue(python3Available(), "python3 unavailable — skipping the end-to-end sampler leg");

        var endpoints = cluster.status()
                               .nodes()
                               .stream()
                               .map(n -> "http://localhost:" + n.mgmtPort())
                               .toList();
        var nodeIds = cluster.status()
                             .nodes()
                             .stream()
                             .map(EmberCluster.NodeStatus::id)
                             .toList();

        var process = new ProcessBuilder("python3", script.toString(),
                                         "--cores", String.join(",", endpoints),
                                         "--node-ids", String.join(",", nodeIds),
                                         "--workers", "0",
                                         "--window", "5")
            .redirectErrorStream(true)
            .start();
        var output = new String(process.getInputStream().readAllBytes());
        var finished = process.waitFor(90, TimeUnit.SECONDS);

        log.info("CSLOPE-INSTRUMENT sampler output:\n{}", output);
        assertThat(finished).as("sampler must terminate").isTrue();
        assertThat(process.exitValue())
            .as("sampler must exit 0 against a healthy cluster; output was:\n%s", output)
            .isZero();
        assertThat(output)
            .as("sampler must emit the fields the slope table is built from")
            .contains("totalCoreMessagesPerSecond")
            .contains("perCoreMessagesPerSecond")
            .contains("meanCoreCpuUsage")
            .contains("anyCoreSaturated");
        assertThat(output)
            .as("the sampler must report the cores it was given, not a subset")
            .contains("\"cores\": " + CORES);
    }

    private long messagesTotal(int mgmtPort) {
        var body = get(mgmtPort, "/api/v1/metrics/transport");

        return REQUIRED_TRANSPORT_KEYS.stream()
                                      .mapToLong(key -> extractLong(body, key))
                                      .sum();
    }

    /// Deliberately strict: a key the sampler depends on must be present and numeric. Returning 0 for
    /// an absent key is precisely the silent-zero failure this class exists to prevent.
    private static long extractLong(String json, String key) {
        var marker = "\"" + key + "\"";
        var at = json.indexOf(marker);

        if (at < 0) {
            throw new AssertionError("transport payload has no " + key + "; payload=" + json);
        }

        var digits = json.substring(at + marker.length())
                         .replaceFirst("^\\s*:\\s*", "")
                         .split("[^0-9]", 2)[0];

        if (digits.isEmpty()) {
            throw new AssertionError("value of " + key + " is not numeric; payload=" + json);
        }

        return Long.parseLong(digits);
    }

    private String get(int mgmtPort, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + mgmtPort + path))
                                 .timeout(Duration.ofSeconds(10))
                                 .GET()
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or("");
    }

    /// The module runs with its own directory as the working directory; the sampler lives at the repo
    /// root. Walk up rather than hard-coding a relative depth.
    private static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
            dir = dir.getParent();
        }

        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private static boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
