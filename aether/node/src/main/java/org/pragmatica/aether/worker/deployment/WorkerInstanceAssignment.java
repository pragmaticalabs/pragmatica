// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;

import java.util.Comparator;
import java.util.List;


@SuppressWarnings("JBCT-UTIL-02")
public sealed interface WorkerInstanceAssignment {
    record unused() implements WorkerInstanceAssignment {}

    static int assignedInstances(Artifact artifact, int targetInstances, List<NodeId> aliveMembers, NodeId self) {
        if (aliveMembers.isEmpty() || targetInstances <= 0) {
            return 0;
        }

        var sortedMembers = aliveMembers.stream().sorted(Comparator.comparingInt(member -> hashFor(member, artifact))).toList();
        var selfIndex = sortedMembers.indexOf(self);

        if (selfIndex < 0) {
            return 0;
        }

        var memberCount = sortedMembers.size();
        var baseCount = targetInstances / memberCount;
        var remainder = targetInstances % memberCount;

        return baseCount + (selfIndex < remainder
                            ? 1
                            : 0);
    }

    private static int hashFor(NodeId nodeId, Artifact artifact) {
        return (nodeId.id() + ":" + artifact.asString()).hashCode();
    }
}
