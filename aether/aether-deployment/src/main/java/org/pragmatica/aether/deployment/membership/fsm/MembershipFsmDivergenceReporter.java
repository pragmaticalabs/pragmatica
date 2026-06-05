// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.membership.ntt.ReconcileIntent;
import org.pragmatica.aether.deployment.membership.ntt.ReconcileTrigger;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Phase 1 SHADOW divergence reporter (membership v2) — compares the [`ShadowMembershipFsm`]'s verdict
/// against the live [`org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler`] decision
/// (carried as a [`ReconcileIntent`]) on each reconcile, and logs whether they AGREE or DIVERGE. It
/// **observes only and acts on NOTHING** — it provisions/drains/evicts nothing; the only side effect is
/// the structured greppable log line, and the returned [`Option`] makes the verdict unit-testable.
///
/// **Decoupled by design.** The live per-member view (`liveMembers`) is passed IN per call rather than
/// the reporter reaching into NTT, so the wiring task can supply `ntt.currentMembers()` and the reporter
/// stays pure and unit-testable. The reporter holds only the shadow as a dependency.
///
/// **What is compared each reconcile** (`onReconcileIntent`):
/// - the shadow's `wouldProvision` / `wouldDrain` (computed against `intent.configuredCoreCount()`)
///   against the live `provisionCount` / `drainCount`;
/// - the shadow's `effective()` against the live `clusterMembershipCount`;
/// - the shadow's MEMBER/SUSPECT set (members whose [`ShadowMembershipFsm#memberStates`] value is
///   `"Member"` or `"Suspect"`) against `liveMembers` (symmetric difference = membership-view divergence).
///
/// While the shadow is inactive ([`ShadowMembershipFsm#isActive`] false) there is nothing to compare, so
/// the reporter returns [`Option#none`] and logs nothing. When everything agrees it logs at DEBUG and
/// returns [`Option#none`]; when anything differs it logs a single structured INF0 line and returns
/// [`Option#some`] of the [`MembershipDivergence`] delta.
///
/// **No throwing.** JBCT code returns errors/absence as values; this reporter returns an [`Option`] and
/// never throws, so it can be chained behind the live reconcile path without exception isolation.
public final class MembershipFsmDivergenceReporter {
    private static final Logger log = LoggerFactory.getLogger(MembershipFsmDivergenceReporter.class);

    /// Shadow member-state names (the simple class name of [`MembershipState`] variants) that count as
    /// "present in the cluster view" for the membership-set diff — MEMBER and SUSPECT (SUSPECT still
    /// counts toward effective, the churn cure).
    private static final String MEMBER_STATE = "Member";
    private static final String SUSPECT_STATE = "Suspect";

    private final ShadowMembershipFsm shadow;

    private MembershipFsmDivergenceReporter(ShadowMembershipFsm shadow) {
        this.shadow = shadow;
    }

    /// Factory — keep deps minimal; the live per-member view is passed IN per call so the reporter stays
    /// decoupled and unit-testable.
    public static MembershipFsmDivergenceReporter membershipFsmDivergenceReporter(ShadowMembershipFsm shadow) {
        return new MembershipFsmDivergenceReporter(shadow);
    }

    /// The shadow-vs-live delta for one reconcile. A plain record of values: the `trigger` and
    /// `configuredCoreCount` for context, the shadow verdict (`effective` / `wouldProvision` /
    /// `wouldDrain`), the live decision (`clusterMembershipCount` / `provisionCount` / `drainCount`), and
    /// a short `detail` naming which facet(s) mismatched.
    public record MembershipDivergence(ReconcileTrigger trigger,
                                       int configuredCoreCount,
                                       int shadowEffective,
                                       int shadowWouldProvision,
                                       int shadowWouldDrain,
                                       int liveClusterMembershipCount,
                                       int liveProvisionCount,
                                       int liveDrainCount,
                                       String detail) {}

    /// Compare the shadow's verdict for this reconcile against the live [`ReconcileIntent`] and the live
    /// member set. Returns [`Option#none`] when the shadow is inactive (nothing to compare) or when the
    /// two agree; returns [`Option#some`] of the [`MembershipDivergence`] otherwise. The structured log
    /// line is the Docker-run deliverable; the returned value is the testable verdict.
    public Option<MembershipDivergence> onReconcileIntent(ReconcileIntent intent, Set<NodeId> liveMembers) {
        if (!shadow.isActive()) {
            return none();
        }
        return report(intent, presentMembers(), liveMembers);
    }

