// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;


/// One admission boundary for every registered bridge, including same-node invocations.
record AdmittedSliceBridge(SliceBridge delegate, Supplier<InvocationAdmission> admission) implements SliceBridge {
    @Override
    public Promise<byte[]> invoke(String methodName, byte[] input) {
        return admission.get()
                        .execute(() -> delegate.invoke(methodName, input));
    }

    @Override
    public Promise<byte[]> invokeWithContext(String methodName, byte[] input, MessageContext context) {
        return admission.get()
                        .execute(() -> delegate.invokeWithContext(methodName, input, context));
    }

    /// QUIC response deadlines bound the reply, not the application execution. Both execution
    /// settlement and reply enqueue must finish before drain can release this single admission.
    Unit invokeWithReply(String method, byte[] input, TimeSpan timeout, Consumer<Result<byte[]>> completion) {
        var gate = admission.get();

        if (!gate.tryEnter()) {
            completion.accept(InvocationAdmission.Error.DRAINING.result());

            return Unit.unit();
        }

        var remaining = new AtomicInteger(2);
        var execution = ObservabilityCells.around(delegate, method, () -> delegate.invoke(method, input));

        execution.onResultRun(() -> releaseAfterBoth(remaining, gate));
        execution.map(bytes -> bytes)
                 .timeout(timeout)
                 .withResult(completion)
                 .onResultRun(() -> releaseAfterBoth(remaining, gate));

        return Unit.unit();
    }

    @Contract
    private static void releaseAfterBoth(AtomicInteger remaining, InvocationAdmission gate) {
        if (remaining.decrementAndGet() == 0) {
            gate.exit();
        }
    }

    @Override
    public Promise<Unit> start() {
        return delegate.start();
    }

    @Override
    public Promise<Unit> stop() {
        return delegate.stop();
    }

    @Override
    public Promise<byte[]> encode(Object input) {
        return delegate.encode(input);
    }

    @Override
    public Promise<Object> decode(byte[] bytes) {
        return delegate.decode(bytes);
    }

    @Override
    public ClassLoader classLoader() {
        return delegate.classLoader();
    }

    @Override
    public List<String> methodNames() {
        return delegate.methodNames();
    }

    @Override
    public Option<SliceCodec> sliceCodec() {
        return delegate.sliceCodec();
    }

    @Override
    public Option<ObservabilityStrategyCell> observabilityCell(String method) {
        return delegate.observabilityCell(method);
    }

    @Override
    public List<ObservabilityStrategyCell> observabilityCells() {
        return delegate.observabilityCells();
    }
}
