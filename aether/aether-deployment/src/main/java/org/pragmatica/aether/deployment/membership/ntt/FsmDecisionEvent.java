// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// FSM-side decision event consumed by [`DivergenceLogger#observeFsmDecision`]. Stage 6 wires
/// `MembershipFsm.addDecisionListener(divergenceLogger::observeFsmDecision)` so every accepted
/// or rejected FSM decision is forwarded here for side-by-side comparison against NTT's
/// [`ReconcileIntent`] (which says what NTT WOULD do if it were primary).
///
/// At E1 observation-only. The event captures the FSM-side decision verbatim — no inference
/// about whether NTT would have agreed; E3 chaos-suite analysis correlates the two streams
/// out-of-band via [`#peerId`] and the log timestamps.
///
/// @param observedAtNanos `TimeSource`-derived monotonic nanos at the moment the FSM
///                        committed (or no-op'd) the decision
/// @param type            coarse classification of the decision; see [`FsmDecisionType`]
/// @param peerId          peer the decision targets; `None` for cluster-wide actions (none
///                        emitted today — kept open for Stage 6+)
/// @param fsmStateBefore  lifecycle state name BEFORE the decision (e.g. `"Joining"`,
///                        `"OnDuty"`, `"Untracked"`)
/// @param fsmStateAfter   lifecycle state name AFTER the decision (e.g. `"OnDuty"`,
///                        `"Stopped"`)
/// @param reason          short reason — typically the FSM event class simple name
///                        (`"SwimFaulty"`, `"DrainOutcome"`, `"ForceDecommission"`) or a
///                        synthetic reason for KV-derived transitions
public record FsmDecisionEvent(long observedAtNanos,
                               FsmDecisionType type,
                               Option<NodeId> peerId,
                               String fsmStateBefore,
                               String fsmStateAfter,
                               String reason) {
    public static FsmDecisionEvent fsmDecisionEvent(long observedAtNanos,
                                                    FsmDecisionType type,
                                                    Option<NodeId> peerId,
                                                    String fsmStateBefore,
                                                    String fsmStateAfter,
                                                    String reason) {
        return new FsmDecisionEvent(observedAtNanos,
                                    type,
                                    peerId,
                                    fsmStateBefore,
                                    fsmStateAfter,
                                    reason);
    }
}
