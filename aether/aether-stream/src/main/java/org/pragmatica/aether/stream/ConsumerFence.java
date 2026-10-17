// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.generation.Epoch;


/// The committed assignment a consumer subscription was admitted under (#1271).
///
/// `epoch` is fixed at attach: every cursor commit and cursor fetch of the subscription carries it, and
/// the consensus applier admits a checkpoint only while that exact assignment is still the committed
/// one. [#admitted] is re-read before EVERY delivery pass — a local read of this node's committed-state
/// mirror, never a consensus round — so a node whose mirror has applied a reassignment stops delivering
/// at its next pass, bounding the overlap with the new assignee to the two nodes' apply skew plus the
/// one batch already in flight. It is a BOUNDED overlap, not a guarantee of a single deliverer.
public interface ConsumerFence {
    Epoch epoch();
    /// Whether this node is still the committed assignee at [#epoch], as its local mirror shows now.
    boolean admitted();
}
