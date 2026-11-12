// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.ExecutionOutcome;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.Settlement;
import org.pragmatica.aether.slice.CallerIdentity;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.lang.Option;
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

    /// #2157: a same-node call made on a caller's behalf passes the same admission gate as [AdmittedSliceBridge#invoke]:
    /// refused while draining, with the application never called, and counted as an arrival that settled REJECTED.
    @Test
    void invokeAsCaller_closedGate_rejectsWithoutCallingApplication_andCountsTheRefusal() {
        var delegate = new CallerBridge(Promise.success(new byte[0]));
        var gate = new Gate();
        var counts = new Counts();
        gate.open.set(false);
        var bridge = new AdmittedSliceBridge(delegate, () -> gate, AdmittedSliceBridge.ExecutionRecorder.NONE, counts);
        var result = bridge.invokeAsCaller("call", new byte[0], Option.some(CALLER)).await(TimeSpan.timeSpan(1).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(delegate.callers).isEmpty();
        assertThat(gate.count.get()).isZero();
        assertThat(counts.events).containsExactly("arrived:call", "settled:call:" + Settlement.REJECTED);
    }

    /// #2157: an admitted call on a caller's behalf hands the caller to the delegate, holds the drain count until the
    /// execution settles, is counted as an arrival that settled COMPLETED, and has its outcome recorded.
    @Test
    void invokeAsCaller_openGate_deliversTheCaller_countsAndRecordsTheExecution() {
        var completion = Promise.<byte[]>promise();
        var delegate = new CallerBridge(completion);
        var gate = new Gate();
        var counts = new Counts();
        var outcomes = new CopyOnWriteArrayList<String>();
        var bridge = new AdmittedSliceBridge(delegate, () -> gate, (method, outcome) -> record(outcomes, method, outcome), counts);
        var result = bridge.invokeAsCaller("call", new byte[0], Option.some(CALLER));
        assertThat(delegate.callers).containsExactly(Option.some(CALLER));
        assertThat(gate.count.get()).as("admitted, pending").isEqualTo(1);
        completion.succeed(new byte[0]);
        assertThat(result.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(gate.drained.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(counts.events).containsExactly("arrived:call", "settled:call:" + Settlement.COMPLETED);
        assertThat(outcomes).containsExactly("call:" + ExecutionOutcome.SUCCESS);
    }

    private static final CallerIdentity CALLER = new CallerIdentity() {};

    private static Unit record(List<String> outcomes, String method, ExecutionOutcome outcome) {
        outcomes.add(method + ":" + outcome);
        return Unit.unit();
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

    @Test
    void contextualInvocationPreservesContextAndRetainsAdmissionThroughCancellation() {
        var execution = Promise.<byte[]>promise();
        var gate = new Gate();
        var calls = new AtomicInteger();
        var context = org.pragmatica.aether.slice.topic.MessageContext.messageContext("message-id", "orders", 1, 7L);
        var input = new byte[]{9};
        var delegate = new SliceBridge() {
            public Promise<byte[]> invoke(String method, byte[] bytes) { return BridgeError.CONTEXT_NOT_SUPPORTED.promise(); }
            public Promise<byte[]> invokeWithContext(String method, byte[] bytes,
                org.pragmatica.aether.slice.topic.MessageContext receivedContext) {
                assertThat(method).isEqualTo("call");
                assertThat(bytes).isSameAs(input);
                assertThat(receivedContext).isSameAs(context);
                calls.incrementAndGet();
                return execution;
            }
            public Promise<Unit> start() { return Promise.unitPromise(); }
            public Promise<Unit> stop() { return Promise.unitPromise(); }
            public ClassLoader classLoader() { return getClass().getClassLoader(); }
            public List<String> methodNames() { return List.of("call"); }
        };
        var bridge = new AdmittedSliceBridge(delegate, () -> gate);
        var result = bridge.invokeWithContext("call", input, context);
        assertThat(gate.count.get()).isEqualTo(1);
        gate.open.set(false);
        bridge.invokeWithContext("call", input, context).await(TimeSpan.timeSpan(1).seconds())
            .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("closed admission must refuse context delivery"))
            .onFailure(cause -> assertThat(cause).isEqualTo(InvocationAdmission.Error.DRAINING));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.cancel().await(TimeSpan.timeSpan(1).seconds()).isFailure()).isTrue();
        assertThat(execution.isResolved()).isFalse();
        assertThat(gate.count.get()).isEqualTo(1);
        execution.succeed(new byte[]{1});
        assertThat(gate.drained.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(gate.count.get()).isZero();
    }

    @Test
    void callerTimeout_doesNotCompleteApplicationOrReleaseDrainCount() {
        assertCallerAbandonmentRetainsExecution(result -> result.timeout(TimeSpan.timeSpan(10).millis()));
    }

    @Test
    void callerCancellation_doesNotCompleteApplicationOrReleaseDrainCount() {
        assertCallerAbandonmentRetainsExecution(Promise::cancel);
    }

    private void assertCallerAbandonmentRetainsExecution(
        java.util.function.Function<Promise<byte[]>, Promise<byte[]>> abandon) {
        var completion = Promise.<byte[]>promise();
        var gate = new Gate();
        var bridge = new AdmittedSliceBridge(new Bridge(new AtomicInteger(), completion), () -> gate);
        var result = bridge.invoke("call", new byte[0]);
        assertThat(result).isNotSameAs(completion);
        assertThat(abandon.apply(result).await(TimeSpan.timeSpan(1).seconds()).isFailure()).isTrue();
        assertThat(completion.isResolved()).isFalse();
        assertThat(gate.count.get()).isEqualTo(1);
        assertThat(gate.drained.isResolved()).isFalse();
        gate.open.set(false);
        completion.succeed(new byte[0]);
        assertThat(gate.drained.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(gate.count.get()).isZero();
        assertThat(completion.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
    }

    @Test
    void replyCompletionRunsBeforeDrainReleaseWithoutReadmission() {
        var execution = Promise.<byte[]>promise();
        var gate = new Gate();
        var replies = new AtomicInteger();
        var bridge = new AdmittedSliceBridge(new Bridge(new AtomicInteger(), execution), () -> gate);
        bridge.invokeWithReply("call", new byte[0], Option.none(), TimeSpan.timeSpan(1).seconds(), result -> {
            assertThat(result.isSuccess()).isTrue();
            assertThat(gate.count.get()).isEqualTo(1);
            assertThat(gate.drained.isResolved()).isFalse();
            replies.incrementAndGet();
        });
        gate.open.set(false);
        execution.succeed(new byte[0]);
        assertThat(gate.drained.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(replies.get()).isEqualTo(1);
        assertThat(gate.count.get()).isZero();
    }

    @Test
    void replyTimeoutEmitsOnceButRetainsAdmissionUntilActualExecutionSettles() {
        var execution = Promise.<byte[]>promise();
        var gate = new Gate();
        var replies = new AtomicInteger();
        var replied = Promise.<Unit>promise();
        var bridge = new AdmittedSliceBridge(new Bridge(new AtomicInteger(), execution), () -> gate);
        bridge.invokeWithReply("call", new byte[0], Option.none(), TimeSpan.timeSpan(10).millis(), result -> {
            assertThat(result.isFailure()).isTrue();
            replies.incrementAndGet();
            replied.succeed(Unit.unit());
        });
        assertThat(replied.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(execution.isResolved()).isFalse();
        assertThat(gate.count.get()).isEqualTo(1);
        assertThat(gate.drained.isResolved()).isFalse();
        gate.open.set(false);
        execution.succeed(new byte[0]);
        assertThat(gate.drained.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
        assertThat(replies.get()).isEqualTo(1);
        assertThat(gate.count.get()).isZero();
    }

    @Test
    void replyAdmissionRefusalEmitsOnceWithoutExecutionOrExit() {
        var calls = new AtomicInteger();
        var replies = new AtomicInteger();
        var gate = new Gate();
        gate.open.set(false);
        var bridge = new AdmittedSliceBridge(new Bridge(calls, Promise.success(new byte[0])), () -> gate);
        bridge.invokeWithReply("call", new byte[0], Option.none(), TimeSpan.timeSpan(1).seconds(), result -> {
            assertThat(result.isFailure()).isTrue();
            replies.incrementAndGet();
        });
        assertThat(replies.get()).isEqualTo(1);
        assertThat(calls.get()).isZero();
        assertThat(gate.count.get()).isZero();
        assertThat(gate.drained.isResolved()).isFalse();
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

    static final class Counts implements AdmittedSliceBridge.CallRecorder {
        final List<String> events = new CopyOnWriteArrayList<>();
        public Unit arrived(String method) { events.add("arrived:" + method); return Unit.unit(); }
        public Unit settled(String method, Settlement settlement) { events.add("settled:" + method + ":" + settlement); return Unit.unit(); }
    }

    /// A delegate that serves only [SliceBridge#invokeAsCaller], recording the caller each call was handed.
    static final class CallerBridge implements SliceBridge {
        final List<Option<CallerIdentity>> callers = new CopyOnWriteArrayList<>();
        final Promise<byte[]> completion;
        CallerBridge(Promise<byte[]> completion) { this.completion = completion; }
        public Promise<byte[]> invoke(String method, byte[] input) { throw new AssertionError("invokeAsCaller must not fall back to invoke"); }
        public Promise<byte[]> invokeAsCaller(String method, byte[] input, Option<CallerIdentity> caller) { callers.add(caller); return completion; }
        public Promise<Unit> start() { return Promise.unitPromise(); }
        public Promise<Unit> stop() { return Promise.unitPromise(); }
        public ClassLoader classLoader() { return getClass().getClassLoader(); }
        public List<String> methodNames() { return List.of("call"); }
    }

    record Bridge(AtomicInteger calls, Promise<byte[]> completion) implements SliceBridge {
        public Promise<byte[]> invoke(String method, byte[] input) { calls.incrementAndGet(); return completion; }
        public Promise<Unit> start() { return Promise.unitPromise(); }
        public Promise<Unit> stop() { return Promise.unitPromise(); }
        public ClassLoader classLoader() { return getClass().getClassLoader(); }
        public List<String> methodNames() { return List.of("call"); }
    }
}
