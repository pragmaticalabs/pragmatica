// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// FDB-2a (#2591): the node hands the schema orchestrator the event aggregator's operator-warning sink. The orchestrator
/// raises the fence warnings and their recoveries through that sink; unbound, it writes a log line only and the operator's
/// event log never sees them. Pinned on the assembly source (the technique of `ConfigChangeFeedWiringTest`: the node cannot be
/// started in a unit test). The pin reads the comment-stripped text, so a commented-out line does not satisfy it.
class SchemaOrchestratorWarningSinkWiringTest {
    private static final String SINK_FROM_AGGREGATOR = "varoperatorWarningSink=eventAggregatorOut.operatorWarningSink();";
    private static final String ORCHESTRATOR_CREATED = "varschemaOrchestrator=SchemaOrchestratorService.schemaOrchestratorService(";
    private static final String ORCHESTRATOR_BOUND = "schemaOrchestrator.setOperatorWarningSink(operatorWarningSink);";

    @Test
    void theSchemaOrchestratorIsBoundToTheAggregatorsOperatorWarningSink() {
        var source = AssemblySource.stripped();

        assertThat(source).containsOnlyOnce(SINK_FROM_AGGREGATOR)
                          .containsOnlyOnce(ORCHESTRATOR_CREATED)
                          .containsOnlyOnce(ORCHESTRATOR_BOUND);
        assertThat(source.indexOf(ORCHESTRATOR_BOUND)).as("bound after the orchestrator exists")
                                                      .isGreaterThan(source.indexOf(ORCHESTRATOR_CREATED));
        assertThat(source.indexOf(ORCHESTRATOR_BOUND)).as("bound after the sink exists")
                                                      .isGreaterThan(source.indexOf(SINK_FROM_AGGREGATOR));
    }
}
