// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.entityforward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// The wire pair for running an entity command on its committed owner (#596).
///
/// Modelled on `StreamForwardMessage`, with one deliberate difference: no partition travels. The owner
/// derives it from the key using the same deterministic arc, so there is exactly one source of truth
/// for which queue a command belongs to. A transmitted partition could disagree with the receiver's own
/// derivation, and the disagreement would put a write on the wrong per-key queue — losing the ordering
/// the forward exists to preserve.
@Codec
public sealed interface EntityForwardMessage extends ProtocolMessage {
    /// The FORWARD lane, same as the stream forward pair — this is an owner-forward and shares
    /// that traffic's shape and lifetime. It is a logical lane name, not a routing policy.
    @Override
    default StreamType streamType() {
        return StreamType.FORWARD;
    }

    /// Apply `command` to `key` in `keyspace`, on this node.
    ///
    /// Both byte arrays are defensively copied: a record component that aliases a caller's buffer is a
    /// mutable field with extra steps, and these cross a thread boundary on arrival.
    record EntityUpdateForward(NodeId sender, String correlationId, String keyspace, byte[] key, byte[] command) implements EntityForwardMessage {
        public EntityUpdateForward {
            key = key.clone();
            command = command.clone();
        }

        public static EntityUpdateForward entityUpdateForward(NodeId sender,
                                                              String correlationId,
                                                              String keyspace,
                                                              byte[] key,
                                                              byte[] command) {
            return new EntityUpdateForward(sender, correlationId, keyspace, key, command);
        }
    }

    /// `state` carries the encoded POST-mutation state on success, and is empty on failure.
    ///
    /// A failure here must reach the original caller as a failure. The one thing the sender must never
    /// do is apply the command locally instead — that would put a second writer on the key, which is
    /// precisely what the ownership fence prevents.
    record EntityUpdateForwardResponse(NodeId sender,
                                       String correlationId,
                                       boolean success,
                                       byte[] state,
                                       String errorMessage) implements EntityForwardMessage {
        public EntityUpdateForwardResponse {
            state = state.clone();
        }

        public static EntityUpdateForwardResponse successResponse(NodeId sender, String correlationId, byte[] state) {
            return new EntityUpdateForwardResponse(sender, correlationId, true, state, "");
        }

        public static EntityUpdateForwardResponse failureResponse(NodeId sender,
                                                                  String correlationId,
                                                                  String errorMessage) {
            return new EntityUpdateForwardResponse(sender, correlationId, false, new byte[0], errorMessage);
        }
    }
}
