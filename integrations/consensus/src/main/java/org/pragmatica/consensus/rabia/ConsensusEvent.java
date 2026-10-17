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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;


/// Engine-level consensus events emitted by `RabiaEngine` on phase-transitions that affect
/// "is consensus operational?" semantics. These are the authoritative source for cluster-state
/// notifications (E2 Phase 2c.0, 2026-05-28).
///
/// Emission is single-fire-per-transition (idempotent within a phase): `ConsensusActive`
/// fires exactly once when the engine moves INTO `Idle` / `InPhase`; `ConsensusPassive` fires
/// exactly once when the engine moves OUT OF `Active` to any other state (`Syncing`,
/// `Paused`, `Stopped`, `Observing`). The one exception is demotion: moving from `Active` into
/// `Observing` because a voter reconfiguration removed this node while the cluster kept quorum
/// emits `ConsensusDemoted` instead (#1790) — the node is still passive, but it did not lose quorum
/// and keeps applying committed decisions. Leaving `Observing` for any other non-active state
/// (`Paused`, `Syncing`, `Stopped`) emits `ConsensusPassive`.
public sealed interface ConsensusEvent extends Message.Local {
    NodeId self();

    record ConsensusActive(NodeId self) implements ConsensusEvent {}

    record ConsensusPassive(NodeId self) implements ConsensusEvent {}

    record ConsensusDemoted(NodeId self) implements ConsensusEvent {}
}
