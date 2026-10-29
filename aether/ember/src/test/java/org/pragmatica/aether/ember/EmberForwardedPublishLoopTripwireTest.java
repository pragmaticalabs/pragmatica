// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), forwarded publish: C-01 - a forwarded publish runs its WAL durability wait on the
/// transport event loop.
///
/// A publish made through a NON-owner's write router (the path every entry point shares, #1263) reaches the owner as a
/// `PublishForward`, which `QuicClusterNetwork` routes inline on the QUIC loop thread that decoded it. The inbound filter
/// records which thread delivers each `PublishForward`; a sampler then reads that thread's stack while 400 forwarded publishes
/// run. The instrument is sampling, so the test asserts a lower bound on what it caught, not a rate: at least one sample finds
/// the loop thread parked inside `StreamForwardHandler.onPublishForward`. A sampler that missed every stall would fail the
/// tripwire loudly, not pass it.
/// - Tripwire: at least one such sample.
/// - `@Disabled` acceptance: no sample ever finds a transport thread inside `onPublishForward` parked.
///
/// One cluster for the class (with data directories: the WAL is what is waited on), booted by the first test that runs.
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberForwardedPublishLoopTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final int FORWARDED_PUBLISHES = 400;
    private static final String STREAM = "c04";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private final Set<Thread> forwardThreads = ConcurrentHashMap.newKeySet();
    private final AtomicLong forwardsSeen = new AtomicLong();

    private EmberCluster cluster;
    private Path dataDir;
    private AetherNode owner;
    private AetherNode replica;
    private Throwable bootFailure;

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(600)
    void forwardedPublish_tripwire_parksTheTransportLoopThreadInsideTheHandler() {
        boot();

        var samples = sampleForwardedPublishes();

        assertThat(samples.forwardsSeen()).as("control: the owner received the forwarded publishes (PublishForward messages seen)").isGreaterThanOrEqualTo((long) FORWARDED_PUBLISHES);
        assertThat(samples.transportThreads()).as("control: PublishForward was delivered on netty/QUIC event-loop threads").isNotEmpty()
                                              .allSatisfy(name -> assertThat(name).contains("EventLoop"));
        assertThat(samples.parkedInHandler()).as("C-01 fixed? No sample of the transport thread found it parked inside StreamForwardHandler.onPublishForward "
                                                 + "(samples inside the handler: %d, example frames: %s). Delete this tripwire and enable "
                                                 + "forwardedPublish_neverParksTheTransportLoopThread (#2201)",
                                                 samples.insideHandler(),
                                                 samples.example())
                                             .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Disabled("C-01 (#2201): enable when the forwarded publish's WAL wait leaves the transport thread; RED today: the loop thread is parked inside onPublishForward")
    @Timeout(600)
    void forwardedPublish_neverParksTheTransportLoopThread() {
        boot();

        var samples = sampleForwardedPublishes();

        assertThat(samples.forwardsSeen()).as("control: the owner received the forwarded publishes").isGreaterThanOrEqualTo((long) FORWARDED_PUBLISHES);
        assertThat(samples.parkedInHandler()).as("samples that found a transport thread parked inside onPublishForward (example frames: %s)", samples.example()).isZero();
    }

    private record Samples(long forwardsSeen, Set<String> transportThreads, long insideHandler, long parkedInHandler, String example) {}

    private void boot() {
        if (bootFailure != null) {
            throw new AssertionError("the shared cluster failed to boot for an earlier test: " + bootFailure, bootFailure);
        }

        if (owner != null) {
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
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(STREAM, 1, 100_000L, 3_600_000L));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);

        owner = cluster.getNode(record.owner().id()).unwrap();
        replica = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).findFirst().orElseThrow();
        cluster.allNodes().forEach(this::observe);
    }

    /// A pure observer on every node's inbound path: it counts and records, and lets every message through.
    private void observe(AetherNode node) {
        node.setInboundFaultFilter((_, message) -> observed(message));
    }

    private boolean observed(Object message) {
        if (message instanceof StreamForwardMessage.PublishForward forward && ENGINE_KEY.equals(forward.streamName())) {
            forwardsSeen.incrementAndGet();
            forwardThreads.add(Thread.currentThread());
        }

        return true;
    }

    private Samples sampleForwardedPublishes() {
        var forwardsBefore = forwardsSeen.get();
        var running = new AtomicBoolean(true);
        var inside = new AtomicLong();
        var parked = new AtomicLong();
        var example = new AtomicReference<>("none");
        var sampler = Thread.ofPlatform().name("tripwire-stack-sampler").start(() -> sampleLoop(running, inside, parked, example));
        var router = StreamTripwireSupport.component(replica, "streamWriteRouter", StreamWriteRouter.class);

        for (var i = 0; i < FORWARDED_PUBLISHES; i++) {
            var offset = router.publish(ENGINE_KEY, PARTITION, ("forwarded-" + i).getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()).await(PUBLISH_BOUND);

            assertThat(offset.isSuccess()).as("forwarded publish %d via the non-owner %s: %s", i, replica.self().id(), offset).isTrue();
        }
        running.set(false);
        StreamTripwireSupport.joinQuietly(sampler);

        var names = new TreeSet<String>();

        forwardThreads.forEach(thread -> names.add(thread.getName()));

        return new Samples(forwardsSeen.get() - forwardsBefore, names, inside.get(), parked.get(), example.get());
    }

    private void sampleLoop(AtomicBoolean running,
                            AtomicLong inside,
                            AtomicLong parked,
                            AtomicReference<String> example) {
        while (running.get()) {
            for (var thread : List.copyOf(forwardThreads)) {
                sampleOne(thread, inside, parked, example);
            }
            Thread.onSpinWait();
        }
    }

    private static void sampleOne(Thread thread, AtomicLong inside, AtomicLong parked, AtomicReference<String> example) {
        var frames = thread.getStackTrace();
        var inHandler = Arrays.stream(frames).anyMatch(frame -> frame.getMethodName().equals("onPublishForward"));

        if (!inHandler) {
            return;
        }
        inside.incrementAndGet();
        if (Arrays.stream(frames).limit(12).anyMatch(EmberForwardedPublishLoopTripwireTest::isBlockingFrame)) {
            parked.incrementAndGet();
            example.compareAndSet("none", Arrays.stream(frames).limit(8).map(StackTraceElement::toString).collect(Collectors.joining(" <- ")));
        }
    }

    private static boolean isBlockingFrame(StackTraceElement frame) {
        var method = frame.getMethodName();

        return method.equals("park") || method.equals("parkNanos") || method.equals("await") || method.equals("force") || method.equals("force0");
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c04-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "c04", dataDir);
    }
}
