// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.invoke.InvocationTraceStore.ClusterTraceEvent;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Covers the synthetic trace injection surface on `InvocationTraceStore`:
///   - inject + read-back via `all()` / `forRequest()` (correlation by requestId)
///   - validation (blank operation rejected)
///   - default stamping (UUID generation for missing ids, defaults for durationMs/depth)
///
/// Injection is a node-local in-memory operation, so no cluster wiring is required;
/// tests instantiate a fresh store per case via the public factory.
class InvocationTraceStoreInjectTest {

    @Nested
    class InjectionSuccess {

        @Test
        void inject_returnsStampedEntry_whenAllFieldsProvided() {
            var store = InvocationTraceStore.invocationTraceStore();
            store.inject("processOrder",
                         Option.option(123L),
                         Option.option(2),
                         Option.option("req-abc-123"),
                         Option.option("trace-xyz-789"))
                 .onFailure(cause -> fail("Inject failed: " + cause.message()))
                 .onSuccess(node -> {
                     assertEquals("processOrder", node.callee());
                     assertEquals(2, node.depth());
                     assertEquals(123L * 1_000_000L, node.durationNs(), "durationMs=123 must translate to 123ms in ns");
                     assertEquals("req-abc-123", node.requestId(), "client-provided requestId must win over generated UUID");
                     assertEquals(InvocationNode.Outcome.SUCCESS, node.outcome());
                     assertNotNull(node.timestamp(), "timestamp must be stamped");
                 });
        }

        @Test
        void inject_entryVisibleInAll_andForRequest() {
            var store = InvocationTraceStore.invocationTraceStore();
            var requestId = store.inject("processPayment",
                                         Option.option(50L),
                                         Option.option(1),
                                         Option.option("req-readback-001"),
                                         Option.empty())
                                 .map(InvocationNode::requestId)
                                 .or("");
            assertTrue(!requestId.isEmpty(), "Injection must produce a non-empty requestId");

            var all = store.all();
            assertEquals(1, all.size(), "GET /api/traces backing buffer must surface the injected entry");
            assertEquals(requestId, all.get(0).requestId(), "Entry in all() must match injected requestId");

            var forReq = store.forRequest(requestId);
            assertEquals(1, forReq.size(), "forRequest must locate the injected entry by id");
            assertEquals("processPayment", forReq.get(0).callee());
        }
    }

    @Nested
    class ClusterReplication {

        @Test
        void inject_publishesTraceInjectedEvent_whenSinkBound() {
            var store = InvocationTraceStore.invocationTraceStore();
            var capturedOperations = new CopyOnWriteArrayList<String>();
            var capturedMetadata = new CopyOnWriteArrayList<Map<String, String>>();
            store.bindTraceEventSink((operation, requestId, depth, durationMs, metadata) -> {
                capturedOperations.add(operation);
                capturedMetadata.add(metadata);
            });

            store.inject("processOrder",
                         Option.option(150L),
                         Option.option(3),
                         Option.option("req-replicated-001"),
                         Option.empty())
                 .onFailure(cause -> fail("Inject failed: " + cause.message()));

            assertEquals(1, capturedOperations.size(), "inject must emit exactly one cluster event");
            assertEquals("processOrder", capturedOperations.get(0));
            var metadata = capturedMetadata.get(0);
            assertEquals("req-replicated-001", metadata.get("requestId"));
            assertEquals("processOrder", metadata.get("operation"));
            assertEquals("150", metadata.get("durationMs"));
            assertEquals("3", metadata.get("depth"));
            assertNotNull(metadata.get("timestamp"), "metadata must carry timestamp");
        }

        @Test
        void all_includesClusterWideInjectedTraces_andDedupsByRequestId() {
            var store = InvocationTraceStore.invocationTraceStore();
            store.inject("localOp",
                          Option.option(20L),
                          Option.empty(),
                          Option.option("req-local-001"),
                          Option.empty())
                  .onFailure(cause -> fail("Local inject failed: " + cause.message()));

            var peerEvent = new ClusterTraceEvent("req-peer-001", "peerOp", 80L, 2, 1700000000000L, "peer-node");
            var echoEvent = new ClusterTraceEvent("req-local-001", "localOp", 20L, 0, 1700000000001L, "self-node");
            store.bindClusterEventsSource(() -> List.of(peerEvent, echoEvent));

            var all = store.all();
            var requestIds = all.stream().map(InvocationNode::requestId).toList();
            assertTrue(requestIds.contains("req-local-001"),
                       "Originator's local trace must remain visible: " + requestIds);
            assertTrue(requestIds.contains("req-peer-001"),
                       "Peer-originated trace must be unioned in: " + requestIds);
            assertEquals(2, all.size(),
                          "Echo of originator's requestId must dedup: " + requestIds);
        }
    }

    @Nested
    class InjectionValidation {

        @Test
        void inject_fails_whenOperationBlank() {
            InvocationTraceStore.invocationTraceStore()
                                .inject("",
                                        Option.empty(),
                                        Option.empty(),
                                        Option.empty(),
                                        Option.empty())
                                .onSuccess(_ -> fail("Blank operation must be rejected"))
                                .onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("operation"),
                                                               "Failure message must mention operation: " + cause.message()));
        }

        @Test
        void inject_fails_whenOperationWhitespace() {
            InvocationTraceStore.invocationTraceStore()
                                .inject("   ",
                                        Option.empty(),
                                        Option.empty(),
                                        Option.empty(),
                                        Option.empty())
                                .onSuccess(_ -> fail("Whitespace-only operation must be rejected"))
                                .onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("operation"),
                                                               "Failure message must mention operation: " + cause.message()));
        }
    }

    @Nested
    class DefaultStamping {

        @Test
        void inject_generatesUuidRequestId_whenOmitted() {
            var store = InvocationTraceStore.invocationTraceStore();
            store.inject("ping",
                         Option.empty(),
                         Option.empty(),
                         Option.empty(),
                         Option.empty())
                 .onFailure(cause -> fail("Inject failed: " + cause.message()))
                 .onSuccess(node -> {
                     assertNotNull(node.requestId(), "requestId must be stamped when omitted");
                     assertTrue(!node.requestId().isBlank(), "Generated requestId must be non-blank");
                     // UUID canonical form has 36 chars (8-4-4-4-12 + 4 hyphens)
                     assertEquals(36, node.requestId().length(),
                                  "Generated requestId must be a UUID: " + node.requestId());
                 });
        }

        @Test
        void inject_appliesDefaultDuration_andDepth_whenOmitted() {
            var store = InvocationTraceStore.invocationTraceStore();
            store.inject("ping",
                         Option.empty(),
                         Option.empty(),
                         Option.option("req-defaults"),
                         Option.empty())
                 .onFailure(cause -> fail("Inject failed: " + cause.message()))
                 .onSuccess(node -> {
                     assertEquals(10L * 1_000_000L, node.durationNs(),
                                  "Default durationMs=10 must translate to 10_000_000 ns");
                     assertEquals(0, node.depth(), "Default depth must be 0");
                 });
        }

        @Test
        void inject_fallsBackToTraceId_whenRequestIdMissing() {
            var store = InvocationTraceStore.invocationTraceStore();
            store.inject("ping",
                         Option.empty(),
                         Option.empty(),
                         Option.empty(),
                         Option.option("trace-only-fallback"))
                 .onFailure(cause -> fail("Inject failed: " + cause.message()))
                 .onSuccess(node -> assertEquals("trace-only-fallback", node.requestId(),
                                                  "When requestId omitted but traceId given, traceId fills the slot"));
        }
    }
}
