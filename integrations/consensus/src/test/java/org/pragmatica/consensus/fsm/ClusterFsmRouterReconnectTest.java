/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// A-03 (PR-A2b): `ClusterFsmRouter` maps `PeerReconnected` to `NodeAdded` like `PeerJoined`, so an FSM wired through it
/// gets the evicted peer back in its stored view, as `LeaderManager.peerReconnected` does.
class ClusterFsmRouterReconnectTest {
    private static final NodeId PEER = new NodeId("peer-1");
    private static final List<NodeId> VIEW = List.of(new NodeId("self"), PEER);

    private record Recording(List<ClusterFsmEvent> events) implements FsmState<Recording, ClusterFsmEvent> {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<Recording, ClusterFsmEvent> tx) {
            tx.handle(() -> events.add(event));
        }
    }

    @Test
    void wire_dispatchesNodeAddedWithTheView_onPeerReconnected() {
        var recording = new Recording(new CopyOnWriteArrayList<>());
        var harness = FsmTestHarness.<Recording, ClusterFsmEvent>harness("router-reconnect", recording);
        var router = MessageRouter.mutable();

        ClusterFsmRouter.wire(router, harness.fsm(), new AtomicLong());
        router.route(TransportObservation.peerReconnected(PEER, VIEW, ObservationSource.QUIC));

        assertThat(recording.events()).containsExactly(new ClusterFsmEvent.NodeAdded(PEER, VIEW));
    }
}
