// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;

import org.pragmatica.aether.environment.ClusterName;

/// Schema-2 apply-ledger fixtures shared by the tests that write a ledger by hand.
final class ApplyStateFixtures {
    static final String APPLY_ID = "0123456789abcdefghjkmnpqrs";

    private ApplyStateFixtures() {}

    static ApplyState state(ClusterName clusterName) {
        return ApplyState.applyState(APPLY_ID,
                                     clusterName,
                                     "h",
                                     0L,
                                     "2026-10-18T00:00:00Z",
                                     ApplyPhase.RUNNING,
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     List.of());
    }

    /// A schema-2 ledger whose `createdResources` holds exactly `createdResourceEntry` (raw JSON, may be invalid).
    static String json(String clusterName, String createdResourceEntry) {
        return """
               {"schemaVersion": 2, "applyId": "%s", "clusterName": "%s", "configHash": "h", "storedVersion": 0,
                "startedAt": "2026-10-18T00:00:00Z", "phase": "RUNNING", "actions": [], "resources": [],
                "reservations": [], "createdResources": [%s], "destroyedNodeIds": []}
               """.formatted(APPLY_ID, clusterName, createdResourceEntry);
    }
}
