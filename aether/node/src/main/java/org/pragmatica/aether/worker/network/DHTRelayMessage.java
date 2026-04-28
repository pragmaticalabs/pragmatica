// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.network;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;


@Codec public record DHTRelayMessage(NodeId actualTarget, byte[] serializedPayload) implements Message.Wired {
    public DHTRelayMessage {
        serializedPayload = serializedPayload.clone();
    }

    @Override public byte[] serializedPayload() {
        return serializedPayload.clone();
    }

    @Override public boolean equals(Object o) {
        return o instanceof DHTRelayMessage other && actualTarget.equals(other.actualTarget) && Arrays.equals(serializedPayload,
                                                                                                              other.serializedPayload);
    }

    @Override public int hashCode() {
        return 31 * actualTarget.hashCode() + Arrays.hashCode(serializedPayload);
    }

    public static DHTRelayMessage dhtRelayMessage(NodeId actualTarget, byte[] serializedPayload) {
        return new DHTRelayMessage(actualTarget, serializedPayload);
    }
}
