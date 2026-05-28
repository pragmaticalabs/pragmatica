/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.rabia;

import org.pragmatica.consensus.rabia.ConsensusEvent.ConsensusActive;
import org.pragmatica.consensus.rabia.ConsensusEvent.ConsensusPassive;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.MessageRouter;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Bridges `ConsensusEvent` emissions from `RabiaEngine` onto the cluster-wide
/// `MessageRouter` as `ClusterStateNotification` instances. This is the SOLE emitter of
/// `ClusterStateNotification` in v2 — the legacy QUIC-count-based path in `TopologyObserver`
/// has been removed (E2 Phase 2c.0, 2026-05-28).
///
/// The bridge owns no state of its own — it is a stateless transform whose `Consumer<ConsensusEvent>`
/// is injected into `RabiaEngine` at construction. The router is invoked synchronously on the
/// emitting thread (typically the Rabia executor) — the router itself is responsible for
/// any required dispatch decoupling.
public final class ConsensusBridge {
    private static final Logger log = LoggerFactory.getLogger(ConsensusBridge.class);

    private final MessageRouter router;

    private ConsensusBridge(MessageRouter router) {
        this.router = router;
    }

    /// Build the bridge sink suitable for injection into `RabiaEngine`'s
    /// `consensusEventListener` parameter.
    public static Consumer<ConsensusEvent> consensusBridge(MessageRouter router) {
        return new ConsensusBridge(router)::onEvent;
    }

    @Contract
    private void onEvent(ConsensusEvent event) {
        switch (event) {
            case ConsensusActive active -> publishActive(active);
            case ConsensusPassive passive -> publishPassive(passive);
        }
    }

    @Contract
    private void publishActive(ConsensusActive event) {
        log.info("ConsensusBridge: ACTIVE for {}", event.self());
        router.route(ClusterStateNotification.active());
    }

    @Contract
    private void publishPassive(ConsensusPassive event) {
        log.info("ConsensusBridge: PASSIVE for {}", event.self());
        router.route(ClusterStateNotification.passive());
    }
}
