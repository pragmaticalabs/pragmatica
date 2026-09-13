// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins `AetherCli.StreamCommand#resolveStreamAddress` — the client-side bare-name-or-full-address
/// resolution used by `status`/`publish`/`read`/`delete` (management-api-versioning-spec.md hard
/// cutover to the catalog-form routes). The picocli field-binding tests
/// (`StreamsReadCommandTest`, `StreamsLifecycleCommandTest`) pin the raw string reaching this
/// method; this test pins what the method does with it, including the failure path — a resolver
/// that only ever succeeds is not resolving anything.
class StreamAddressResolutionTest {

    /// #1044. This previously asserted a bare name resolved to `system:orders:1.0.0`. That default
    /// became a silent wrong answer once #1040 qualified application streams: the command succeeded
    /// against an empty `system` ring while the stream's events sat under its blueprint namespace,
    /// returning a well-formed empty result indistinguishable from a stream with no events.
    ///
    /// The old assertion was CORRECT about the code and WRONG about the requirement — it pinned the
    /// defect as the specification. It is replaced rather than deleted so the reason survives.
    @Test
    void resolveStreamAddress_bareName_isRefusedAsAmbiguous() throws Exception {
        var result = invoke("orders");

        assertFalse(result.isSuccess());
    }

    /// The refusal must name the form to retype — both the caller's likely namespace and the `system:`
    /// spelling the bare name used to mean. A refusal that does not say what to write instead only
    /// relocates the problem, so this asserts the remedy is present, not merely that it failed.
    @Test
    void resolveStreamAddress_bareName_namesTheFullFormAndTheSystemSpelling() throws Exception {
        var message = invoke("orders").toString();

        assertTrue(message.contains("namespace:stream:version"), message);
        assertTrue(message.contains("system:orders:1.0.0"), message);
    }

    /// A `system` stream stays reachable — spelled in full. Guards against reading #1044 as
    /// "system streams are no longer addressable from the CLI".
    @Test
    void resolveStreamAddress_fullSystemAddress_stillResolves() throws Exception {
        var result = invoke("system:cluster-events:1.0.0");

        assertTrue(result.isSuccess());
        var addr = result.unwrap();
        assertEquals("system", addr.namespace().value());
        assertEquals("cluster-events", addr.name().value());
    }

    @Test
    void resolveStreamAddress_fullAddress_parsesAllThreeComponents() throws Exception {
        var result = invoke("billing:invoices:2.1.0");

        assertTrue(result.isSuccess());
        var addr = result.unwrap();
        assertEquals("billing", addr.namespace().value());
        assertEquals("invoices", addr.name().value());
        assertEquals("2.1.0", addr.version().asString());
    }

    @Test
    void resolveStreamAddress_malformedAddress_failsWithWrongFormat() throws Exception {
        // Two colon-parts instead of three — the same shape a fat-fingered "namespace:stream"
        // (forgetting the version) would produce.
        var result = invoke("billing:invoices");

        assertFalse(result.isSuccess());
        assertTrue(result.toString().toLowerCase().contains("format")
                   || result.toString().toLowerCase().contains("namespace:name:version"),
                  "expected a WRONG_FORMAT-shaped failure, got: " + result);
    }

    @Test
    void resolveStreamAddress_blankAddress_fails() throws Exception {
        var result = invoke("");

        assertFalse(result.isSuccess());
    }

    @SuppressWarnings({"JBCT-EX-01", "unchecked"})
    private static Result<ResourceAddress> invoke(String raw) throws Exception {
        var method = AetherCli.StreamCommand.class.getDeclaredMethod("resolveStreamAddress", String.class);
        method.setAccessible(true);
        return (Result<ResourceAddress>) method.invoke(null, raw);
    }
}
