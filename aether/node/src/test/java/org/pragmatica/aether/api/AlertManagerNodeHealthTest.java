// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;


/// #926 scope item 2 — the node-health alerting path, which did not exist.
///
/// Before this, `AlertEvent` carried only `ThresholdAlert`, `SliceFailureAlert` and `AlertResolved`,
/// produced solely by `AlertManager.onAllInstancesFailed` and `checkThreshold`. Repo-wide, "unhealthy"
/// in `aether/node/src/main` appeared exactly twice, both rendering a status string into an HTTP
/// response. A cluster member could therefore be confirmed dead with no alert raised anywhere —
/// measured as three nodes sitting unhealthy for nine days with nothing to notice.
///
/// The alert is deliberately per-node LOCAL state. That is what makes it safe from the gating this
/// ticket is about: raising and reading it need no leader, no quorum, no replica and no partition
/// ownership, unlike the cluster-events stream, whose read path prefers a possibly-dead remote replica.
class AlertManagerNodeHealthTest {

    private static final NodeId FAILED = new NodeId("failed-node");
    private static final NodeId OBSERVER = new NodeId("observer-node");

    @SuppressWarnings("unchecked")
    private static AlertManager newManager() {
        return AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    @Test
    void nodeFailure_raisesCriticalAlert() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);

