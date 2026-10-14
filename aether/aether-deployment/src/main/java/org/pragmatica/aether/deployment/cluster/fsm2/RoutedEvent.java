// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.consensus.NodeId;


/// An event the cluster FSM asks the interpreter to route to the rest of the node (spec 1.3, "Routed events").
public sealed interface RoutedEvent {
    /// A goal for `artifact` on `node` was committed (one per accepted LOAD).
    record DeploymentStarted(Artifact artifact, NodeId node, long atMs) implements RoutedEvent {}

    /// A slice failed (a retry that exhausted, or a fatal failure), with the node's real reason.
    record DeploymentFailed(Artifact artifact, NodeId node, String reason, long atMs) implements RoutedEvent {}

    /// Reconciliation changed the instance count of `artifact`.
    record ReconciliationAdjustment(Artifact artifact, int current, int desired) implements RoutedEvent {}

    /// A schema record holds the activation of `slices`. The record carries what the indexed `SchemaVersionValue` knows (H-4 (a)): the owning
    /// blueprint as a typed id, the artifact coordinates and the attempt count, so that the interpreter can translate the event without
    /// loss. `owner()` stays as the string form of `owningBlueprint` `[guess: "no field is dropped" read as "no information is dropped"; the
    /// string is derived from the typed id instead of being a second component that could disagree with it]`.
    record ActivationBlocked(String datasource,
                             BlueprintId owningBlueprint,
                             List<String> slices,
                             String artifactCoords,
                             int attemptCount) implements RoutedEvent {
        public String owner() {
            return owningBlueprint.asString();
        }
    }
}
