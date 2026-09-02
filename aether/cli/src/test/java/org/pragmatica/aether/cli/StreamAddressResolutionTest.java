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

    @Test
    void resolveStreamAddress_bareName_defaultsToSystemNamespaceAtDefaultVersion() throws Exception {
        var result = invoke("orders");

        assertTrue(result.isSuccess());
        var addr = result.unwrap();
        assertEquals("system", addr.namespace().value());
        assertEquals("orders", addr.name().value());
        assertEquals("1.0.0", addr.version().asString());
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
