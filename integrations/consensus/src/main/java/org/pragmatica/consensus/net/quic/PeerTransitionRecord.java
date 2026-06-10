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

package org.pragmatica.consensus.net.quic;

import org.pragmatica.consensus.NodeId;

/// One [PeerState] phase-transition observation (cluster-topology-overhaul spec, Wave 1
/// Enrichment A). Emitted by [PeerState] at every phase mutation — and by
/// [QuicClusterNetwork] for the dialer expected-vs-actual Hello diagnostic (`cause`
/// prefixed `dialer-hello`, where `from == to` since no phase changed) — through the
/// transition listener installed via [ClusterNetwork#setPeerTransitionListener].
///
/// Pure diagnostic data: consumers (the per-node transition journal in `aether-node`)
/// only record it; emitting has NO control-flow effect. The listener defaults to a
/// no-op, so transports outside the journal wiring are unaffected.
///
/// @param peerId      the peer whose lifecycle phase transitioned
/// @param from        phase before the mutation
/// @param to          phase after the mutation (equal to `from` for connection-replacement
///                    events inside CONNECTED and for `dialer-hello` diagnostics)
/// @param cause       short machine-readable reason (e.g. `begin-connecting`, `evict`,
///                    `authoritative-remove`, `attach-supersede`, `dialer-hello …`)
/// @param wallClockMs wall-clock timestamp (epoch ms) of the transition
public record PeerTransitionRecord(NodeId peerId,
                                   PeerState.Phase from,
                                   PeerState.Phase to,
                                   String cause,
                                   long wallClockMs) {}
