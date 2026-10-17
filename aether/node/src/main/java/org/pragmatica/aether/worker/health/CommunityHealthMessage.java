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


/// Core-challenged governor observations. This protocol never declares membership death.
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
                  List<MemberHealth> members) implements CommunityHealthMessage {
        public Report {
            members = List.copyOf(members);
        }
    }

    /// Age is measured by the governor's monotonic clock, not a cross-machine timestamp.
    /// A fresh local direct observation is required; a cached ALIVE membership label is insufficient.
    @Codec
    record MemberHealth(NodeId node,
                        long incarnation,
                        boolean alive,
                        boolean ready,
                        org.pragmatica.lang.io.TimeSpan observationAge) {}
}
