// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.slice.ConfigFacade;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// F2 / W-1: the LEGACY `config-update` push runs every slice's `notifyConfigUpdate` on ONE shared
/// `config-notification` thread, one after the other, so a slice that blocks in its callback holds every other
/// slice's notification behind it. `Watched` does not inherit this: each subscription drains on its own virtual
/// thread. Re-seating the legacy path on the feed (and retiring this thread) is a later change; until then this
/// test is an ENABLED tripwire pinning today's behaviour, with the behaviour it should have beside it, disabled.
class ConfigNotificationManagerSharedThreadTest {
    private static final Artifact SLICE_A = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();

    /// Fresh per test: a latch left open by one test would let A through in the next.
    private static volatile CountDownLatch release = new CountDownLatch(1);
    private static final AtomicBoolean A_ENTERED = new AtomicBoolean();
    private static final List<String> B_CALLS = new CopyOnWriteArrayList<>();

    /// Stands in for slice A's generated factory: blocks until released.
    public static final class BlockingFactory {
        public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {
            A_ENTERED.set(true);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// Stands in for slice B's generated factory.
    public static final class RecordingFactory {
        public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {
            B_CALLS.add(section);
        }
    }

    private ConfigNotificationManager notifyBoth() {
        release = new CountDownLatch(1);
        A_ENTERED.set(false);
        B_CALLS.clear();
        var manager = ConfigNotificationManager.configNotificationManager();

        manager.register(SLICE_A, new Object(), getClass().getClassLoader(), BlockingFactory.class.getName(), List.of("database"));
        manager.register(SLICE_B, new Object(), getClass().getClassLoader(), RecordingFactory.class.getName(), List.of("database"));
        manager.notifyChange("database.pool", _ -> NodeDeploymentManager.NO_OP_CONFIG);
        return manager;
    }

    @Test
    void tripwire_todayASliceBlockedInItsCallback_holdsEveryOtherSlicesNotificationBehindIt() throws Exception {
        var manager = notifyBoth();

        await().atMost(Duration.ofSeconds(5)).until(A_ENTERED::get);
        Thread.sleep(300);

        assertThat(B_CALLS).as("W-1 TRIPWIRE: B is still waiting behind blocked A on the shared thread. "
                               + "If this fails the legacy path no longer shares one thread: enable "
                               + "everyOtherSlice_isNotifiedWhileASliceIsBlocked and delete this test").isEmpty();
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> B_CALLS.size() == 1);
        manager.shutdown();
    }

    @Test
    @Disabled("W-1: red today - the legacy push shares one thread (F2). Enable when the legacy path is re-seated on the feed.")
    void everyOtherSlice_isNotifiedWhileASliceIsBlocked() throws Exception {
        var manager = notifyBoth();

        await().atMost(Duration.ofSeconds(5)).until(A_ENTERED::get);

        await().atMost(Duration.ofSeconds(2)).until(() -> B_CALLS.size() == 1);
        release.countDown();
        manager.shutdown();
    }
}
