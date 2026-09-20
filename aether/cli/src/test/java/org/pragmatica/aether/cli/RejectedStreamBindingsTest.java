// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1336 (rev1363 MEDIUM-4): `aether blueprints publish` prints only "Published blueprint: X" in TABLE
/// format; the declarations the cluster did not bind must reach the operator on that path too.
class RejectedStreamBindingsTest {
    @Test
    void rendersOneLinePerRejectedBinding_withFieldRuleAndMessage() {
        var json = """
                {"status":"published","blueprint":"org.example:app:1.0.0","targetInstances":1,"activeInstances":0,
                 "failedInstances":0,"statusUrl":"/api/v1/blueprints/status/x",
                 "rejectedStreamBindings":[
                   {"field":"[streams.audit-events]","rule":"version-and-source-mutually-exclusive","message":"Stream resource 'audit-events' must not set both 'source' and 'version'"},
                   {"field":"[streams.orders]","rule":"inert-stream-config-key","message":"compression 'LZ4' has no runtime effect"}]}
                """;

        assertThat(RejectedStreamBindings.lines(json))
                .containsExactly("Rejected stream binding [streams.audit-events] [version-and-source-mutually-exclusive]: "
                                 + "Stream resource 'audit-events' must not set both 'source' and 'version'",
                                 "Rejected stream binding [streams.orders] [inert-stream-config-key]: compression 'LZ4' has no runtime effect");
    }

    /// The wire omits the key when nothing was rejected (NIT-1); that is the clean case, not a parse problem.
    @Test
    void rendersNothing_whenTheKeyIsAbsent() {
        assertThat(RejectedStreamBindings.lines("{\"status\":\"published\",\"blueprint\":\"org.example:app:1.0.0\"}")).isEmpty();
    }

    @Test
    void rendersNothing_whenTheBodyDoesNotParse() {
        assertThat(RejectedStreamBindings.lines("not json")).isEmpty();
    }
}
