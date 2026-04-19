// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;


/// Operator-initiated cluster reshape actions consumed by `HealthReconciler`.
///
/// Runtime-only value (no `@Codec`); reaches the reconciler via REST/CLI in-process.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.1 / §8.2.
public sealed interface OperatorIntent {
    record RemoveMember(NodeId nodeId) implements OperatorIntent{}

    record SetDesiredSize(int size) implements OperatorIntent{}

    record DrainMember(NodeId nodeId) implements OperatorIntent{}
}
