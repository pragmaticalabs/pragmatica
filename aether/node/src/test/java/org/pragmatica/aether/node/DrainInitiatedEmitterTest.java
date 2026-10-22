// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.node.assembly.DrainAssembly;

import static org.assertj.core.api.Assertions.assertThat;

/// The node's single drain edge runs the scheduled-task hook, then the storage-maintenance stop, then emits
/// `SelfDrainInitiated`; each hook is guarded on its own, so a hook that throws neither skips the next one nor the event.
class DrainInitiatedEmitterTest {
    private final List<String> calls = new ArrayList<>();
    private final AtomicReference<Consumer<DrainReason>> eventEmitter = new AtomicReference<>(reason -> calls.add("event"));

    @Test
    void emitter_runsTheScheduledHook_thenTheStorageStop_thenTheEvent() {
        var scheduled = new AtomicReference<Runnable>(() -> calls.add("scheduled"));

        DrainAssembly.drainInitiatedEmitter(scheduled, () -> calls.add("storage"), eventEmitter).accept(DrainReason.COMMANDED);

        assertThat(calls).containsExactly("scheduled", "storage", "event");
    }

    @Test
    void emitter_aStorageStopThatThrows_stillEmitsTheEvent() {
        var scheduled = new AtomicReference<Runnable>(() -> calls.add("scheduled"));

        DrainAssembly.drainInitiatedEmitter(scheduled, DrainInitiatedEmitterTest::boom, eventEmitter).accept(DrainReason.COMMANDED);

        assertThat(calls).containsExactly("scheduled", "event");
    }

    @Test
    void emitter_aScheduledHookThatThrows_stillStopsStorageAndEmitsTheEvent() {
        var scheduled = new AtomicReference<Runnable>(DrainInitiatedEmitterTest::boom);

        DrainAssembly.drainInitiatedEmitter(scheduled, () -> calls.add("storage"), eventEmitter).accept(DrainReason.COMMANDED);

        assertThat(calls).containsExactly("storage", "event");
    }

    private static void boom() {
        throw new IllegalStateException("simulated hook failure");
    }
}
