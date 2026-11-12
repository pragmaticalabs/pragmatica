// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Contract;


/// The membership decisions the cluster FSM consumes (phase-3 design 5.1, B9, B10, CI-7, CI-8, CI-9): a core's `NodeJoined` and `NodeRemoved`, and a
/// worker's `WorkerJoin` and `WorkerLeave` (#2762); each join carries the admission membership attaches (`admission(n)`, W-23). After each input is
/// enqueued the seam poller samples, so the fresh sample is appended behind it (review M1).
///
/// The arms the cluster FSM has no variant for (`NodeDecommissioned`, `NodeJoining`, `NodeDraining`, `NodeFailedDrain`, `NodeShuttingDown`;
/// C4, `c9e1d1747`) are deliberately not here, and `DeploymentWiring` binds nothing to them under FSM2 (MT-16, NT-116).
public final class MembershipInputs {
    /// Where the inputs go: the cluster interpreter.
    public interface Target {
        boolean deliver(ClusterInput input);
    }

    private final Target target;
    private final AdmissionSeam admissions;
    private final SeamPoller seams;

    private MembershipInputs(Target target, AdmissionSeam admissions, SeamPoller seams) {
        this.target = target;
        this.admissions = admissions;
        this.seams = seams;
    }

    public static MembershipInputs membershipInputs(Target target, AdmissionSeam admissions, SeamPoller seams) {
        return new MembershipInputs(target, admissions, seams);
    }

    /// C17r (#2937): local membership's DEAD set as the `Rebuild` snapshot carries it, split by the removal input each id takes: a
    /// core-role member `NodeRemoved`, any other `WorkerLeave`, the same role split the delta projector applies to their joins
    /// (`MemberDescriptor.isCoreRole`).
    public static ClusterInput.DeadSet deadSet(Map<NodeId, MemberDescriptor> dead) {
        var byClass = dead.entrySet()
                          .stream()
                          .collect(Collectors.partitioningBy(entry -> entry.getValue()
                                                                           .isCore(),
                                                             Collectors.mapping(Map.Entry::getKey,
                                                                                Collectors.toSet())));

        return ClusterInput.DeadSet.deadSet(byClass.get(true), byClass.get(false));
    }

    /// CI-8: membership admitted a core.
    @Contract
    public void onNodeJoined(MembershipDecision.NodeJoined decision) {
        target.deliver(new ClusterInput.NodeJoined(decision.nodeId(),
                                                   admissions.admission(decision.nodeId())));
        seams.afterMembershipInput();
    }

    /// CI-8: a core left.
    @Contract
    public void onNodeRemoved(MembershipDecision.NodeRemoved decision) {
        target.deliver(new ClusterInput.NodeRemoved(decision.nodeId()));
        seams.afterMembershipInput();
    }

    /// CI-7: membership admitted a worker (B10), or a worker's placement record re-drives its join (B11).
    @Contract
    public void onWorkerJoin(WorkerJoinDecision decision) {
        target.deliver(new ClusterInput.WorkerJoin(decision.nodeId(),
                                                   admissions.admission(decision.nodeId())));
        seams.afterMembershipInput();
    }

    /// CI-9: a worker left (B9).
    @Contract
    public void onWorkerLeave(WorkerLeaveDecision decision) {
        target.deliver(new ClusterInput.WorkerLeave(decision.nodeId()));
        seams.afterMembershipInput();
    }
}
