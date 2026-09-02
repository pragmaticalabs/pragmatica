// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The durable substrate an entity keyspace runs on (#345 I3): a fenced, fsync-durable, replicated log
/// per `(keyspace, partition)`, plus the checkpoint store that bounds how far back a fold has to go.
///
/// ## Why this is an interface here rather than a direct call
/// `resource/durable-entity` must not depend on `aether-stream`. The node registers the implementation
/// as a provisioning-context extension, exactly as it already does for [EntityKeyspaceRegistrar] and
/// [EntityLinearizableBarrier]. The entity therefore states what it needs from a log and stays ignorant
/// of the fact that a log is a stream partition.
///
/// ## What the implementation is required to guarantee
/// These are not incidental properties of the backing stream — the entity's durability claim rests on
/// each of them, so an implementation that cannot provide one must fail rather than approximate it:
///
///   1. **[#append] is fenced.** A deposed owner's append is REJECTED against the partition's committed
///      ownership high-water. This is what stops two nodes that both believe they own an arc from both
///      writing.
///   2. **[#append] resolves only when durable.** The returned promise must not resolve until the record
///      is fsync-durable on the owner AND held by the keyspace's declared `minSyncReplicas` (counting the
///      owner). A promise that resolves earlier turns [DurableEntityConfig#minSyncReplicas()] into a
///      decoration.
///   3. **[#read] spans tiers.** A fold starts at a checkpoint that may predate everything still in
///      memory, so reads must transparently cross sealed segments as well as the live ring.
///   4. **[#saveCheckpoint] is shared, not node-local.** A checkpoint on the owner's own disk bounds a
///      RESTART and does nothing for a FAILOVER, which is the case that actually matters. It must land
///      where a different node can load it.
///   5. **A checkpoint pins retention, in one direction only.** A checkpoint at offset `H` means every
///      record at or below `H` is already folded into the snapshot and is therefore garbage the tier MAY
///      reclaim; every record ABOVE `H` is still the only copy of a mutation and must survive until a
///      later checkpoint subsumes it. Getting this backwards in either direction is fatal — retaining
///      nothing loses committed state, retaining everything is the unbounded growth the checkpoint
///      exists to stop.
public interface EntityLogSubstrate {
    /// Materialize the backing log for a keyspace. Idempotent — every node hosting the keyspace calls
    /// this during provisioning, and a keyspace already created elsewhere is a success, not a conflict.
    ///
    /// Returns a failure when the log genuinely cannot exist (the cluster's partition budget or ring pool
    /// is exhausted). Provisioning REFUSES on that failure rather than continuing: an entity with no log
    /// is an entity with no durability, which is the same class of silent wrongness as an absent fence.
    ///
    /// @param keyspace          raw keyspace name; the implementation applies the `entity:` arc prefix
    /// @param partitionCount    number of partitions, matching the keyspace's ownership arcs
    /// @param replicationFactor total copies including the owner
    /// @param minSyncReplicas   copies including the owner that must hold a record before an append acks
    Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas);
    /// Append one encoded [EntityLogRecord] to `(keyspace, partition)`, resolving with its offset only
    /// once the durability contract above is met.
    Promise<Long> append(String keyspace, int partition, byte[] record);
    /// Read up to `maxRecords` encoded records from `fromOffset` forward, crossing the live ring and
    /// sealed segments. An empty list means the offset is at or past the head.
    Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords);
    /// The highest offset currently in `(keyspace, partition)`, or `-1` when the partition holds nothing.
    /// The fold reads up to this and no further, so it terminates against a log still being appended to.
    long headOffset(String keyspace, int partition);
    /// The EARLIEST offset this node can still read for `(keyspace, partition)`, or `-1` when it holds
    /// nothing.
    ///
    /// ## Why the fold cannot proceed without this
    /// A node taking over a partition reaches only two sources: the checkpoint, and its own local log
    /// from having been an in-sync replica. It reaches neither the previous owner's WAL nor its sealed
    /// segments — both are node-local, and the replica-receive path does not write a replica's WAL. So if
    /// this value is HIGHER than `checkpoint.throughOffset() + 1`, the mutations in between exist on no
    /// node this one can read, and no amount of replaying will produce correct state.
    ///
    /// The fold compares the two and REFUSES on a gap rather than folding what it happens to have.
    /// Serving that partial fold would be indistinguishable, to every later reader, from complete state —
    /// which is the precise shape of claim-vs-reality failure this epic exists to close.
    long earliestRetainedOffset(String keyspace, int partition);
    /// Whether this node's local copy of `(keyspace, partition)` reaches the cluster's true head — i.e.
    /// whether [#headOffset] can be trusted as the end of the log rather than as the end of what happens
    /// to be here.
    ///
    /// ## Why the fold cannot use headOffset without this
    /// [#headOffset] is a LOCAL reading. A node that has just been handed a partition it was not
    /// replicating sees an empty ring and a head of `-1`, which is indistinguishable from "the log is
    /// empty" — so a fold trusting it would load the checkpoint, replay nothing, and serve state missing
    /// every mutation the previous owner accepted after that checkpoint. Nothing downstream could tell.
    ///
    /// This is the same mistake #593 fixed elsewhere: a local view treated as cluster truth. The fold
    /// therefore waits, reporting [EntityLogError.FoldInProgress], until the node's own copy is complete —
    /// which is exactly what the catch-up machinery already establishes for a replica.
    boolean localLogComplete(String keyspace, int partition);
    /// Whether this node holds `(keyspace, partition)` locally at all.
    ///
    /// Distinct from [#localLogComplete], and the distinction is the difference between a transient state
    /// and a permanent one. A node that holds the partition but is still catching up will BECOME able to
    /// serve, so refusing it as transient is correct. A node that is not in the partition's replica set
    /// never will — it holds no log and no catch-up is running — so reporting THAT as transient produces a
    /// permanent outage wearing a "retry me" message, which is precisely the failure this arc has spent
    /// three increments removing.
    boolean holdsPartition(String keyspace, int partition);
    /// Durably record the fold of `(keyspace, partition)` as of `throughOffset`, on storage another node
    /// can read. Advancing the checkpoint is what lets the tier reclaim the log below it.
    Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot);

    /// The newest checkpoint for `(keyspace, partition)`, or [Option#none] when the partition has never
    /// been checkpointed and must fold from the beginning of its log.
    Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition);

    /// A folded partition as of an offset.
    ///
    /// @param throughOffset the last log offset folded INTO `snapshot`; the fold resumes at
    ///                      `throughOffset + 1`
    /// @param snapshot      the encoded fold, as written by the entity
    record EntityCheckpoint(long throughOffset, byte[] snapshot) {
        public static EntityCheckpoint entityCheckpoint(long throughOffset, byte[] snapshot) {
            return new EntityCheckpoint(throughOffset, snapshot);
        }
    }
}
