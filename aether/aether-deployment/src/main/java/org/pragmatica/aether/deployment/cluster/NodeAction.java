// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


public sealed interface NodeAction {
    record StartNode(ProvisionSpec spec) implements NodeAction {}

    record StopNode(NodeId nodeId) implements NodeAction {}

    record RestartNode(NodeId nodeId) implements NodeAction {}

    record MigrateSlices(NodeId sourceNode, Option<NodeId> targetNode) implements NodeAction {}
}
