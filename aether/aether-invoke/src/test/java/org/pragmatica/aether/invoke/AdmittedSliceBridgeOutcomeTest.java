// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.invoke.AdmittedSliceBridgeTest.Bridge;
import org.pragmatica.aether.invoke.AdmittedSliceBridgeTest.Gate;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.ExecutionOutcome;
import org.pragmatica.aether.slice.SliceDefect;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1573: what the admission boundary records for the leader's all-instances-failed detector. A bridge
/// defect counts, a success counts, and nothing else does — a business failure the method returned, a
/// DRAINING refusal, and a reply timeout are all silent.
class AdmittedSliceBridgeOutcomeTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(1).seconds();

    private final List<String> recorded = new CopyOnWriteArrayList<>();

    @Test
    void invoke_success_recordsSuccess() {
        bridge(Promise.success(new byte[0])).invoke("call", new byte[0]).await(WAIT);

        assertThat(recorded).containsExactly("call:SUCCESS");
    }

    @Test
    void invoke_sliceDefect_recordsDefect() {
        bridge(SliceDefect.MethodThrew.FACTORY.apply(Causes.cause("NPE")).promise()).invoke("call", new byte[0]).await(WAIT);

        assertThat(recorded).containsExactly("call:DEFECT");
    }

    @Test
    void invoke_businessFailure_recordsNothing() {
        bridge(Causes.cause("insufficient funds").promise()).invoke("call", new byte[0]).await(WAIT);

        assertThat(recorded).as("a failure the method returned is never a defect").isEmpty();
    }

    @Test
    void invoke_draining_recordsNothing() {
        var gate = new Gate();

        gate.open.set(false);
        new AdmittedSliceBridge(new Bridge(new AtomicInteger(), Promise.success(new byte[0])), () -> gate, this::record)
            .invoke("call", new byte[0])
            .await(WAIT);

        assertThat(recorded).isEmpty();
    }

    @Test
    void invokeWithReply_timeout_recordsNothing() {
        var replied = Promise.<Unit>promise();

        bridge(Promise.promise()).invokeWithReply("call", new byte[0], TimeSpan.timeSpan(10).millis(), _ -> replied.succeed(Unit.unit()));
        replied.await(WAIT);

        assertThat(recorded).as("a stall is overload or an outage as often as a broken version").isEmpty();
    }

    @Test
    void invokeWithReply_defect_recordsDefect() {
        var replied = Promise.<Unit>promise();

        bridge(SliceDefect.CodecFailed.FACTORY.apply(Causes.cause("bad bytes")).promise())
            .invokeWithReply("call", new byte[0], WAIT, _ -> replied.succeed(Unit.unit()));
        replied.await(WAIT);

        assertThat(recorded).containsExactly("call:DEFECT");
    }

    private AdmittedSliceBridge bridge(Promise<byte[]> completion) {
        return new AdmittedSliceBridge(new Bridge(new AtomicInteger(), completion), Gate::new, this::record);
    }

    private Unit record(String method, ExecutionOutcome outcome) {
        recorded.add(method + ":" + outcome);

        return Unit.unit();
    }
}
