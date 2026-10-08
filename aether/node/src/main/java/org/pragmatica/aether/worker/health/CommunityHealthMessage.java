// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// Core-challenged governor observations. A [Report] carries two kinds of fact about the governor's
/// community: positive per-member health ([MemberHealth]) and, since #1717, terminal deaths
/// ([MemberDeparture]). A departure is relayed ONLY when the governor's own failure detector (SWIM)
/// has reached terminal DEAD for that member; absence, silence and staleness are never a death report.
/// The core accepts a report only from the committed governor of the exact governor term it challenged,
/// so a superseded governor's departures are rejected with the rest of its report.
@Codec
public sealed interface CommunityHealthMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.KV;
    }

    record Request(NodeId sender, String communityId, long governorTerm, String incarnation, long sequence) implements CommunityHealthMessage {}

    record Report(NodeId sender,
                  String communityId,
                  long governorTerm,
                  String incarnation,
                  long sequence,
                  List<MemberHealth> members,
                  List<MemberDeparture> departures) implements CommunityHealthMessage {
        public Report {
            members = List.copyOf(members);
            departures = List.copyOf(departures);
        }

        /// A report with no departures: the pre-#1717 shape.
        public Report(NodeId sender,
                      String communityId,
                      long governorTerm,
                      String incarnation,
                      long sequence,
                      List<MemberHealth> members) {
            this(sender, communityId, governorTerm, incarnation, sequence, members, List.of());
        }
    }

    /// The governor's SWIM reached terminal DEAD for `node`. `bootToken` is the member's boot token as the
    /// governor last observed it (equality only), so the core can refuse a report about another process
    /// that reuses the id. One entry per member; the core applies it once.
    @Codec
    record MemberDeparture(NodeId node, long bootToken) {}

    /// Age is measured by the governor's monotonic clock, not a cross-machine timestamp.
    /// A fresh local direct observation is required; a cached ALIVE membership label is insufficient.
    @Codec
    record MemberHealth(NodeId node,
                        long incarnation,
                        boolean alive,
                        boolean ready,
                        org.pragmatica.lang.io.TimeSpan observationAge) {}
}
