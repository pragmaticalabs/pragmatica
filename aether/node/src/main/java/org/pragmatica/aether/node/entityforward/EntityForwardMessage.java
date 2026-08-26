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
    ///
    /// `remainingMillis` (#634 follow-up — the entity half of stage-2 deadline propagation, mirroring
    /// `HttpForwardRequest`): the sender's remaining budget at send time (`Deadline.toWireMillis`).
    /// The receiver rebinds it and REFUSES an arrived-expired command instead of applying work whose
    /// ack nobody collects — the zombie-dispatch amplification 02w measured behind abandoned hops.
    /// Wire note: adding this component changes the encoded shape of all three request records —
    /// same-version clusters only, rc-internal, the same policy as every positional-codec change.
    record EntityUpdateForward(NodeId sender,
                               String correlationId,
                               String keyspace,
                               byte[] key,
                               byte[] command,
                               long remainingMillis) implements EntityForwardMessage {
        public EntityUpdateForward {
            key = key.clone();
            command = command.clone();
        }

        public static EntityUpdateForward entityUpdateForward(NodeId sender,
                                                              String correlationId,
                                                              String keyspace,
                                                              byte[] key,
                                                              byte[] command,
                                                              long remainingMillis) {
            return new EntityUpdateForward(sender, correlationId, keyspace, key, command, remainingMillis);
        }
    }

    /// Create `key` with `initial` in `keyspace`, on this node.
    ///
    /// A DISTINCT record rather than a flag on [EntityUpdateForward]: adding a component to a shipped
    /// record changes its encoded shape, whereas a new permitted subclass of a sealed `@Codec` interface
    /// gets its own tag and leaves every existing message on the wire untouched.
    record EntityCreateForward(NodeId sender,
                               String correlationId,
                               String keyspace,
                               byte[] key,
                               byte[] initial,
                               long remainingMillis) implements EntityForwardMessage {
        public EntityCreateForward {
            key = key.clone();
            initial = initial.clone();
        }

        public static EntityCreateForward entityCreateForward(NodeId sender,
                                                              String correlationId,
                                                              String keyspace,
                                                              byte[] key,
                                                              byte[] initial,
                                                              long remainingMillis) {
            return new EntityCreateForward(sender, correlationId, keyspace, key, initial, remainingMillis);
        }
    }

    /// Delete `key` in `keyspace`, on this node. No payload beyond the key — a delete carries no state.
    record EntityDeleteForward(NodeId sender, String correlationId, String keyspace, byte[] key, long remainingMillis) implements EntityForwardMessage {
        public EntityDeleteForward {
            key = key.clone();
        }

        public static EntityDeleteForward entityDeleteForward(NodeId sender,
                                                              String correlationId,
                                                              String keyspace,
                                                              byte[] key,
                                                              long remainingMillis) {
            return new EntityDeleteForward(sender, correlationId, keyspace, key, remainingMillis);
        }
    }

    /// The `BOUNDED_STALE` read half (#596): serve `key` from the receiving node's fold, through its
    /// own ready/caught-up gates. No payload beyond the key, and the same wire budget discipline as
    /// the mutation trio — an arrived-expired read is refused, not served to nobody.
    record EntityGetForward(NodeId sender, String correlationId, String keyspace, byte[] key, long remainingMillis) implements EntityForwardMessage {
        public EntityGetForward {
            key = key.clone();
        }

        public static EntityGetForward entityGetForward(NodeId sender,
                                                        String correlationId,
                                                        String keyspace,
                                                        byte[] key,
                                                        long remainingMillis) {
            return new EntityGetForward(sender, correlationId, keyspace, key, remainingMillis);
        }
    }

    /// The read response — separate from [EntityUpdateForwardResponse] because absence must be an
    /// EXPLICIT `present` flag, never a byte convention: `state` empty-on-delete works there because
    /// the sender discards it by contract, but a read's caller DECODES the answer, and any future
    /// zero-length encoding would silently read as ABSENT — the exact defect the read half removes.
    /// `state` is meaningful only when `success && present`.
    record EntityGetForwardResponse(NodeId sender,
                                    String correlationId,
                                    boolean success,
                                    boolean present,
                                    byte[] state,
                                    String failureType,
                                    String errorMessage) implements EntityForwardMessage {
        public EntityGetForwardResponse {
            state = state.clone();
        }

        public static EntityGetForwardResponse presentResponse(NodeId sender, String correlationId, byte[] state) {
            return new EntityGetForwardResponse(sender, correlationId, true, true, state, "", "");
        }

        public static EntityGetForwardResponse absentResponse(NodeId sender, String correlationId) {
            return new EntityGetForwardResponse(sender, correlationId, true, false, new byte[0], "", "");
        }

        public static EntityGetForwardResponse failureResponse(NodeId sender,
                                                               String correlationId,
                                                               String failureType,
                                                               String errorMessage) {
            return new EntityGetForwardResponse(sender,
                                                correlationId,
                                                false,
                                                false,
                                                new byte[0],
                                                failureType,
                                                errorMessage);
        }
    }

    /// The response for ALL THREE forwarded operations — update, create and delete. Deliberately NOT
    /// renamed to match: the name is a codec identity, and renaming a `@Codec` type re-derives its tag
    /// for a cosmetic gain. `state` carries the encoded post-mutation state for update and create, is
    /// EMPTY for delete (which has no post-state), and is empty on failure.
    ///
    /// A failure here must reach the original caller as a failure. The one thing the sender must never
    /// do is apply the command locally instead — that would put a second writer on the key, which is
    /// precisely what the ownership fence prevents.
    /// `failureType` is the owner-side cause's simple class name ("" on success) — carried so the
    /// sender can reconstruct the TYPED refusal instead of a string-flattened one; see
    /// `EntityOwnerForward.ForwardRefused`.
    record EntityUpdateForwardResponse(NodeId sender,
                                       String correlationId,
                                       boolean success,
                                       byte[] state,
                                       String failureType,
                                       String errorMessage) implements EntityForwardMessage {
        public EntityUpdateForwardResponse {
            state = state.clone();
        }

        public static EntityUpdateForwardResponse successResponse(NodeId sender, String correlationId, byte[] state) {
            return new EntityUpdateForwardResponse(sender, correlationId, true, state, "", "");
        }

        public static EntityUpdateForwardResponse failureResponse(NodeId sender,
                                                                  String correlationId,
                                                                  String failureType,
                                                                  String errorMessage) {
            return new EntityUpdateForwardResponse(sender, correlationId, false, new byte[0], failureType, errorMessage);
        }
    }
}
