// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Runs an entity operation on the partition's committed OWNER when this node is not it (#596).
///
/// ## Why this is an interface here rather than a direct call
/// `resource/durable-entity` must not depend on the node's transport, exactly as it must not depend on
/// `aether-stream` for [EntityLogSubstrate]. The node registers the implementation as a
/// provisioning-context extension; the entity states what it needs and stays ignorant of how a message
/// reaches another node.
///
/// ## Why this became possible only now
/// The operation being forwarded used to be an `Fn1<S, S>` — a lambda, which has no name and cannot be
/// encoded. With [org.pragmatica.aether.resource.Mutator] the operation is a RECORD with a generated
/// codec, so the slice JAR already on every node supplies the CODE and only the DATA travels. That is
/// the whole reason the command type landed first.
///
/// ## What the implementation is required to guarantee
/// - The command is applied ON the owner, inside the owner's per-key serialization, so the single-writer
///   total order per key is preserved across the hop. A forwarded write that bypassed the owner's queue
///   would silently trade the guarantee the entity exists to provide for reachability.
/// - The owner's own epoch fence still admits the write. Forwarding must not become a way to land a
///   write that the owner's admission would have refused — a deposed owner's write must still be
///   rejected after the hop.
/// - The returned bytes decode to the POST-mutation state, encoded with the same serializer the owner
///   commits with.
/// - A failure to reach the owner surfaces as a failure. It must not fall back to applying the command
///   locally: that is precisely the split-brain the ownership fence prevents.
public interface EntityOwnerForward {
    /// The key travels ENCODED rather than stringified: `K` is generic, and `String.valueOf(key)` is the
    /// arc-derivation form, not something the owner can turn back into a `K`. `K` is a resource type
    /// argument, so it already carries a generated codec.
    ///
    /// The partition is deliberately NOT transmitted — the owner derives it from the key with the same
    /// deterministic arc. Sending it would create a second source of truth that can disagree with the
    /// first, and the disagreement would route a write to the wrong queue.
    ///
    /// @param owner    the committed owner resolved from the key's `(keyspace, partition)` arc
    /// @param keyspace the entity keyspace, i.e. the `entity:<keyspace>` arc coordinate
    /// @param key      the encoded entity key
    /// @param command  the encoded [org.pragmatica.aether.resource.Mutator] to apply
    ///
    /// @return the encoded post-mutation state
    Promise<byte[]> forwardUpdate(NodeId owner, String keyspace, byte[] key, byte[] command);

    /// Create `key` with `initial` on the owner. Separate from [#forwardUpdate] because a create carries
    /// an initial STATE, not a [org.pragmatica.aether.resource.Mutator] — there is no prior state to
    /// mutate, and the owner must run the same already-exists check a local create runs.
    ///
    /// @return the encoded post-create state
    Promise<byte[]> forwardCreate(NodeId owner, String keyspace, byte[] key, byte[] initial);
    /// Delete `key` on the owner. Carries no payload beyond the key, and answers with no state — the
    /// outcome is the success or failure itself, so the response's state bytes are empty by contract.
    Promise<byte[]> forwardDelete(NodeId owner, String keyspace, byte[] key);
    /// The `BOUNDED_STALE` read half (#596): serve `key` from the owner's fold, through the owner's own
    /// ready/caught-up gates — the staleness bound is the OWNER's, which is at least as fresh as any
    /// replica's. Absence travels as an explicit empty Option (a `present` flag on the wire), never as
    /// an empty-bytes convention: a zero-length-encoding edge silently reading as ABSENT is the defect
    /// this forward exists to remove.
    Promise<Option<byte[]>> forwardGet(NodeId owner, String keyspace, byte[] key);

    /// A refusal that crossed the forward wire. `failureType` is the OWNER-side cause's simple class
    /// name, carried explicitly because the wire otherwise flattens causes to message strings — and a
    /// forwarded duplicate-create that surfaced as a generic failure instead of `EntityAlreadyExists`
    /// reads as an unexplained error to every consumer that matches on the type (02w counts acked
    /// creates exactly that way). The entity reconstructs the typed [EntityError] variants it knows;
    /// anything else keeps this carrier, whose message names the owner's reason verbatim.
    record ForwardRefused(String failureType, String ownerMessage) implements Cause {
        @Override
        public String message() {
            return "entity owner-forward refused by the owner: " + ownerMessage;
        }
    }
}
