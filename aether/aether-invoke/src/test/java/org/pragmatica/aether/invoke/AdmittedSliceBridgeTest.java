// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;

class AdmittedSliceBridgeTest {
    @Test
    void invoke_closedGate_rejectsWithoutCallingApplication() {
        var calls = new AtomicInteger();
        var gate = new Gate();
        gate.open.set(false);
        var bridge = new AdmittedSliceBridge(new Bridge(calls, Promise.success(new byte[0])), () -> gate);
        var result = bridge.invoke("call", new byte[0]).await(TimeSpan.timeSpan(1).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(calls.get()).isZero();
        assertThat(gate.count.get()).isZero();
    }

    @Test
    void invoke_pendingExecution_retainsDrainCountUntilCompletion() {
        var completion = Promise.<byte[]>promise();
        var gate = new Gate();
        var bridge = new AdmittedSliceBridge(new Bridge(new AtomicInteger(), completion), () -> gate);
        var result = bridge.invoke("call", new byte[0]);
        assertThat(gate.count.get()).isEqualTo(1);
        gate.open.set(false);
        assertThat(bridge.invoke("call", new byte[0]).await(TimeSpan.timeSpan(1).seconds()).isFailure()).isTrue();
        assertThat(gate.count.get()).isEqualTo(1);
        completion.succeed(new byte[0]);
        assertThat(result.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(gate.drained.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(gate.count.get()).isZero();
    }

    static final class Gate implements InvocationAdmission {
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicInteger count = new AtomicInteger();
        final Promise<Unit> drained = Promise.promise();
        public boolean tryEnter() {
            if (!open.get()) { return false; }
            count.incrementAndGet();
            return true;
        }
        public void exit() { if (count.decrementAndGet() == 0) { drained.succeed(Unit.unit()); } }
    }

    record Bridge(AtomicInteger calls, Promise<byte[]> completion) implements SliceBridge {
        public Promise<byte[]> invoke(String method, byte[] input) { calls.incrementAndGet(); return completion; }
        public Promise<Unit> start() { return Promise.unitPromise(); }
        public Promise<Unit> stop() { return Promise.unitPromise(); }
        public ClassLoader classLoader() { return getClass().getClassLoader(); }
        public List<String> methodNames() { return List.of("call"); }
    }
}
