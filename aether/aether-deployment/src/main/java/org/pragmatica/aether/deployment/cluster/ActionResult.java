// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.consensus.NodeId;


public sealed interface ActionResult {
    record NodeStarted(InstanceInfo instance) implements ActionResult {}

    record NodeStopped(NodeId nodeId) implements ActionResult {}

    record NodeRestarted(NodeId nodeId) implements ActionResult {}

    record SlicesMigrated(NodeId source, int sliceCount) implements ActionResult {}
}
