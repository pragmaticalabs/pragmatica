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
    /// Wire note: this component sits on all SIX request records — the mutation trio, the get, and both
    /// timer verbs — so its encoded shape is theirs: same-version clusters only, rc-internal, the same
    /// policy as every positional-codec change.
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

    /// The response for FOUR forwarded operations — update, create, delete and timer-cancel. Deliberately
    /// NOT renamed to match: the name is a codec identity, and renaming a `@Codec` type re-derives its tag
    /// for a cosmetic gain. `state` carries the encoded post-mutation state for update and create, and is
    /// EMPTY for delete and timer-cancel (neither has a post-state) and on failure.
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

    /// Schedule a one-shot timer on `key` in `keyspace`, on this node (#345 I4).
    ///
    /// The DELAY travels, never an absolute instant. The owner stamps the fire instant from its OWN wall
    /// clock on arrival, so the clock that mints the instant and the clock that later finds it due are the
    /// same one — an instant stamped by the sender would be compared against a different node's clock and
    /// shift the fire by the skew between them. The price is that the hop's latency is added to the delay,
    /// which is bounded by the forward timeout and far smaller than unbounded skew.
    ///
    /// The TOKEN, by contrast, IS the sender's: it is minted at the caller's `DurableEntity.scheduleTimer`
    /// entry, before this message exists, and the owner applies it rather than one of its own. A token
    /// already pending on the key names a schedule that already landed, so a re-sent forward — the shape a
    /// lost response takes — appends nothing and answers the same token. Carried as a plain `String`, for
    /// the reason [EntityScheduleTimerForwardResponse] gives. Positioned next to `key` so it sits where
    /// [EntityCancelTimerForward] carries its own.
    ///
    /// `key` and `onFire` are defensively copied for the same reason the mutation trio's arrays are: a
    /// record component that aliases a caller's buffer is a mutable field with extra steps, and these
    /// cross a thread boundary on arrival.
    record EntityScheduleTimerForward(NodeId sender,
                                      String correlationId,
                                      String keyspace,
                                      byte[] key,
                                      String token,
                                      long delayMillis,
                                      byte[] onFire,
                                      long remainingMillis) implements EntityForwardMessage {
        public EntityScheduleTimerForward {
            key = key.clone();
            onFire = onFire.clone();
        }

        public static EntityScheduleTimerForward entityScheduleTimerForward(NodeId sender,
                                                                            String correlationId,
                                                                            String keyspace,
                                                                            byte[] key,
                                                                            String token,
                                                                            long delayMillis,
                                                                            byte[] onFire,
                                                                            long remainingMillis) {
            return new EntityScheduleTimerForward(sender,
                                                  correlationId,
                                                  keyspace,
                                                  key,
                                                  token,
                                                  delayMillis,
                                                  onFire,
                                                  remainingMillis);
        }
    }

    /// The schedule response, carrying the owner's ECHO of the token it applied ("" on failure).
    ///
    /// An echo rather than a mint: the token arrives on [EntityScheduleTimerForward] and the owner answers
    /// with the one it actually used, so the sender can VERIFY the identity survived both the wire and the
    /// owner's already-pending check. It fails loudly on a mismatch — a schedule whose token changed under
    /// it is a durable timer the caller cannot cancel, which is the defect caller-side minting removes.
    ///
    /// The token is a plain `String` and never the entity's own `TimerToken`: that type lives in
    /// `resource/durable-entity`, and a wire type here that depended on it would drag the entity module
    /// into the node's codec surface — and pin a domain type's SHAPE to a wire tag, so a later field on
    /// it would be a wire break. The sender wraps the string back into the token inside the entity module,
    /// where the type belongs.
    ///
    /// `failureType` is the owner-side cause's simple class name ("" on success) — carried so the sender
    /// can reconstruct the TYPED refusal instead of a string-flattened one; see
    /// `EntityOwnerForward.ForwardRefused`. A schedule on a key that holds no state refuses with
    /// `EntityNotFound`, and that must still read as `EntityNotFound` after the hop.
    record EntityScheduleTimerForwardResponse(NodeId sender,
                                              String correlationId,
                                              boolean success,
                                              String token,
                                              String failureType,
                                              String errorMessage) implements EntityForwardMessage {
        public static EntityScheduleTimerForwardResponse successResponse(NodeId sender,
                                                                         String correlationId,
                                                                         String token) {
            return new EntityScheduleTimerForwardResponse(sender, correlationId, true, token, "", "");
        }

        public static EntityScheduleTimerForwardResponse failureResponse(NodeId sender,
                                                                         String correlationId,
                                                                         String failureType,
                                                                         String errorMessage) {
            return new EntityScheduleTimerForwardResponse(sender, correlationId, false, "", failureType, errorMessage);
        }
    }

    /// Cancel `token` on `key` in `keyspace`, on this node (#345 I4). The token is a plain `String` for
    /// the same reason [EntityScheduleTimerForwardResponse]'s is.
    ///
    /// There is deliberately NO `EntityCancelTimerForwardResponse`: a cancel's outcome is the success or
    /// failure itself, which is exactly what [EntityUpdateForwardResponse] with an empty `state` already
    /// carries for delete. A second Unit-shaped response record would spend a wire tag and a correlation
    /// map on a distinction nothing reads.
    record EntityCancelTimerForward(NodeId sender,
                                    String correlationId,
                                    String keyspace,
                                    byte[] key,
                                    String token,
                                    long remainingMillis) implements EntityForwardMessage {
        public EntityCancelTimerForward {
            key = key.clone();
        }

        public static EntityCancelTimerForward entityCancelTimerForward(NodeId sender,
                                                                        String correlationId,
                                                                        String keyspace,
                                                                        byte[] key,
                                                                        String token,
                                                                        long remainingMillis) {
            return new EntityCancelTimerForward(sender, correlationId, keyspace, key, token, remainingMillis);
        }
    }
}
