// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.AppendLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), D-01 node-level half: a WAL fail-stop neither moves ownership nor shows in
/// the node's metrics. (The component-level alarm half is `WalFailStopObservabilityTripwireTest`.)
///
/// The owner's real WAL has its file channel closed (the same stand-in for an I/O failure the component test uses), so the
/// next frame write fails and `AppendLog` stops for good. On a 3-node cluster:
/// - Tripwire: every later publish through the owner fails (control: the fail-stop took), `walSnapshot().failStopped` is
///   true (control: it IS exposed), yet for 60 s the committed owner of the partition is unchanged, and the owner's
///   Prometheus scrape (`/api/v1/metrics/prometheus`) has no line matching one shared case-insensitive regex (`wal` and `fail|stop` on one line) (control: the
///   scrape works and carries other `aether` lines).
/// - `@Disabled` acceptance, two tests so that a fix doing only one shows: (a) `stepsDown`, (b) `isGauged`; both - the committed owner changes within 60 s, and a gauge line naming the WAL
///   fail-stop appears. Which gauge name and which step-down mechanism is the overhaul's choice; the regex is the contract.
@PortBudget
class EmberWalFailStopNodeTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long WINDOW_MS = 60_000L;
    /// ONE matcher for both halves: any scrape line naming the WAL together with a failure or a stop, whatever the metric is
    /// called. A narrower pattern would let a fix with an unexpected name turn the real assertion green and leave the tripwire green.
    private static final String GAUGE_REGEX = "(?im)^(?=.*wal)(?=.*(fail|stop)).*$";
    private static final Pattern WAL_FAIL_GAUGE = Pattern.compile(GAUGE_REGEX);

    /// The matching sample lines (comment lines excluded) whose value is not zero: a gauge exposed at 0 from boot is not an alarm.
    private static boolean nonZeroGauge(String scrape) {
        return scrape.lines().filter(line -> !line.startsWith("#")).filter(line -> WAL_FAIL_GAUGE.matcher(line).find()).anyMatch(EmberWalFailStopNodeTripwireTest::isNonZero);
    }

    private static boolean isNonZero(String line) {
        var value = line.substring(line.lastIndexOf(' ') + 1);

        try {
            return Double.parseDouble(value) != 0.0;
        } catch (NumberFormatException e) {
            return true;
        }
    }
    private static final String STREAM = "d01";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private EmberCluster cluster;
    private Path dataDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(600)
    void walFailStopOnTheOwner_tripwire_neitherMovesOwnershipNorShowsInTheMetrics() {
        var owner = failStopTheOwner();
        var ownerId = owner.self().id();
        var scrapeBefore = scrape(owner);

        assertThat(scrapeBefore).as("control: the scrape works and carries aether lines").containsPattern("(?m)^aether");
        assertThat(publishFails(owner)).as("control: the owner's partition is unwritable after the fail-stop").isTrue();
        assertThat(failStopped(owner)).as("control, 1b-edge: WalStats.failStopped IS exposed").isTrue();
        sleepQuietly(WINDOW_MS);
        assertThat(committedOwner()).as("D-01 fixed? The committed owner changed after the owner's WAL fail-stop. Delete this tripwire and enable "
                                        + "walFailStopOnTheOwner_stepsDown and walFailStopOnTheOwner_isGauged (#2201)")
                                    .isEqualTo(Option.some(ownerId));
        assertThat(nonZeroGauge(scrapeBefore)).as("control: before the fail-stop no scrape line matching " + GAUGE_REGEX + " has a non-zero value (no line, or a line at 0)").isFalse();
        assertThat(nonZeroGauge(scrape(owner))).as("D-01 gauge fixed? A scrape line matching " + GAUGE_REGEX + " has a non-zero value after the fail-stop").isFalse();
    }

    @Test
    @Disabled("D-01 (#2201): enable when a WAL fail-stop steps the owner down; RED today: the committed owner never changes")
    @Timeout(600)
    void walFailStopOnTheOwner_stepsDown() {
        var owner = failStopTheOwner();
        var ownerId = owner.self().id();

        publishFails(owner);
        awaitUntil(() -> !committedOwner().equals(Option.some(ownerId)) && committedOwner().isPresent(), WINDOW_MS, "the committed owner moved off " + ownerId + " after its WAL fail-stop");
    }

    @Test
    @Disabled("D-01 (#2201): enable when a WAL fail-stop is gauged; RED today: no scrape line matching the shared regex has a non-zero value")
    @Timeout(600)
    void walFailStopOnTheOwner_isGauged() {
        var owner = failStopTheOwner();

        publishFails(owner);
        awaitUntil(() -> nonZeroGauge(scrape(owner)), WINDOW_MS, "a scrape line matching " + GAUGE_REGEX + " has a non-zero value");
    }

    private AetherNode failStopTheOwner() {
        dataDir = StreamTripwireSupport.newDataDir("d01-tripwire");
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, basePort -> StreamTripwireSupport.threeNodes(basePort, "d01", dataDir), START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(STREAM, 1, 100_000L, 3_600_000L));

        var owner = cluster.getNode(StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS).owner().id()).unwrap();
        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);

        assertThat(router.publish(ENGINE_KEY, PARTITION, "before".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()).await(PUBLISH_BOUND).isSuccess())
            .as("control: the owner acknowledges before the fail-stop").isTrue();
        closeWalChannel(owner);

        return owner;
    }

    @SuppressWarnings({"JBCT-EX-01", "unchecked"})
    private static void closeWalChannel(AetherNode owner) {
        try {
            Method walFor = StreamPartitionManager.class.getDeclaredMethod("walFor", String.class, int.class);

            walFor.setAccessible(true);

            var wal = ((Option<AppendLog>) walFor.invoke(owner.streamPartitionManager(), ENGINE_KEY, PARTITION)).unwrap();
            var channel = AppendLog.class.getDeclaredField("channel");

            channel.setAccessible(true);
            ((FileChannel) channel.get(wal)).close();
        } catch (ReflectiveOperationException | IOException e) {
            throw new AssertionError("could not fail-stop the owner's WAL: " + e, e);
        }
    }

    private boolean publishFails(AetherNode owner) {
        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);

        router.publish(ENGINE_KEY, PARTITION, "meets-failure".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()).await(PUBLISH_BOUND);

        return router.publish(ENGINE_KEY, PARTITION, "after".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()).await(PUBLISH_BOUND).isFailure();
    }

    private static boolean failStopped(AetherNode owner) {
        return owner.streamPartitionManager()
                    .walSnapshot()
                    .streams()
                    .stream()
                    .flatMap(stream -> stream.partitions().stream())
                    .anyMatch(view -> view.wal().map(AppendLog.WalStats::failStopped).or(false));
    }

    private Option<String> committedOwner() {
        return StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.owner().id());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String scrape(AetherNode node) {
        var request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + node.managementPort() + "/api/v1/metrics/prometheus")).GET().build();

        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new AssertionError("metrics scrape failed: " + e, e);
        }
    }
}
