// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.controller.RollbackManager.RollbackDecision;
import org.pragmatica.aether.invoke.SliceFailureEvent.AllInstancesFailed;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;

/// #1573: node-local notice of an automatic rollback the leader has COMMITTED, carrying everything an
/// operator needs to see why: the failed artifact, the version rolled back to, and the evidence — each
/// hosting node's slice defects within the detection window.
public sealed interface RollbackEvent extends Message.Local {
    record AutoRollbackExecuted(String requestId,
                                Artifact failedArtifact,
                                Version targetVersion,
                                int rollbackNumber,
                                Map<NodeId, Long> defectsPerHost,
                                long windowMs) implements RollbackEvent {
        public AutoRollbackExecuted {
            defectsPerHost = Map.copyOf(defectsPerHost);
        }

        public static AutoRollbackExecuted autoRollbackExecuted(AllInstancesFailed trigger, RollbackDecision decision) {
            return new AutoRollbackExecuted(trigger.requestId(),
                                            trigger.artifact(),
                                            decision.targetVersion(),
                                            decision.rollbackNumber(),
                                            trigger.defectsPerHost(),
                                            trigger.windowMs());
        }
    }
}
