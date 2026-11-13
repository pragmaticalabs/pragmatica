// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.AppendLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;

/// Streams overhaul tripwire, step 2 (#2201), forwarded publish: C-01 - a forwarded publish does synchronous disk I/O on the
/// transport event loop.
///
/// A publish made through a NON-owner's write router (the path every entry point shares, #1263) reaches the owner as a
/// `PublishForward`, which `QuicClusterNetwork` routes inline on the QUIC loop thread that decoded it. #2952 took the WAL
/// group-commit wait off that thread, but the FIRST append of a fresh owner epoch still records the epoch on the loop thread:
/// `AppendLog.writeAttributed` -> `EpochHistory.persist` -> `FileChannel.force` (the sidecar file, then its directory), under
/// the epoch history's lock. Every later append of the epoch skips it, so the stall is one per epoch and a stack sampler lands
/// on it only by luck (#3128: 0 of 1875 samples under CI load).
///
/// The instrument is therefore not a sampler: `AppendLog.epochPersistProbe` (test-only) runs on the thread that enters
/// `EpochHistory.persist`, and the probe records that thread and its stack. Each test publishes through a FRESH stream, so its
/// first forwarded publish records a fresh epoch every time.
/// - Tripwire: `persist` WAS entered on a transport event-loop thread from inside `StreamForwardHandler.onPublishForward`.
/// - `@Disabled` acceptance: `persist` is never entered on a transport thread inside `onPublishForward`.
///
/// One cluster for the class (with data directories: the epoch sidecar and the WAL live there), booted by the first test that runs.
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberForwardedPublishLoopTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final int FORWARDED_PUBLISHES = 20;

    private final ConcurrentLinkedQueue<Persist> persists = new ConcurrentLinkedQueue<>();
    private final AtomicLong forwardsSeen = new AtomicLong();

    private EmberCluster cluster;
    private Path dataDir;
    private Throwable bootFailure;
    private volatile String watchedKey = "";

    @AfterAll
    void tearDown() {
        AppendLog.epochPersistProbe(() -> {});
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(600)
    void forwardedPublish_tripwire_persistsTheEpochHistoryOnTheTransportLoopThread() {
        var run = publishThroughFreshStream("c04");

        assertThat(run.forwardsSeen()).as("control: the owner received the forwarded publishes (PublishForward messages seen)").isGreaterThanOrEqualTo((long) FORWARDED_PUBLISHES);
        assertThat(run.persists()).as("control: the fresh stream's first append recorded its epoch (EpochHistory.persist entered)").isNotEmpty();
        assertThat(run.onTransportInHandler()).as("C-01 fixed? EpochHistory.persist (FileChannel.force under the AppendLog lock) was never entered on a transport "
                                                  + "event-loop thread inside StreamForwardHandler.onPublishForward (persists seen: %s). Delete this tripwire and "
                                                  + "enable forwardedPublish_neverPersistsTheEpochHistoryOnTheTransportLoopThread (#2201)",
                                               run.summary())
                                              .isNotEmpty();
    }

    @Test
    @Disabled("C-01 (#2201): enable when the forwarded publish's epoch-history persist leaves the transport thread; RED today: EpochHistory.persist forces the sidecar file and directory on the loop thread")
    @Timeout(600)
    void forwardedPublish_neverPersistsTheEpochHistoryOnTheTransportLoopThread() {
        var run = publishThroughFreshStream("c04b");

        assertThat(run.forwardsSeen()).as("control: the owner received the forwarded publishes").isGreaterThanOrEqualTo((long) FORWARDED_PUBLISHES);
        assertThat(run.persists()).as("control: the fresh stream's first append recorded its epoch (EpochHistory.persist entered)").isNotEmpty();
        assertThat(run.onTransportInHandler()).as("EpochHistory.persist entered on a transport thread inside onPublishForward (persists seen: %s)", run.summary()).isEmpty();
    }

    /// One `persist` entry: the thread that made it, and whether that thread was inside `StreamForwardHandler.onPublishForward`.
    private record Persist(String thread, boolean inHandler, String frames) {}

    private record Run(long forwardsSeen, List<Persist> persists) {
        List<Persist> onTransportInHandler() {
            return persists.stream().filter(entry -> entry.inHandler() && entry.thread().contains("EventLoop")).toList();
        }

        String summary() {
            return persists.stream().map(entry -> entry.thread() + (entry.inHandler() ? " [in onPublishForward] " : " ") + entry.frames()).collect(Collectors.joining(" || "));
        }
    }

    private Run publishThroughFreshStream(String stream) {
        boot();

        var engineKey = engineKey(stream);

        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(stream, 1, 100_000L, 3_600_000L));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, engineKey, ISR_BUDGET_MS);
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var replica = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).findFirst().orElseThrow();
        var router = StreamTripwireSupport.component(replica, "streamWriteRouter", StreamWriteRouter.class);
        var forwardsBefore = forwardsSeen.get();

        persists.clear();
        watchedKey = engineKey;
        AppendLog.epochPersistProbe(this::recordPersist);

        for (var i = 0; i < FORWARDED_PUBLISHES; i++) {
            var offset = StreamTripwireSupport.publishOnceActivated(router, engineKey, ("forwarded-" + i).getBytes(StandardCharsets.UTF_8));

            assertThat(offset.isSuccess()).as("forwarded publish %d via the non-owner %s: %s", i, replica.self().id(), offset).isTrue();
        }
        AppendLog.epochPersistProbe(() -> {});

        return new Run(forwardsSeen.get() - forwardsBefore, List.copyOf(persists));
    }

    /// Runs on whichever thread enters `EpochHistory.persist`; reads its own stack, once.
    private void recordPersist() {
        var thread = Thread.currentThread();
        var frames = thread.getStackTrace();
        var inHandler = Arrays.stream(frames).anyMatch(frame -> frame.getMethodName().equals("onPublishForward"));

        persists.add(new Persist(thread.getName(), inHandler, Arrays.stream(frames).skip(2).limit(8).map(StackTraceElement::toString).collect(Collectors.joining(" <- "))));
    }

    private void boot() {
        if (bootFailure != null) {
            throw new AssertionError("the shared cluster failed to boot for an earlier test: " + bootFailure, bootFailure);
        }

        if (cluster != null) {
            return;
        }

        try {
            bootCluster();
        } catch (Throwable failure) {
            bootFailure = failure;

            throw failure;
        }
    }

    private void bootCluster() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        cluster.allNodes().forEach(this::observe);
    }

    /// A pure observer on every node's inbound path: it counts, and lets every message through.
    private void observe(AetherNode node) {
        node.setInboundFaultFilter((_, message) -> observed(message));
    }

    private boolean observed(Object message) {
        if (message instanceof StreamForwardMessage.PublishForward forward && watchedKey.equals(forward.streamName())) {
            forwardsSeen.incrementAndGet();
        }

        return true;
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c04-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "c04", dataDir);
    }
}
