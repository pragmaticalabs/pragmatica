// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec public sealed interface DHTNotification extends ProtocolMessage {
    @Override default StreamType streamType() {
        return StreamType.DHT;
    }

    @Override default boolean deliverToPassive() {
        return true;
    }

    record Put(NodeId sender, byte[] key, byte[] value) implements DHTNotification {
        public Put {
            key = key.clone();
            value = value.clone();
        }
    }

    record Removed(NodeId sender, byte[] key) implements DHTNotification {
        public Removed {
            key = key.clone();
        }
    }
}
