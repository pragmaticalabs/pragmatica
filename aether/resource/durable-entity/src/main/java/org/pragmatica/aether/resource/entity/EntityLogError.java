// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Cause;


/// Failures of the entity's durable log substrate (#345 I3) — distinct from [DurableEntityError], which
/// is scoped to a caller's operation on a key, and from [DurableEntityProvisioningError], which
/// describes a resource that never came into existence. These describe the LOG under a working entity.
public sealed interface EntityLogError extends Cause {
    /// A log record could not be parsed. Reaching this means the bytes at an offset are not what this
    /// build wrote there — a truncated append, a corrupted segment, or a framing change nobody versioned.
    /// It fails the fold rather than skipping the record: a fold that silently drops what it cannot read
    /// produces state that is wrong in a way no later read can detect.
    record MalformedRecord(String detail) implements EntityLogError {
        @Override
        public String message() {
            return "Entity log record is malformed: " + detail;
        }
    }

    /// A record carries a framing version this build does not know. Distinct from [MalformedRecord]
    /// because the operator action differs: this one names a version skew, and the fix is a build that
    /// understands it, not a repair of the data.
    record UnsupportedVersion(byte found, byte supported) implements EntityLogError {
        @Override
        public String message() {
            return "Entity log record framing version " + found
                 + " is not supported by this build (writes version " + supported
                 + ") — a newer node wrote this log; upgrade rather than truncating it";
        }
    }

    /// The log refused an append because this node's owner epoch for the partition is older than the
    /// partition's committed high-water — i.e. the writer is a DEPOSED owner.
    ///
    /// This is the write fence firing, and it exists in this vocabulary so the entity module can
    /// recognise it without depending on the stream module's error types. The entity translates it to
    /// [DurableEntityError.StaleOwner], which is the cause callers have always seen for this case and
    /// which must not change just because the fence moved from the storage engine to the log.
    record StaleOwnerAppend(String keyspace, int partition, String detail) implements EntityLogError {
        @Override
        public String message() {
            return "Entity keyspace '" + keyspace
                 + "' partition " + partition
                 + " refused an append from a deposed owner: " + detail;
        }
    }

    /// This node is not in the partition's replica set, so it holds none of the key's log and never will
    /// without a placement change.
    ///
    /// STABLE, not transient — the caller must go elsewhere rather than retry here. Reporting this as
    /// [FoldInProgress] would be a "retry me" message that never clears, which is a permanent outage
    /// disguised as a slow one.
    record PartitionNotHeld(String keyspace, int partition) implements EntityLogError {
        @Override
        public String message() {
            return "Entity keyspace '" + keyspace
                 + "' partition " + partition
                 + " is not held by this node — ask a node in its replica set; retrying here will not help";
        }
    }

    /// A partition's state is still being rebuilt from its log, so neither reads nor writes can be served
    /// for keys on that partition yet.
    ///
    /// This is TRANSIENT and the caller should retry. It exists as its own cause rather than as a silent
    /// wait because a fold over a long log can take real time, and a caller blocked with no explanation
    /// is indistinguishable from a wedged cluster — the #593 lesson, where a status that never advanced
    /// read exactly like a broken one.
    record FoldInProgress(String keyspace, int partition) implements EntityLogError {
        @Override
        public String message() {
            return "Entity keyspace '" + keyspace
                 + "' partition " + partition
                 + " is still replaying its log — retry; this clears when the fold completes";
        }
    }

    /// The fold for a partition failed, so the partition has NO usable state and is refusing rather than
    /// serving a partial one. Terminal until something re-drives the fold.
    record FoldFailed(String keyspace, int partition, Cause reason) implements EntityLogError {
        @Override
        public String message() {
            return "Entity keyspace '" + keyspace
                 + "' partition " + partition
                 + " could not be rebuilt from its log: " + reason.message();
        }
    }

    /// The write reached the owner's disk but not enough replicas before the barrier gave up.
    ///
    /// The record IS in the log — the append is fsync-durable before this is raised, and `offset` names
    /// where it landed — so this is emphatically not "the write did not happen". It says the write did not
    /// achieve the durability the keyspace declared, which is the honest report: a caller that treats it
    /// as a hard failure and retries is safe, because the fold is idempotent per key.
    ///
    /// `offset` is load-bearing, not diagnostic. The entity applies the record to its local fold on this
    /// cause, because a recovering node WILL replay it; without the offset the fold could not be advanced
    /// and this node would serve a view disagreeing with the log it recovers from.
    record ReplicationBarrierUnmet(String keyspace, int partition, long offset, int required, Cause reason) implements EntityLogError {
        @Override
        public String message() {
            return "Entity keyspace '" + keyspace
                 + "' partition " + partition
                 + " write at offset " + offset
                 + " is durable on the owner but did not reach " + required
                 + " in-sync replica(s): " + reason.message()
                 + " — the record IS present; retrying is safe because the fold is idempotent per key";
        }
    }
}
