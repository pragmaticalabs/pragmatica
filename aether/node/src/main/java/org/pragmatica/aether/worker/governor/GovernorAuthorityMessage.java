// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec
public sealed interface GovernorAuthorityMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.KV;
    }

    record Request(NodeId sender, String communityId, long requestId, long expectedTerm, String tcpAddress) implements GovernorAuthorityMessage {}

    record Response(NodeId sender, String communityId, long requestId, Option<GovernorAnnouncementValue> authority) implements GovernorAuthorityMessage {}
}
