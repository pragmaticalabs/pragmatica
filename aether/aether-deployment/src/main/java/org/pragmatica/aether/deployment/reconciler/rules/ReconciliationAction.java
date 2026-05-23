// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;


/// Output of a single `ReconciliationRule.evaluate(...)` decision. The reconciler
/// consumes the `command` (subject to per-rule `enforce`) and routes the `justification`
/// onto the audit-stream payload.
///
/// `peer` is redundant with `command.peer()` for the variants that carry a peer (all five
/// today), but kept as a top-level field so rule output is uniformly addressable for the
/// status endpoint's recent-decisions surface.
public record ReconciliationAction(NodeId peer, LifecycleCommand command, Cause justification) {}
