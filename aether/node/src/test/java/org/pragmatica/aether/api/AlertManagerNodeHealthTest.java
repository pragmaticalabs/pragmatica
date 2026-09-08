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
}