    private Option<MembershipDivergence> report(ReconcileIntent intent, Set<NodeId> shadowMembers, Set<NodeId> liveMembers) {
        var shadowProvision = shadow.wouldProvision(intent.configuredCoreCount());
        var shadowDrain = shadow.wouldDrain(intent.configuredCoreCount());
        var shadowEffective = shadow.effective();
        var detail = describeMismatch(intent, shadowEffective, shadowProvision, shadowDrain, shadowMembers, liveMembers);

        return detail.isEmpty()
                ? agree(intent, shadowEffective, shadowProvision, shadowDrain)
                : diverge(intent, shadowEffective, shadowProvision, shadowDrain, detail);
    }

    private Option<MembershipDivergence> agree(ReconcileIntent intent,
                                               int shadowEffective,
                                               int shadowProvision,
                                               int shadowDrain) {
        log.debug("MEMBERSHIP-FSM-AGREE trigger={} effective={} provision={} drain={}",
                  intent.trigger(), shadowEffective, shadowProvision, shadowDrain);
        return none();
    }

    private Option<MembershipDivergence> diverge(ReconcileIntent intent,
                                                 int shadowEffective,
                                                 int shadowProvision,
                                                 int shadowDrain,
                                                 String detail) {
        var divergence = new MembershipDivergence(intent.trigger(),
                                                  intent.configuredCoreCount(),
                                                  shadowEffective,
                                                  shadowProvision,
                                                  shadowDrain,
                                                  intent.clusterMembershipCount(),
                                                  intent.provisionCount(),
                                                  intent.drainCount(),
                                                  detail);
        log.info("MEMBERSHIP-FSM-DIVERGENCE trigger={} cfg={} shadow{eff={},prov={},drain={}} "
                 + "live{members={},prov={},drain={}} detail={}",
                 divergence.trigger(),
                 divergence.configuredCoreCount(),
                 divergence.shadowEffective(),
                 divergence.shadowWouldProvision(),
                 divergence.shadowWouldDrain(),
                 divergence.liveClusterMembershipCount(),
                 divergence.liveProvisionCount(),
                 divergence.liveDrainCount(),
                 divergence.detail());
        return some(divergence);
    }

    /// Build the human-readable mismatch detail by appending one clause per diverging facet; an empty
    /// result means full agreement. No control flow inside lambdas — each facet appends conditionally via
    /// a small named helper.
    private static String describeMismatch(ReconcileIntent intent,
                                           int shadowEffective,
                                           int shadowProvision,
                                           int shadowDrain,
                                           Set<NodeId> shadowMembers,
                                           Set<NodeId> liveMembers) {
        var detail = new StringBuilder();

        appendCountMismatch(detail, "provision", shadowProvision, intent.provisionCount());
        appendCountMismatch(detail, "drain", shadowDrain, intent.drainCount());
        appendCountMismatch(detail, "members", shadowEffective, intent.clusterMembershipCount());
        appendSetMismatch(detail, shadowMembers, liveMembers);
        return detail.toString();
    }

    private static void appendCountMismatch(StringBuilder detail, String label, int shadowValue, int liveValue) {
        if (shadowValue != liveValue) {
            appendClause(detail, label + "(shadow=" + shadowValue + ",live=" + liveValue + ")");
        }
    }

    private static void appendSetMismatch(StringBuilder detail, Set<NodeId> shadowMembers, Set<NodeId> liveMembers) {
        var onlyShadow = difference(shadowMembers, liveMembers);
        var onlyLive = difference(liveMembers, shadowMembers);

        if (!onlyShadow.isEmpty() || !onlyLive.isEmpty()) {
            appendClause(detail, "membership-set(onlyShadow=" + ids(onlyShadow) + ",onlyLive=" + ids(onlyLive) + ")");
        }
    }

    private static void appendClause(StringBuilder detail, String clause) {
        if (!detail.isEmpty()) {
            detail.append("; ");
        }
        detail.append(clause);
    }

    /// Shadow members currently "present in the cluster view" — those whose shadow state name is MEMBER
    /// or SUSPECT (the set the live `liveMembers` is diffed against).
    private Set<NodeId> presentMembers() {
        return shadow.memberStates()
                     .entrySet()
                     .stream()
                     .filter(MembershipFsmDivergenceReporter::isPresentState)
                     .map(Map.Entry::getKey)
                     .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isPresentState(Map.Entry<NodeId, String> entry) {
        return MEMBER_STATE.equals(entry.getValue()) || SUSPECT_STATE.equals(entry.getValue());
    }

    private static Set<NodeId> difference(Set<NodeId> from, Set<NodeId> remove) {
        var result = new LinkedHashSet<>(from);

        result.removeAll(remove);
        return result;
    }

    private static String ids(Set<NodeId> members) {
        return members.stream()
                      .map(NodeId::id)
                      .collect(Collectors.joining(",", "[", "]"));
    }
}
