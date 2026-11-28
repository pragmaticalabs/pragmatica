// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #3858 invariant 2: a release moves a pin between two slots, so it and every other write run under the registry's one monitor;
/// no request can join a pin that is half-way out of the live slot. The probe holds the monitor on purpose: a request for
/// `PAUSED` is suspended inside the registry's read of the boot token, which runs under the monitor. A write of another node
/// that finishes meanwhile did not take the monitor. A write that is excluded finishes after the request is let go.
class DrainCommandRegistryMonitorTest {
    private static final NodeId PAUSED = NodeId.nodeId("paused").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final long DECIDE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final CompletableFuture<Void> entered = new CompletableFuture<>();
    private final CompletableFuture<Void> letGo = new CompletableFuture<>();
    private final DrainCommandRegistry registry = pausingRegistry();

    private DrainCommandRegistry pausingRegistry() {
        var created = DrainCommandRegistry.drainCommandRegistry();

        created.bindSubject(DrainSubject.drainSubject(this::pauseOnPaused, _ -> 0L));
        return created;
    }

    private long pauseOnPaused(NodeId node) {
        if (node.equals(PAUSED)) {
            entered.complete(null);
            letGo.join();
        }
        return 0L;
    }

    @AfterEach
    void tearDown() {
        letGo.complete(null);
    }

    private void assertExcludedWhileARequestHoldsTheMonitor(Consumer<DrainCommandRegistry> write) {
        var holder = CompletableFuture.runAsync(() -> registry.requestDrain(PAUSED));
        entered.orTimeout(5, TimeUnit.SECONDS).join();
        var writer = new AtomicReference<Thread>();
        var other = CompletableFuture.runAsync(() -> runRecording(write, writer));

        assertThat(blocksOnTheMonitor(other, writer)).as("the write ran while a request held the registry's monitor").isTrue();

        letGo.complete(null);
        other.orTimeout(5, TimeUnit.SECONDS).join();
        holder.orTimeout(5, TimeUnit.SECONDS).join();
    }

    private void runRecording(Consumer<DrainCommandRegistry> write, AtomicReference<Thread> writer) {
        writer.set(Thread.currentThread());
        write.accept(registry);
    }

    /// Decided by the writer's own state, never by a pause: `true` once it is blocked on a monitor, `false` once it finished
    /// without blocking. A slow host cannot turn an unexcluded write into a pass.
    private static boolean blocksOnTheMonitor(CompletableFuture<Void> write, AtomicReference<Thread> writer) {
        var deadline = System.nanoTime() + DECIDE_NANOS;

        while (!write.isDone() && System.nanoTime() < deadline) {
            if (isBlocked(writer.get())) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static boolean isBlocked(Thread thread) {
        return thread != null && thread.getState() == Thread.State.BLOCKED;
    }

    @Test
    void request_isExcludedByARequestInFlight() {
        assertExcludedWhileARequestHoldsTheMonitor(r -> r.requestDrain(OTHER));
    }

    @Test
    void release_isExcludedByARequestInFlight() {
        var live = registry.requestDrain(OTHER).unwrap();

        assertExcludedWhileARequestHoldsTheMonitor(r -> r.release(live));
    }

    @Test
    void clearDrain_isExcludedByARequestInFlight() {
        var live = registry.requestDrain(OTHER).unwrap();

        assertExcludedWhileARequestHoldsTheMonitor(r -> r.clearDrain(live));
    }

    @Test
    void clearEndedLife_isExcludedByARequestInFlight() {
        registry.requestDrain(OTHER);

        assertExcludedWhileARequestHoldsTheMonitor(r -> r.clearEndedLife(OTHER, 0L));
    }

    @Test
    void clearPriorLives_isExcludedByARequestInFlight() {
        registry.requestDrain(OTHER);

        assertExcludedWhileARequestHoldsTheMonitor(r -> r.clearPriorLives(OTHER, 1L));
    }
}
