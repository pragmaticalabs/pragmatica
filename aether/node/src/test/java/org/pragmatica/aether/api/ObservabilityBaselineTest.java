// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.invoke.InvocationNode;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;


/// #277 increment 5a: the fleet observability layer absorbed from the retired ObservabilityInterceptor now
/// lives in the baseline strategy. Proves the write-site relocation is behaviour-preserving through the real
/// registry + cell wiring: an unconfigured cell resolves to the fleet baseline, which on a sampled depth-0
/// success records an InvocationNode into the SAME InvocationTraceStore with the interceptor's exact fields
/// (requestId/depth/nodeId/caller/callee/outcome/local/hops); on failure it records regardless of sampling;
/// and an unsampled success records nothing but still counts. A fresh AdaptiveSampler has effectiveRate 1.0,
/// so `shouldSample()` (nextDouble() < 1.0) is deterministically true for the depth-0 entry-sampling path.
class ObservabilityBaselineTest {
    private static final String ARTIFACT_BASE = "com.example:my-slice";
    private static final String METHOD = "echo";
    private static final String CALLEE = ARTIFACT_BASE + "/" + METHOD;
    private static final String NODE_ID = "node-1";
    private static final int DEFAULT_DEPTH = 1;

    private InvocationTraceStore traceStore;
    private ObservabilityConfigRegistry registry;

    @BeforeEach
    void setUp() {
        traceStore = InvocationTraceStore.invocationTraceStore();
        var sampler = AdaptiveSampler.adaptiveSampler(1_000_000);

        registry = ObservabilityConfigRegistry.observabilityConfigRegistry(null,
                                                                           kvStoreStub(),
                                                                           ObservabilityBaseline.fleet(sampler,
                                                                                                       traceStore,
                                                                                                       NODE_ID,
                                                                                                       DEFAULT_DEPTH));
    }

    @Test
    void baseline_recordsInvocationNode_onSampledDepthZeroSuccess() {
        var cell = registeredCell();

        invoke(cell, 0, false, "req-1", () -> Promise.success("ok"));
        var node = onlyNode();

        assertThat(node.requestId()).isEqualTo("req-1");
        assertThat(node.depth()).isZero();
        assertThat(node.nodeId()).isEqualTo(NODE_ID);
        assertThat(node.caller()).isEqualTo("HTTP");
        assertThat(node.callee()).isEqualTo(CALLEE);
        assertThat(node.outcome()).isEqualTo(InvocationNode.Outcome.SUCCESS);
        assertThat(node.errorMessage()).isEqualTo(Option.<String> none());
        assertThat(node.local()).isTrue();
        assertThat(node.hops()).isZero();
        // Counting rides alongside tracing: the same observed invocation increments the cell's counter.
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void baseline_alreadySampledContext_recordsAtItsDepth_withUnknownCaller() {
        var cell = registeredCell();

        invoke(cell, 3, true, "req-s", () -> Promise.success("ok"));
        var node = onlyNode();

        assertThat(node.depth()).isEqualTo(3);
        assertThat(node.caller()).isEqualTo("unknown");
        assertThat(node.outcome()).isEqualTo(InvocationNode.Outcome.SUCCESS);
    }

    @Test
    void baseline_recordsFailureNode_regardlessOfSampling() {
        var cell = registeredCell();
        // Depth 1, not sampled: the unsampled path still records + logs the failure.
        invoke(cell,
               1,
               false,
               "req-2",
               () -> Causes.cause("boom").promise());
        var node = onlyNode();

        assertThat(node.outcome()).isEqualTo(InvocationNode.Outcome.FAILURE);
        assertThat(node.errorMessage()).isEqualTo(Option.some("boom"));
        assertThat(node.depth()).isEqualTo(1);
        assertThat(node.caller()).isEqualTo("unknown");
        assertThat(node.callee()).isEqualTo(CALLEE);
    }

    @Test
    void baseline_unsampledSuccess_recordsNothing_butCounts() {
        var cell = registeredCell();
        // Depth 1 (no entry sampling) + not already-sampled: success is not traced, only counted.
        invoke(cell, 1, false, "req-3", () -> Promise.success("ok"));
        assertThat(allNodes()).isEmpty();
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));
    }

    private ObservabilityStrategyCell registeredCell() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT_BASE, METHOD);

        registry.register(cell);

        return cell;
    }

    private static void invoke(ObservabilityStrategyCell cell,
                               int depth,
                               boolean sampled,
                               String requestId,
                               Fn0<Promise<String>> body) {
        InvocationContext.runWithContext(requestId,
                                         null,
                                         null,
                                         depth,
                                         sampled,
                                         () -> cell.around(body)
                                                   .await());
    }

    private InvocationNode onlyNode() {
        var nodes = allNodes();

        assertThat(nodes).hasSize(1);

        return nodes.getFirst();
    }

    private List<InvocationNode> allNodes() {
        return traceStore.all()
                         .await()
                         .unwrap();
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> kvStoreStub() {
        return Mockito.mock(KVStore.class);
    }
}
