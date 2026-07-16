// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


/// Per-peer SWIM health observation produced by a follower's detector.
/// Followers push these into an outbound buffer that drains into the next
/// `ClusterSyncPong`. Carries the sender's observed epoch so the leader's
/// `HealthReconciler` can epoch-fence stale observations, plus a wall-clock
/// `producedAtMs` (epoch millis) so consumers can apply a staleness TTL at
/// drain time independent of cluster epoch.
///
/// @param peerId                node the observation is about
/// @param hint                  observed health hint
/// @param observedEpochTerm     observer's epoch term at the time of the observation
/// @param observedEpochCounter  observer's epoch counter at the time of the observation
/// @param producedAtMs          observer's wall-clock millis when the observation was produced
@Codec
public record PeerHealthObservation(NodeId peerId,
                                    HealthHintWire hint,
                                    long observedEpochTerm,
                                    long observedEpochCounter,
                                    long producedAtMs) {}
