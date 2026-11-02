// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.messaging.Message;


/// Reconciliation changed an artifact's instance count. It sits beside `DeploymentEvent`, not inside a deployment
/// manager, so the legacy and the rewritten cluster FSM can route the same event to the same subscribers.
public record ReconciliationAdjustment(Artifact artifact, int currentInstances, int desiredInstances) implements Message.Local {
    public static ReconciliationAdjustment reconciliationAdjustment(Artifact artifact,
                                                                    int currentInstances,
                                                                    int desiredInstances) {
        return new ReconciliationAdjustment(artifact, currentInstances, desiredInstances);
    }
}
