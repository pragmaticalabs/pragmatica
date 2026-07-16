// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;


/// Membership co-confirmation snapshot consumed by [`QuorumLossDetector`] at firing time (Fix C,
/// split-brain self-fence safety). Answers the single question the firing check needs: is a STRICT
/// (state==MEMBER) quorum shortfall a genuine partition, or a stuck-promotion artifact?
///
/// The detector's numerator is the STRICT core count (members in state exactly MEMBER). After a
/// false-FAULTY storm the QUIC-link-gated MEMBER promotion can wedge a still-present, SWIM-alive
/// peer pre-MEMBER (it sits in SUSPECT), dropping the strict count below quorum even though every
/// such peer is reachable. Firing the QUORUM_LOSS drain there is the split-brain self-destruct the
/// 12-network serial cascade exhibited — and the false self-fence the loaded-host SLOW-APPLY
/// incident reproduced (a leader whose consensus apply loop went 10s stale dropped its strict
/// count below quorum while two of its three stuck peers were verifiably SWIM-alive).
///
/// **Per-member sufficiency (refinement (b)).** The original predicate was all-or-nothing: it
/// suppressed only when the COUNTED set met quorum AND EVERY stuck member was SWIM-alive, so a
/// single genuinely-dead member vetoed suppression even when the remaining alive stuck members
/// plainly restored effective quorum. This snapshot instead carries the per-member partition of the
/// stuck set into SWIM-alive and SWIM-dead, and the predicate counts only the alive ones toward
/// effective quorum: `effective = strictCount + swimAliveStuck`. A SWIM-alive stuck member is BY
/// DEFINITION reachable from this node — its probe-acks arrived — hence it is on OUR side of any
/// genuine partition; counting it toward effective quorum can never mask a true split. A
/// genuinely-dead stuck member simply contributes `0` instead of vetoing the whole suppression.
///
/// **Self-contained predicate.** The snapshot carries its OWN `strictCount`, sampled from the same
/// FSM source as the stuck partition at the firing instant, so [`#suppresses`] needs nothing from
/// the detector's live state. This is what preserves the unwired invariant: the [`#absent`]
/// sentinel has `strictCount = 0`, so its effective count is `0` and it can never suppress
/// regardless of the detector's own member count (consensus may signal PASSIVE while the raw count
/// still looks quorate — the absent gate must NOT mask that).
///
/// @param strictCount             the STRICT (state==MEMBER) core count — the numerator that fell
///                                below threshold. Sampled from the FSM at the firing instant.
/// @param countedCount            size of the COUNTED set (MEMBER + SUSPECT, role-scoped core)
///                                including self — reported in the suppression/firing WARN for
///                                operator visibility. Equals strict + every stuck member;
///                                retained verbatim from the supplier rather than recomputed.
/// @param swimAliveStuckMembers   stuck (counted-but-not-strict, i.e. SUSPECT core) members that
///                                are provably SWIM-alive (HEALTHY or SUSPECTED at the raw-SWIM
///                                protocol layer) — reachable wedged promotions. These count
///                                toward effective quorum in [`#suppresses`]. Insertion order
///                                preserved by the supplier.
/// @param swimDeadStuckMembers    stuck members that are NOT provably SWIM-alive (FAULTY /
///                                UNKNOWN — no probe-acks reach this node). These contribute `0`
///                                to effective quorum: a genuinely partitioned member is on the
///                                FAR side of the split and must not be counted. Reported in the
///                                WARN so operators see exactly which members were discounted.
public record QuorumCoConfirmation(int strictCount,
                                   int countedCount,
                                   List<NodeId> swimAliveStuckMembers,
                                   List<NodeId> swimDeadStuckMembers) {
    public QuorumCoConfirmation {
        swimAliveStuckMembers = List.copyOf(swimAliveStuckMembers);
        swimDeadStuckMembers = List.copyOf(swimDeadStuckMembers);
    }

    public static QuorumCoConfirmation quorumCoConfirmation(int strictCount,
                                                            int countedCount,
                                                            List<NodeId> swimAliveStuckMembers,
                                                            List<NodeId> swimDeadStuckMembers) {
        return new QuorumCoConfirmation(strictCount, countedCount, swimAliveStuckMembers, swimDeadStuckMembers);
    }

    /// Sentinel used when no co-confirmation signal is wired: an empty snapshot that NEVER
    /// suppresses (strict 0 + zero alive stuck members can never reach any positive threshold),
    /// preserving the detector's legacy fire-on-strict-shortfall behaviour until the FSM-backed
    /// supplier is wired.
    public static QuorumCoConfirmation absent() {
        return new QuorumCoConfirmation(0, 0, List.of(), List.of());
    }

    /// All stuck members regardless of liveness (alive ++ dead), for operator-facing logging.
    public List<NodeId> stuckMembers() {
        return Stream.concat(swimAliveStuckMembers.stream(),
                             swimDeadStuckMembers.stream())
                     .toList();
    }

    /// Number of stuck members that are individually SWIM-co-confirmed alive — the term added to
    /// the strict count to form the EFFECTIVE quorum count.
    public int swimAliveStuckCount() {
        return swimAliveStuckMembers.size();
    }

    /// EFFECTIVE quorum count actually used by [`#suppresses`]: the STRICT (MEMBER-only) count
    /// plus the number of stuck members individually co-confirmed SWIM-alive. Exposed for
    /// firing/suppression logging so the per-member arithmetic is one-grep diagnosable.
    public int effectiveQuorumCount() {
        return strictCount + swimAliveStuckCount();
    }

    /// The Fix C suppression predicate, refined to per-member sufficiency: SUPPRESS the
    /// QUORUM_LOSS drain iff the EFFECTIVE quorum count meets `threshold`, where effective =
    /// `strictCount + (stuck members individually co-confirmed SWIM-alive)`.
    ///
    /// This is strictly safer than firing AND cannot mask a real partition: every term in the
    /// effective count is a member whose SWIM probe-acks reached THIS node, so it is provably on our
    /// side of any split. A genuine minority partition drops both the strict count and the
    /// SWIM-alive stuck count (unreachable members are FAULTY/UNKNOWN, not alive), so the
    /// effective count stays below threshold and the genuine self-fence proceeds. The previous
    /// all-or-nothing rule (`countedCount >= threshold AND every stuck member alive`) was a
    /// special case: a single SWIM-dead member zeroed suppression even when the alive members
    /// alone restored effective quorum — exactly the false self-fence this refinement eliminates.
    public boolean suppresses(int threshold) {
        return threshold > 0 && effectiveQuorumCount() >= threshold;
    }
}