        var active = manager.getActiveNodeHealthAlerts();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().nodeId()).isEqualTo(FAILED);
        assertThat(active.getFirst().observedBy()).isEqualTo(OBSERVER);
        assertThat(active.getFirst().severity()).isEqualTo(AlertEvent.Severity.CRITICAL);
    }

    /// The alert key is derived from the FAILED node alone, so re-observing the same death replaces
    /// rather than accumulates. This is what lets the raise sit on an ungated edge that fires on every
    /// node's FSM without needing a dedup token — and a token is exactly the wrong fix here, since any
    /// token whose scope matches the counted unit costs at least quorum, and the measured incident ran
    /// below quorum.
    @Test
    void repeatedObservationOfSameDeath_isIdempotent() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);
        manager.onNodeFailed(FAILED, OBSERVER);
        manager.onNodeFailed(FAILED, OBSERVER);

        assertThat(manager.getActiveNodeHealthAlerts()).hasSize(1);
    }

    @Test
    void distinctFailures_raiseDistinctAlerts() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);
        manager.onNodeFailed(new NodeId("other-failed"), OBSERVER);

        assertThat(manager.getActiveNodeHealthAlerts()).hasSize(2);
    }

    /// Recovery must be exactly as reachable as the failure it clears. A raise with no matching clear
    /// leaves a permanently red surface, which trains an operator to ignore it — the same end state as
    /// the silence this ticket is about, reached from the opposite direction.
    @Test
    void rejoin_resolvesTheAlert() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);
        assertThat(manager.getActiveNodeHealthAlerts()).hasSize(1);

        manager.clearNodeHealthAlert(FAILED);
        assertThat(manager.getActiveNodeHealthAlerts()).isEmpty();
    }

    @Test
    void clearingUnknownNode_isNoOp() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);

        manager.clearNodeHealthAlert(new NodeId("never-failed"));
        assertThat(manager.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// An alert that is raised but never rendered is not operator-visible, which would reproduce this
    /// ticket's defect one layer up. Pins that node-health alerts reach the SAME `/api/alerts` view as
    /// every other alert kind, discriminated by `source`.
    @Test
    void alertReachesTheOperatorFacingAlertsView() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);

        var views = manager.activeAlertsAsList().await().unwrap();
        var nodeHealth = views.stream()
                              .filter(view -> "node_health".equals(view.source()))
                              .toList();

        assertThat(nodeHealth).hasSize(1);
        assertThat(nodeHealth.getFirst().nodeId()).isEqualTo(FAILED.id());
        assertThat(nodeHealth.getFirst().severity()).isEqualTo("CRITICAL");
        assertThat(nodeHealth.getFirst().alertId()).isEqualTo("node.failed:" + FAILED.id());
    }

    @Test
    void resolvedAlert_leavesTheOperatorFacingView() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);
        manager.clearNodeHealthAlert(FAILED);

        var views = manager.activeAlertsAsList().await().unwrap();
        assertThat(views.stream().filter(view -> "node_health".equals(view.source())).toList()).isEmpty();
    }

    /// **Regression pin for the round-2 blocking defect.** `SwimDeparted` was briefly treated as a
    /// graceful goodbye, on the strength of `MembershipFsm.onSwimDeparted`'s docstring. It is not: it is
    /// SWIM's DEATH BROADCAST. `SwimProtocol.emitFaultyEdgePair` delivers `FaultyObserved` and
    /// `DepartedObserved` as a pair at the FAULTY edge ("FAULTY IS confirmed death (canonical SWIM)"),
    /// `DepartedObserved` routes to `onSwimDeparted` (`AetherNode:4968`), and that dispatches
    /// `SwimDeparted`. It is the PRIMARY crash path — with it suppressed, `kill -9` raised no CRITICAL
    /// alert at all.
    ///
    /// The test it replaced fed the literal `"SwimDeparted"` and asserted NO alert, so it did not merely
    /// miss the defect — **it encoded the defect as the specification.** Any future change that puts
    /// `SwimDeparted` back into the graceful set turns this red.
    @Test
    void swimDeparted_stillAlerts_becauseItIsSwimsDeathBroadcast() {
        var manager = newManager();
        manager.noteMembershipTransition(FAILED, "SwimDeparted");
        manager.onNodeFailed(FAILED, OBSERVER);

        var active = manager.getActiveNodeHealthAlerts();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().severity()).isEqualTo(AlertEvent.Severity.CRITICAL);
    }

    /// The only cause that is genuinely an announced departure: the operator/controller drain command.
    /// Nothing in SWIM's failure detection raises it.
    @Test
    void operatorDrain_raisesNoAlert() {
        var manager = newManager();
        manager.noteMembershipTransition(FAILED, "DrainRequested");
        manager.onNodeFailed(FAILED, OBSERVER);

        assertThat(manager.getActiveNodeHealthAlerts()).isEmpty();
    }

    /// The discriminating half. A cause that is NOT an announced departure must still alert — otherwise
    /// the graceful path would have been bought by suppressing real failures, which is the defect this
    /// ticket exists to remove. `DownHysteresisMet` is the failure-detection route into DEPARTING.
    @Test
    void abruptDeparture_stillRaisesCriticalAlert() {
        var manager = newManager();
        manager.noteMembershipTransition(FAILED, "DownHysteresisMet");
        manager.onNodeFailed(FAILED, OBSERVER);

        var active = manager.getActiveNodeHealthAlerts();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().severity()).isEqualTo(AlertEvent.Severity.CRITICAL);
    }

    /// The bias is one-directional by design: an UNMARKED departure alerts. A mark that is missed,
    /// dropped, or never delivered therefore costs a spurious CRITICAL and never a silent one.
    @Test
    void unmarkedDeparture_alerts_soAMissedMarkIsNeverSilent() {
        var manager = newManager();
        manager.onNodeFailed(FAILED, OBSERVER);

        assertThat(manager.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// The mark is CONSUMED on read, so a node that departs gracefully, rejoins, and later crashes
    /// still alerts on the crash. Without consumption a single graceful departure would silence that
    /// node's failures for the lifetime of the process.
    @Test
    void gracefulMarkIsConsumed_soALaterCrashStillAlerts() {
        var manager = newManager();
        manager.noteMembershipTransition(FAILED, "DrainRequested");
        manager.onNodeFailed(FAILED, OBSERVER);
        assertThat(manager.getActiveNodeHealthAlerts()).isEmpty();

        manager.onNodeFailed(FAILED, OBSERVER);
        assertThat(manager.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// CTM auto-heal mints a FRESH random id for a replacement rather than reusing the departed one, so
    /// the id-exact clear can never match a replaced node. Without a bound every replacement under
    /// churn would add a permanent entry, growing heap and the `/api/alerts` payload without limit.
    @Test
    void alertMapIsBounded_underReplacementChurn() {
        var manager = newManager();

        for (int i = 0; i < 500; i++) {
            manager.onNodeFailed(new NodeId("replaced-" + i), OBSERVER);
        }

        assertThat(manager.getActiveNodeHealthAlerts()).hasSizeLessThanOrEqualTo(64);
        assertThat(manager.activeAlertsAsList().await().unwrap()).hasSizeLessThanOrEqualTo(64);
    }

    /// Size alone does not pin eviction: flipping oldest-first to newest-first left the suite green,
    /// because the bound test above asserts only how many survive and never WHICH. Newest-first would
    /// discard exactly the failures an operator is still acting on while retaining ancient ones, so the
    /// direction is the part that matters. Insertion order — not `System.currentTimeMillis()` — is what
    /// makes this assertable at all: alerts raised in a tight loop share a millisecond, so a
    /// timestamp-ordered eviction has no well-defined "oldest" to test.
    @Test
    void evictionDropsOldestFirst_keepingTheMostRecentFailures() {
        var manager = newManager();

        for (int i = 0; i < 100; i++) {
            manager.onNodeFailed(new NodeId("node-" + i), OBSERVER);
        }

        var surviving = manager.getActiveNodeHealthAlerts()
                               .stream()
                               .map(alert -> alert.nodeId().id())
                               .toList();

        assertThat(surviving).hasSize(64);
        // The 36 oldest are gone; the 64 most recent remain.
        assertThat(surviving).contains("node-99", "node-36").doesNotContain("node-0", "node-35");
    }
}
