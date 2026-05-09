/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.fsm;

import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;

import java.util.concurrent.atomic.AtomicLong;

/// Adapter that subscribes a target `Fsm` to cluster-lifecycle notifications from a
/// `MessageRouter`. Every FSM that reacts to quorum / topology / leader changes calls
/// [`#wire`] once during construction instead of repeating six `router.addRoute(...)` calls.
///
/// Consumes `TransportObservation` (the fast bootstrap path) — local, partial-view, may flap.
/// Cluster-canonical decisions live on `MembershipDecision` and are consumed elsewhere.
///
/// Semantic transforms applied here (not inside the FSM):
/// - `TransportObservation.PeerJoined` → `ClusterFsmEvent.NodeAdded`
/// - `TransportObservation.PeerDisconnected` → `ClusterFsmEvent.NodeGone`
/// - `TransportObservation.PeerObservedFaulty` → `ClusterFsmEvent.NodeGone` (FAULTY treated as departure)
/// - `TransportObservation.PeerReconnected` → no FSM event (transparent)
/// - `TransportObservation.SelfShutdown` → `ClusterFsmEvent.QuorumDisappeared` (topology empty by contract)
/// - `QuorumStateNotification` stale-sequence dedup via `advanceSequence` — applied here once
///   so individual FSMs do not repeat the check.
public final class ClusterFsmRouter {
    private ClusterFsmRouter() {}

    public static <S extends FsmState<S, ClusterFsmEvent>> void wire(MessageRouter.MutableRouter router,
                                                                      Fsm<S, ClusterFsmEvent> fsm,
                                                                      AtomicLong quorumSequence) {
        router.addRoute(TransportObservation.PeerJoined.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.NodeAdded(notification.nodeId(),
                                                                                   notification.topology())));
        router.addRoute(TransportObservation.PeerDisconnected.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.NodeGone(notification.nodeId(),
                                                                                  notification.topology())));
        router.addRoute(TransportObservation.PeerObservedFaulty.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.NodeGone(notification.nodeId(),
                                                                                  notification.topology())));
        router.addRoute(TransportObservation.PeerReconnected.class,
                        ClusterFsmRouter::ignoreReconnect);
        router.addRoute(TransportObservation.SelfShutdown.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared()));
        router.addRoute(QuorumStateNotification.class,
                        notification -> dispatchQuorumState(fsm, notification, quorumSequence));
        router.addRoute(LeaderNotification.LeaderChange.class,
                        notification -> fsm.dispatch(new ClusterFsmEvent.LeaderChange(notification.leaderId(),
                                                                                      notification.localNodeIsLeader())));
    }

    private static void ignoreReconnect(TransportObservation.PeerReconnected reconnected) {
        // Transparent — peer was already known to upstream consumers via prior PeerJoined.
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
