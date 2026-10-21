// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec
public sealed interface HttpForwardMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.FORWARD;
    }

    @Codec
    enum Pipeline {
        APP,
        MANAGEMENT,
        /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
        /// Routed to NEITHER pipeline: an unreadable target must not default onto the app pipeline.
        /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
        /// by an older node either way.
        UNKNOWN
    }

    /// `remainingMillis` is the sender's remaining request budget at send time (stage 2 of deadline
    /// propagation): a receiver drops work whose sender has already given up instead of computing an
    /// answer nobody is waiting for. Negative (`Deadline.NO_BUDGET`) means the sender had no budget
    /// and the receiver applies its own defaults.
    record HttpForwardRequest(NodeId sender,
                              String correlationId,
                              String requestId,
                              byte[] requestData,
                              Pipeline pipeline,
                              long remainingMillis) implements HttpForwardMessage {}

    record HttpForwardResponse(NodeId sender,
                               String correlationId,
                               String requestId,
                               boolean success,
                               byte[] payload,
                               Pipeline pipeline) implements HttpForwardMessage {}

    /// Why a target refused a forwarded request BEFORE dispatching it. Every reason proves the request was not
    /// applied on that node: the refusal happens before any router or slice code runs.
    @Codec
    enum NotAppliedReason {
        /// The target serves no local route for the request (a stale route entry on the sender, a slice mid-undeploy).
        ROUTE_NOT_FOUND,
        /// The sender's wire budget had run out on arrival, so the target refused without dispatching.
        BUDGET_EXHAUSTED,
        /// The target could not decode the forwarded request.
        UNDECODABLE_REQUEST,
        /// Wire sentinel, as [Pipeline#UNKNOWN]: a reason this node cannot name. The message type itself still
        /// asserts "not applied". Must stay LAST.
        UNKNOWN
    }

    /// F1 (#2244): a target's answer that the request was NOT applied there, out of band from [HttpForwardResponse]
    /// so no failure text, which a slice controls, can claim it. It makes re-sending safe for every method, including
    /// POST/PATCH/CONNECT, and an exhausted hunt then answers "nothing was applied; safe to retry".
    ///
    /// Mixed versions: a node built before this type drops it as an unknown tag, so its pending forward waits out
    /// the hop timeout and is treated as "may have been applied". That is the conservative side, and only slower.
    record HttpForwardNotApplied(NodeId sender,
                                 String correlationId,
                                 String requestId,
                                 NotAppliedReason reason,
                                 Pipeline pipeline) implements HttpForwardMessage {}
}
