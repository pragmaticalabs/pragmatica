// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

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
