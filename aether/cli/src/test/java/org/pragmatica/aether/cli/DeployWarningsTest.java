// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1564 finding (a): the deploy warnings — the LOUD replication warnings among them — must reach an operator using
/// the TABLE output of `aether blueprints publish`, whose success line alone would hide them.
class DeployWarningsTest {
    private static final String RESPONSE = """
            {"status":"published","blueprint":"org.example:app:1.0.0",
             "rejectedStreamBindings":[
               {"field":"[streams.audit]","rule":"inert-stream-config-key","message":"compression 'LZ4' has no runtime effect"}],
             "warnings":[
               {"field":"[streams.orders]","rule":"replication-factor-below-three","message":"stream 'orders' (RF 1, CF 1): replication_factor is below 3"},
               {"field":"[entities.carts]","rule":"confirmation-factor-owner-only","message":"entity 'carts' (RF 3, CF 1): confirmation_factor is 1"}]}
            """;

    @Test
    void publishTableFooter_printsTheRejectedBindings_thenEveryWarning() {
        assertThat(DeployWarnings.publishTableFooter(RESPONSE))
            .containsExactly("Rejected stream binding [streams.audit] [inert-stream-config-key]: compression 'LZ4' has no runtime effect",
                             "WARNING [streams.orders] [replication-factor-below-three]: stream 'orders' (RF 1, CF 1): replication_factor is below 3",
                             "WARNING [entities.carts] [confirmation-factor-owner-only]: entity 'carts' (RF 3, CF 1): confirmation_factor is 1");
    }

    /// The wire omits `warnings` when nothing warned; that is the clean case, not a parse problem.
    @Test
    void lines_renderNothing_whenTheKeyIsAbsent() {
        assertThat(DeployWarnings.lines("{\"status\":\"published\",\"blueprint\":\"org.example:app:1.0.0\"}")).isEmpty();
    }

    @Test
    void lines_renderNothing_whenTheBodyDoesNotParse() {
        assertThat(DeployWarnings.lines("not json")).isEmpty();
    }
}
