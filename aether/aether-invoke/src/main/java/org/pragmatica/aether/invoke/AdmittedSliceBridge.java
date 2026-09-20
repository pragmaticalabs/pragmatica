// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;


/// One admission boundary for every registered bridge, including same-node invocations.
record AdmittedSliceBridge(SliceBridge delegate, Supplier<InvocationAdmission> admission) implements SliceBridge {
    enum Error implements Cause {
        DRAINING;
        @Override
        public String message() {
            return "Node is draining and cannot accept new execution";
        }
    }

    @Override
    public Promise<byte[]> invoke(String methodName, byte[] input) {
        var gate = admission.get();

        return gate.tryEnter()
               ? delegate.invoke(methodName, input)
                         .onResultRun(gate::exit)
               : Error.DRAINING.promise();
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
