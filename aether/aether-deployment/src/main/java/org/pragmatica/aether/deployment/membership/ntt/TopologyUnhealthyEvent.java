// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.consensus.NodeId;


/// Emitted by NTT when a SWIM-converged Departed peer has not reconnected via QUIC within
/// `nttDepartureTimeout`. Local in-process event; only the leader's CTM acts on it
/// (see spec §6.3).
///
/// @param peerId       peer whose NTT timer expired
/// @param firedAtNanos `TimeSource`-derived monotonic nanos at the moment the timer fired
public record TopologyUnhealthyEvent(NodeId peerId, long firedAtNanos) {}
