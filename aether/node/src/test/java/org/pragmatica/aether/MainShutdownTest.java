// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #838 review round 1 pins the expiry path of [Main#shutdownNode] directly, without touching the real
/// 30s [Main#SHUTDOWN_TIMEOUT] or the shared log4j2 context in the surefire fork:
///   - BLOCKING 1: `System.exit()` from a shutdown hook deadlocks (proven by the reviewer), so the expiry
///     path halts through an injected `IntConsumer` seam instead -- these tests assert it is invoked
///     (or not) rather than exercising a real JVM halt.
///   - SHOULD-FIX: [ThreadMXBean#dumpAllThreads] omits virtual threads; [Main#captureThreadDump] must
///     include them.
class MainShutdownTest {

    @Nested
    class ShutdownNodeGate {

        @Test
        void shutdownNode_logsAndDoesNotHalt_whenStopResolvesInTime() {
            var node = Mockito.mock(AetherNode.class);
            Mockito.when(node.stop()).thenReturn(Promise.success(unit()));

            var flushed = new AtomicBoolean(false);
            var haltedWith = new AtomicInteger(-1);

            Main.shutdownNode(node, timeSpan(200).millis(), () -> flushed.set(true), haltedWith::set);

            assertFalse(flushed.get(), "log flush must run only on the timeout path, not on a clean stop");
            assertEquals(-1, haltedWith.get(), "halt must not be invoked when stop() resolves in time");
        }

        @Test
        void shutdownNode_flushesLogsThenHalts_withTimeoutExitCode_whenStopNeverResolves() {
            var node = Mockito.mock(AetherNode.class);
            Mockito.when(node.stop()).thenReturn(Promise.promise()); // never resolves

            var flushed = new AtomicBoolean(false);
            var haltedWith = new AtomicInteger(-1);

            Main.shutdownNode(node, timeSpan(200).millis(), () -> flushed.set(true), haltedWith::set);

            assertTrue(flushed.get(), "log flush must run BEFORE halt -- halt() runs no appender flush of its own");
            assertEquals(Main.SHUTDOWN_TIMEOUT_EXIT_CODE, haltedWith.get(),
                         "the shutdown-timeout path must use its own exit code, distinct from the "
                         + "drain-completed self-exit's code 2 (AetherNode.java:415,437)");
        }
    }

    @Nested
    class ThreadDumpGate {

        @Test
        void captureThreadDump_includesAParkedVirtualThread() throws Exception {
            var latch = new CountDownLatch(1);
            var markerName = "mainshutdowntest-marker-vt-" + System.nanoTime();
            var vt = Thread.ofVirtual().name(markerName).start(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            try {
                // Give the scheduler a moment to actually mount and park the virtual thread before dumping.
                Thread.sleep(150);

                List<String> dump = Main.captureThreadDump();

                assertTrue(dump.stream().anyMatch(line -> line.contains(markerName)),
                           "HotSpotDiagnosticMXBean#dumpThreads must include virtual threads -- "
                           + "ThreadMXBean#dumpAllThreads (the prior implementation) does not");
            } finally {
                latch.countDown();
                vt.join();
            }
        }
    }
}
