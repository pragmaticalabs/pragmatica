// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// F1a (cloud run 1, `Concurrent_deploy`) — the END-TO-END pin. A stream created while every node's only
/// `reshuffle_concurrency` slot is taken has replicas that HOLD its partition but cannot materialize it, and
/// those replicas used to answer the owner's watermark probe `PARTITION_NOT_LOCAL`. The owner's
/// `PartitionBackfill` read that as "unreachable" and stayed non-authoritative for the whole 20 s source wait.
/// With the typed "held, not materialized" reply it self-promotes on the first attempt.
///
/// What it drives: a real three-node in-JVM cluster with `reshuffle_concurrency = 1`; an `occupier` stream with
/// three partitions is created first, and a second stream immediately after it. The pin is the owner's
/// self-promotion for that stream (the `PartitionBackfill` WARN, captured through log4j), which must arrive
/// inside [#PROMOTION_BOUND_MS], where the defect costs the 20 s wait.
///
/// **The `#1555` promotion gate is NOT what this pins, and the spec's premise that it is was measured false.**
/// The gate probes over the catch-up read class, which already read a non-holder as `-1`; with the probe fix
/// reverted the gate still opened 15 ms after the first demand (cloudbb-d1, 2026-09-30, mutant run in the
/// f-backfill report). [#ownerGateOpensPromptly] is kept as the incident's symptom check and is green either way.
///
/// **Publish.** The stream's confirmation factor is 2 and its replicas stay paced while the slots are busy, so
/// the first publishes timed out at the confirmation barrier (`REPLICATION_TIMEOUT`, measured before F1f). A
/// replica's first replicate append at offset 0 of an empty partition is no longer paced, and the publish through
/// every node is acknowledged inside [#PUBLISH_BOUND_MS].
///
/// [unverified: the slot is still taken at the second stream's create] It is decided by the reshuffle tick, not
/// by the test. The run log shows `held[0] ... paced: node already has 1 partitions in materialize+backfill`
/// on both replicas, and the mutation that reverts the probe is what shows the setup reaches the defect.
class EmberHeldPartitionPublishTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = EmberTestPorts.POOL_FIRST;
    private static final int LAST_CANDIDATE_BASE = EmberTestPorts.POOL_LAST;
    private static final int CANDIDATE_STEP = EmberTestPorts.POOL_STEP;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long LEADER_ELECTION_BUDGET_MS = 60_000L;
    /// The defect costs the 20 s source wait (`sourceWaitBound`); an owner that opens inside this bound did not wait for it.
    private static final long ACTIVATION_BOUND_MS = 8_000L;
    /// Same bound for the owner's `PartitionBackfill` self-promotion: a retry tick or two is fine, the 20 s wait is not.
    private static final long PROMOTION_BOUND_MS = 8_000L;
    private static final long PUBLISH_BOUND_MS = 10_000L;
    private static final long OWNERSHIP_BOUND_MS = 30_000L;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final String HELD_ENGINE_KEY = NAMESPACE + ":held:" + VERSION;
    private static final String SELF_PROMOTION = "Backfill " + HELD_ENGINE_KEY + "[0]: owner self-promoting to CAUGHT_UP";

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    private record Response(int status, String body, long elapsedMs) {}

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(420)
    void ownerPromotion_ofAStreamWhoseReplicasArePacedByReshuffleConcurrency_doesNotWaitForTheSourceWait() {
        var backfillWarnings = new CopyOnWriteArrayList<String>();
        var detach = warningsOf(PartitionBackfill.class, backfillWarnings);

        try {
            cluster = EmberTestPorts.startedCluster(PORTS, this::clusterWithOneReshuffleSlot, START_BOUND);
            awaitLeader();

            var occupier = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/occupier/" + VERSION, "{\"partitions\":3}");
            var held = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/held/" + VERSION, "{\"partitions\":1}");
            var created = System.nanoTime();

            assertThat(occupier.status()).as("occupier create: %s", occupier.body()).isBetween(200, 299);
            assertThat(held.status()).as("held create: %s", held.body()).isBetween(200, 299);

            var promotedAfterMs = awaitSelfPromotion(backfillWarnings, created);

            assertThat(promotedAfterMs).as("the owner of %s must self-promote inside %dms, not after the 20s source wait; "
                                           + "PartitionBackfill warnings seen: %s",
                                           HELD_ENGINE_KEY,
                                           PROMOTION_BOUND_MS,
                                           backfillWarnings.stream().filter(line -> line.contains(HELD_ENGINE_KEY)).toList())
                                       .isLessThan(PROMOTION_BOUND_MS);
            ownerGateOpensPromptly();
            everyNodePublishesWithinTheBudget();
        } finally {
            detach.run();
        }
    }

    /// The spec's end-to-end pin: with the slot still held, a publish (confirmation_factor 2) through each node's
    /// management API is acknowledged inside the forwarder budget. Its replicas are paced, so it is F1f (an empty
    /// partition's first replicate append is not paced) that lets the confirmation barrier complete.
    private void everyNodePublishesWithinTheBudget() {
        for (var node : cluster.status().nodes()) {
            var published = post(node.mgmtPort(),
                                 "/api/v1/streams/" + NAMESPACE + "/held/" + VERSION + "/publish",
                                 "{\"data\":\"held-" + node.id() + "\"}");

            assertThat(published.status()).as("publish via %s after %dms: %s", node.id(), published.elapsedMs(), published.body())
                                          .isBetween(200, 299);
            assertThat(published.elapsedMs()).as("publish via %s inside the forwarder budget", node.id())
                                             .isLessThan(PUBLISH_BOUND_MS);
        }
    }

    /// The incident's symptom, kept as a check: the owner's serving read passes the #1555 gate promptly. Green with or
    /// without the probe fix (see the class doc), so it pins nothing about F1a.
    private void ownerGateOpensPromptly() {
        var owner = awaitCommittedOwner(HELD_ENGINE_KEY);

        assertThat(awaitOwnerGate(owner)).as("the owner %s of %s must open its promotion gate inside %dms",
                                             owner.self().id(),
                                             HELD_ENGINE_KEY,
                                             ACTIVATION_BOUND_MS)
                                         .isLessThan(ACTIVATION_BOUND_MS);
    }

    /// Milliseconds from `createdNanos` until the owner's self-promotion WARN for the held stream is seen, or twice the
    /// bound when it never is.
    private static long awaitSelfPromotion(List<String> warnings, long createdNanos) {
        var deadline = System.currentTimeMillis() + 2 * PROMOTION_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            if (warnings.stream().anyMatch(line -> line.contains(SELF_PROMOTION))) {
                return elapsedMs(createdNanos);
            }

            sleepQuietly(50L);
        }

        return 2 * PROMOTION_BOUND_MS;
    }

    /// Capture the formatted WARN messages one class's logger emits; the returned runnable detaches the appender.
    @SuppressWarnings("JBCT-RET-01")
    private static Runnable warningsOf(Class<?> loggerOwner, List<String> sink) {
        var loggerName = loggerOwner.getName();
        var context = (LoggerContext) LogManager.getContext(false);
        var loggerConfig = context.getConfiguration().getLoggerConfig(loggerName);
        var appender = new AbstractAppender("warn-capture-" + loggerOwner.getSimpleName(),
                                            null,
                                            PatternLayout.createDefaultLayout(),
                                            true,
                                            Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && loggerName.equals(event.getLoggerName())) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.WARN, null);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        };
    }

    /// The node the committed ownership record names for partition 0 — the one whose append path the gate guards.
    private AetherNode awaitCommittedOwner(String engineKey) {
        var deadline = System.currentTimeMillis() + OWNERSHIP_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var owner = committedOwner(engineKey);

            if (owner.isPresent()) {
                return owner.unwrap();
            }

            sleepQuietly();
        }

        throw new AssertionError("no committed owner for " + engineKey + " within " + OWNERSHIP_BOUND_MS + "ms");
    }

    private Option<AetherNode> committedOwner(String engineKey) {
        for (var node : cluster.allNodes()) {
            var record = ownerRecord(node, engineKey);

            if (record.isPresent()) {
                return cluster.getNode(record.unwrap().owner().id());
            }
        }

        return Option.none();
    }

    private static Option<StreamPartitionOwnershipValue> ownerRecord(AetherNode node, String engineKey) {
        return node.kvStore()
                   .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(engineKey, 0),
                             StreamPartitionOwnershipValue.class);
    }

    /// Milliseconds until the owner's serving read passes the promotion gate (`readServing` refuses with
    /// `OwnerNotActivated` until the owner has caught up), or [#ACTIVATION_BOUND_MS] doubled when it never does.
    private static long awaitOwnerGate(AetherNode owner) {
        var started = System.nanoTime();
        var deadline = System.currentTimeMillis() + 2 * ACTIVATION_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            if (owner.streamPartitionManager().readServing(HELD_ENGINE_KEY, 0, 0L, 1).isSuccess()) {
                return elapsedMs(started);
            }

            sleepQuietly(50L);
        }

        return 2 * ACTIVATION_BOUND_MS;
    }

    private EmberCluster clusterWithOneReshuffleSlot(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "heldpub");

        built.withDataBaseDir(dataDir);
        built.withStreamingConfig(StreamingConfig.streamingConfig(TimeSpan.timeSpan(5).seconds(),
                                                                  TimeSpan.timeSpan(2).seconds(),
                                                                  StreamingConfig.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                                                  StreamingConfig.DEFAULT_READ_LINEARIZATION,
                                                                  1));

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_ELECTION_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly();
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Response post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();
        var started = System.nanoTime();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body(), elapsedMs(started));
        } catch (Exception e) {
            // A transport failure is not a 2xx and is reported verbatim rather than read as a refusal.
            return new Response(-1, e.toString(), elapsedMs(started));
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        sleepQuietly(500L);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
