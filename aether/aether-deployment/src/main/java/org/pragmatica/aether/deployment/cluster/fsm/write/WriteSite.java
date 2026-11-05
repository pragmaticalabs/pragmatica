// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import java.util.Set;


/// Every leader-issued write of the deployment FSM, one constant per write site (#2584, design §1a/§1b). A site declares
/// its [Route], what it does when [TermWriter] refuses it as not the leader, and the [Mechanism]s by which it converges.
public enum WriteSite {
    /// D01: the stalled-migration reset to PENDING, a guarded mutation of the MIGRATING record read.
    SCHEMA_STALLED_RESET(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D04: the slice-target removals of a deleted blueprint.
    BLUEPRINT_DELETE_TARGET_REMOVE(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D05: a core node's activation directive, written through the hierarchy writer.
    CORE_DIRECTIVE(Route.LEGACY, NotLeaderAction.INACTIVE_REFUSAL, Mechanism.NOT_LEADER),
    /// D06: a worker's community assignment transaction.
    WORKER_ASSIGNMENT(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D07: the snapshot-delta cleanup of a decommissioned node.
    DECOMMISSION_FOOTPRINT(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D08: the slice-target puts of an applied blueprint.
    DEPLOY_TARGET_PUT(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D09: the re-submitted slice target of an awaited apply.
    AWAITED_TARGET_RESUBMIT(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D10: the BEST_EFFORT failure outcome merge.
    BEST_EFFORT_FAILURE_OUTCOME(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D11: the ACTIVATE of a LOADED slice.
    ACTIVATE(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D11b (#2581): the re-issued ACTIVATE, a compare-and-set on the committed LOADED value, through the hierarchy writer.
    ACTIVATE_REISSUE(Route.LEGACY, NotLeaderAction.INACTIVE_REFUSAL, Mechanism.NOT_LEADER),
    /// D12: the LOAD of an allocated slice.
    LOAD(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13a: the UNLOAD of a LOADED slice no committed target permits.
    UNLOAD_NOT_PERMITTED(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13b: the UNLOAD of a FAILED slice.
    UNLOAD_SLICE_FAILED(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13c: the drain-eviction UNLOAD once a replacement is ACTIVE.
    UNLOAD_DRAIN(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13d: the UNLOADs of a removed slice target's instances.
    UNLOAD_DEALLOCATION(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13e: the rebalance donor's UNLOAD.
    UNLOAD_REBALANCE_DONOR(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13f: the UNLOAD of a stuck transitional slice.
    UNLOAD_STUCK_RESET(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13g: the UNLOAD of an orphaned slice entry.
    UNLOAD_ORPHAN(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D13h: the scale-down UNLOADs.
    UNLOAD_SCALE_DOWN(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D14a: the node-artifact removals of a departed node.
    NODE_ARTIFACT_REMOVE_DEPARTED(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D14b: the node-artifact removal of a stuck UNLOADING slice.
    NODE_ARTIFACT_REMOVE_STUCK(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D14c: the node-artifact removal of an orphaned UNLOAD/UNLOADING entry.
    NODE_ARTIFACT_REMOVE_ORPHAN(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D15: the footprint removal of a departed node.
    DEPARTURE_FOOTPRINT(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D16: a departed node's activation-directive removal, through the hierarchy writer.
    DEPARTURE_DIRECTIVE_REMOVE(Route.LEGACY, NotLeaderAction.INACTIVE_REFUSAL, Mechanism.NOT_LEADER),
    /// D17: the community state transitions of a reconcile tick.
    COMMUNITY_TRANSITIONS(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D18: the SUCCEEDED deployment outcome.
    SUCCEEDED_OUTCOME(Route.LEGACY, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D19: the rollback applied without a committed leader record to fence it.
    ROLLBACK_UNFENCED(Route.LEGACY, NotLeaderAction.ABANDON_ROLLBACK, Mechanism.NOT_LEADER),
    /// D20: the rollback transaction fenced on the committed leader record.
    ROLLBACK_FENCED(Route.LEGACY, NotLeaderAction.ABANDON_ROLLBACK, Mechanism.NOT_LEADER),
    /// D21: the stale node-routes cleanup.
    STALE_NODE_ROUTES(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D22: the stale topic subscription/publisher cleanup.
    STALE_TOPIC_ROWS(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D23: the stale slice-entry cleanup.
    STALE_SLICE_ENTRIES(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER),
    /// D24: the stale node-artifact cleanup.
    STALE_NODE_ARTIFACTS(Route.PASS, NotLeaderAction.FAIL_NOT_LEADER, Mechanism.NOT_LEADER);
    private final Route route;
    private final NotLeaderAction notLeader;
    private final Set<Mechanism> mechanisms;
    WriteSite(Route route, NotLeaderAction notLeader, Mechanism... mechanisms) {
        this.route = route;
        this.notLeader = notLeader;
        this.mechanisms = Set.of(mechanisms);
    }
    public Route route() {
        return route;
    }
    public NotLeaderAction notLeader() {
        return notLeader;
    }
    public Set<Mechanism> mechanisms() {
        return mechanisms;
    }
}
