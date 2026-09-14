// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.EncryptionError;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /// #1052 fix round 2 (N-2): SIGTERM while the DHT encryption-marker check is still retrying runs the
    /// shutdown hook, whose `stop()` ends `start()` with `DhtMarkerCheckAbandoned`. Before the fix that
    /// reached `exitWithError` -- "Failed to start node" at ERROR plus a second `System.exit(1)` from a
    /// non-hook thread -- for what was the operator's own stop. Pins: the abandon cause does NOT exit;
    /// every other start failure still does, with its own message.
    @Nested
    class StartFailure {

        @Test
        void onStartFailure_doesNotExit_whenTheNodeWasStoppedBeforeStartCompleted() {
            var exitedWith = new AtomicReference<String>();

            Main.onStartFailure(new EncryptionError.DhtMarkerCheckAbandoned("artifacts"), exitedWith::set);

            assertNull(exitedWith.get(), "a stop issued by the operator during start must not be turned into "
                                         + "a boot failure exit -- the shutdown hook that stopped the node owns the exit");
        }

        @Test
        void onStartFailure_exitsWithTheCauseMessage_forAnyOtherStartFailure() {
            var exitedWith = new AtomicReference<String>();
            var refusal = new EncryptionError.EncryptedTierRequiresKeyring("artifacts", "k1");

            Main.onStartFailure(refusal, exitedWith::set);

            assertEquals(refusal.message(), exitedWith.get(),
                         "a definite marker refusal must still exit(1) with its own message (#858 kept by #1052)");
        }
    }

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
            // #838 review round 1: pin the ORDER, not just that both eventually happened -- the halt
            // seam itself asserts flushed is already set at the moment it fires, so a regression that
            // halts before (or without) flushing fails here even if both flags end up true afterward.
            IntConsumer haltFn = code -> {
                assertTrue(flushed.get(), "halt must fire strictly after the flush completes");
                haltedWith.set(code);
            };

            Main.shutdownNode(node, timeSpan(200).millis(), () -> flushed.set(true), haltFn);

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

                // #838 review round 1, JBCT-EX-01: captureThreadDump() returns Result<List<String>>, not a
                // throwing List<String> -- unwrap via Result#or rather than relying on an unchecked throw.
                List<String> dump = Main.captureThreadDump().or(List.of());

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
