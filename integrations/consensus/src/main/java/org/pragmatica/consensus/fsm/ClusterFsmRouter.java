/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.fsm;

import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;

import java.util.concurrent.atomic.AtomicLong;

/// Adapter that subscribes a target `Fsm` to cluster-lifecycle notifications from a
/// `MessageRouter`. Every FSM that reacts to quorum / topology / leader changes calls
/// [`#wire`] once during construction instead of repeating six `router.addRoute(...)` calls.
///
/// Semantic transforms applied here (not inside the FSM):
/// - `TopologyChangeNotification.NodeRemoved` → `ClusterFsmEvent.NodeGone` (unifies with NodeDown)
/// - `TopologyChangeNotification.NodeDown` with empty topology → `ClusterFsmEvent.QuorumDisappeared`
/// - `TopologyChangeNotification.NodeDown` with non-empty topology → `ClusterFsmEvent.NodeGone`
/// - `QuorumStateNotification` stale-sequence dedup via `advanceSequence` — applied here once
///   so individual FSMs do not repeat the check.
public final class ClusterFsmRouter {
    private ClusterFsmRouter() {}

    public static <S extends FsmState<S, ClusterFsmEvent>> void wire(MessageRouter.MutableRouter router,
                                                                      Fsm<S, ClusterFsmEvent> fsm,
                                                                      AtomicLong quorumSequence) {
        router.addRoute(TopologyChangeNotification.NodeAdded.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.NodeAdded(notification.nodeId(),
                                                                                   notification.topology())));
        router.addRoute(TopologyChangeNotification.NodeRemoved.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.NodeGone(notification.nodeId(),
                                                                                  notification.topology())));
        router.addRoute(TopologyChangeNotification.NodeDown.class,
                        notification -> dispatchNodeDown(fsm, notification));
        router.addRoute(QuorumStateNotification.class,
                        notification -> dispatchQuorumState(fsm, notification, quorumSequence));
        router.addRoute(LeaderNotification.LeaderChange.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.LeaderChange(notification.leaderId(),
                                                                                      notification.localNodeIsLeader())));
    }

    private static <S extends FsmState<S, ClusterFsmEvent>> void dispatchNodeDown(Fsm<S, ClusterFsmEvent> fsm,
                                                                                   TopologyChangeNotification.NodeDown notification) {
        if (notification.topology().isEmpty()) {
            fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            return;
        }
        fsm.dispatch(new ClusterFsmEvent.NodeGone(notification.nodeId(), notification.topology()));
    }

    private static <S extends FsmState<S, ClusterFsmEvent>> void dispatchQuorumState(Fsm<S, ClusterFsmEvent> fsm,
                                                                                      QuorumStateNotification notification,
                                                                                      AtomicLong quorumSequence) {
        if (!notification.advanceSequence(quorumSequence)) {
            return;
        }
        switch (notification.state()) {
            case ESTABLISHED -> fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
            case DISAPPEARED -> fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        }
    }
}
