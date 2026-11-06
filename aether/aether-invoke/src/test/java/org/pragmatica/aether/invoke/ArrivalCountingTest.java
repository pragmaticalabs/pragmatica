// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.AdmittedSliceBridgeTest.Bridge;
import org.pragmatica.aether.invoke.AdmittedSliceBridgeTest.Gate;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.Origin;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.worker.metrics.ArrivalCounts;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Rc6 f3a: the INTERNAL arrival counters at the two internal entries. Arrivals are counted before the admission
/// gate, so a refused call is an arrival AND a rejection, never a silent drop.
class ArrivalCountingTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(1).seconds();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:counted:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("call").unwrap();

    private static final MessageContext CONTEXT = MessageContext.messageContext("message-id", "orders", 1, 7L);

    private final InvocationMetricsCollector collector = InvocationMetricsCollector.invocationMetricsCollector();

    private final InvocationHandler handler = InvocationHandler.invocationHandler(new NodeId("self"),
                                                                                  new StubClusterNetwork(),
                                                                                  collector);

    @Test
    void remoteRequest_success_isArrivalAcceptedCompleted() {
        handler.registerSlice(ARTIFACT, new Bridge(new AtomicInteger(), Promise.success(new byte[0])));
        handler.onInvokeRequest(request());
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 1, 0, 1, 0));
    }

    @Test
    void remoteRequest_businessFailure_isArrivalAcceptedFailed() {
        handler.registerSlice(ARTIFACT,
                              new Bridge(new AtomicInteger(),
                                         Causes.cause("no funds").promise()));
        handler.onInvokeRequest(request());
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 1, 0, 0, 1));
    }

    @Test
    void remoteRequest_draining_isArrivalAndRejected_notAccepted() {
        var gate = new Gate();

        gate.open.set(false);
        handler.setInvocationAdmission(gate);
        handler.registerSlice(ARTIFACT, new Bridge(new AtomicInteger(), Promise.success(new byte[0])));
        handler.onInvokeRequest(request());
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 0, 1, 0, 0));
    }

    @Test
    void remoteRequest_unknownSlice_isArrivalAndRejected() {
        handler.onInvokeRequest(request());
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 0, 1, 0, 0));
    }

    @Test
    void remoteRequest_isNotCountedAsExternal() {
        handler.registerSlice(ARTIFACT, new Bridge(new AtomicInteger(), Promise.success(new byte[0])));
        handler.onInvokeRequest(request());
        assertThat(collector.arrivalCounts(ARTIFACT, "call", Origin.EXTERNAL)).isEqualTo(ArrivalCounts.ZERO);
    }

    @Test
    void localCall_success_isCountedInternalOnce() {
        handler.registerSlice(ARTIFACT, new Bridge(new AtomicInteger(), Promise.success(new byte[0])));
        handler.localSlice(ARTIFACT).unwrap().invoke("call", new byte[0]).await(WAIT);
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 1, 0, 1, 0));
    }

    @Test
    void localCall_draining_isArrivalAndRejected() {
        var gate = new Gate();

        gate.open.set(false);
        handler.setInvocationAdmission(gate);
        handler.registerSlice(ARTIFACT, new Bridge(new AtomicInteger(), Promise.success(new byte[0])));
        handler.localSlice(ARTIFACT).unwrap().invoke("call", new byte[0]).await(WAIT);
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 0, 1, 0, 0));
    }

    @Test
    void localCall_pending_isArrivalWithNothingSettledYet() {
        handler.registerSlice(ARTIFACT, new Bridge(new AtomicInteger(), Promise.promise()));
        handler.localSlice(ARTIFACT).unwrap().invoke("call", new byte[0]);
        assertThat(counts()).as("in flight: offered, neither accepted nor rejected yet")
                  .isEqualTo(new ArrivalCounts(1, 0, 0, 0, 0));
    }

    @Test
    void localCallWithContext_success_isCountedInternalOnce() {
        handler.registerSlice(ARTIFACT, new ContextBridge(Promise.success(new byte[0])));
        handler.localSlice(ARTIFACT).unwrap().invokeWithContext("call", new byte[0], CONTEXT).await(WAIT);
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 1, 0, 1, 0));
    }

    @Test
    void localCallWithContext_failure_isArrivalAcceptedFailed() {
        handler.registerSlice(ARTIFACT, new ContextBridge(Causes.cause("no funds").promise()));
        handler.localSlice(ARTIFACT).unwrap().invokeWithContext("call", new byte[0], CONTEXT).await(WAIT);
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 1, 0, 0, 1));
    }

    @Test
    void localCallWithContext_draining_isArrivalAndRejected() {
        var gate = new Gate();

        gate.open.set(false);
        handler.setInvocationAdmission(gate);
        handler.registerSlice(ARTIFACT, new ContextBridge(Promise.success(new byte[0])));
        handler.localSlice(ARTIFACT).unwrap().invokeWithContext("call", new byte[0], CONTEXT).await(WAIT);
        assertThat(counts()).isEqualTo(new ArrivalCounts(1, 0, 1, 0, 0));
    }

    private record ContextBridge(Promise<byte[]> completion) implements SliceBridge {
        @Override
        public Promise<byte[]> invoke(String method, byte[] input) {
            return completion;
        }

        @Override
        public Promise<byte[]> invokeWithContext(String method, byte[] input, MessageContext context) {
            return completion;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public List<String> methodNames() {
            return List.of("call");
        }
    }

    private ArrivalCounts counts() {
        return collector.arrivalCounts(ARTIFACT, "call", Origin.INTERNAL);
    }

    private static InvokeRequest request() {
        return InvokeRequest.invokeRequest(new NodeId("remote"), "corr", "req", ARTIFACT, METHOD, new byte[0], false);
    }
}
