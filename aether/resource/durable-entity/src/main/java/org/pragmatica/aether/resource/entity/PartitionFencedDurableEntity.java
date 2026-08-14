// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.time.Duration;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;


/// Per-`(keyspace, partition)` fenced, restart-durable [DurableEntity] backed by a fenced log (#345 I3).
///
/// ## What changed in I3, and why the fence moved
/// Until I3 this committed each key's state directly into a `StorageEngine`, and the epoch fence lived on
/// that engine's `PartitionOwnerEpochGate`. State was therefore process-local: it died with the node.
///
/// State now lives in an [EntityLogSubstrate] — a fenced, fsync-durable, replicated log per partition —
/// and the in-memory [EntityFold] is a derived view any node can rebuild by replaying it. The write fence
/// moved WITH the state: the log's own append gate fences against the SAME `(keyspace, partition)`
/// ownership high-water the storage gate used, ahead of both the ring append and the WAL fsync. No
/// guarantee was traded away; the enforcement point followed the data.
///
/// ## The two guards, still orthogonal and still both needed
///   - **Owner admission** ([EntityOwnerAdmission]) rejects a LIVE non-owner before any read-modify-write.
///   - **The log's epoch fence** rejects a DEPOSED owner whose committed epoch has been overtaken.
///
/// A node can be neither, either, or both, and each guard catches a case the other misses — an admission
/// check alone waves through the owner that was deposed a moment ago, and a fence alone waves through
/// four live nodes that all read the same current epoch.
///
/// ## Durability, stated per operation rather than as a label
///   - A write resolves only once the record is fsync-durable on the owner AND held by the keyspace's
///     declared `minSyncReplicas`. At `replication_factor = 1` that is the owner alone, which survives a
///     RESTART and not a node loss; at 2 or more a peer holds it before the caller is told anything.
///   - A write that reaches the log but misses the replication barrier reports
///     [EntityLogError.ReplicationBarrierUnmet] and IS applied locally, because the record is in the log
///     and a recovering node will replay it. Reporting failure while diverging from the log would be
///     worse than either honest outcome.
///   - Reads serve the fold, which is refused entirely until the partition has been rebuilt — never
///     served partially.
///
/// @param <K> entity key type — rendered to bytes via `String.valueOf` for the log record
/// @param <S> entity state type — an application-defined immutable value, encoded via [Serializer]
final class PartitionFencedDurableEntity<K, S> implements DurableEntity<K, S> {
    private final String keyspace;
    private final EntityLogSubstrate substrate;
    private final EntityFold fold;
    private final EntityPartitionArc arc;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final PerKeySerialExecutor<K> perKey;
    private final Option<EntityOwnerAdmission> admission;
    private final Option<LinearizableEntityServe<K, S>> linearizableServe;

    private PartitionFencedDurableEntity(String keyspace,
                                         EntityLogSubstrate substrate,
                                         EntityPartitionArc arc,
                                         Serializer serializer,
                                         Deserializer deserializer) {
        this.keyspace = keyspace;
        this.substrate = substrate;
        this.fold = EntityFold.entityFold(keyspace, substrate);
        this.arc = arc;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.perKey = PerKeySerialExecutor.perKeySerialExecutor();
        this.admission = Option.none();
        this.linearizableServe = Option.none();
    }

    private PartitionFencedDurableEntity(String keyspace,
                                         EntityLogSubstrate substrate,
                                         EntityPartitionArc arc,
                                         Serializer serializer,
                                         Deserializer deserializer,
                                         NodeId selfNodeId,
                                         CommittedPartitionOwnerSource committedOwnerSource,
                                         Option<OwnershipEpochHighWater> epochHighWater,
                                         Option<EntityLinearizableBarrier> barrier) {
        this.keyspace = keyspace;
        this.substrate = substrate;
        this.fold = EntityFold.entityFold(keyspace, substrate);
        this.arc = arc;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.perKey = PerKeySerialExecutor.perKeySerialExecutor();
        this.admission = Option.some(EntityOwnerAdmission.entityOwnerAdmission(selfNodeId, arc, committedOwnerSource));
        this.linearizableServe = Option.some(LinearizableEntityServe.linearizableEntityServe(selfNodeId,
                                                                                             arc,
                                                                                             committedOwnerSource,
                                                                                             epochHighWater,
                                                                                             barrier,
                                                                                             this::get));
    }

    /// Unwired form for fence unit tests: no owner admission and no linearizable serve, exercising the
    /// log fence in isolation with no cluster and no ownership records. The node wiring can never take
    /// this arm, because [DurableEntityFactory] REFUSES to provision without those collaborators.
    static <K, S> DurableEntity<K, S> partitionFencedDurableEntity(String keyspace,
                                                                   EntityLogSubstrate substrate,
                                                                   EntityPartitionArc arc,
                                                                   Serializer serializer,
                                                                   Deserializer deserializer) {
        return new PartitionFencedDurableEntity<>(keyspace, substrate, arc, serializer, deserializer);
    }

    /// The wired form the node provisions: owner admission plus a [ReadConsistency#LINEARIZABLE] read
    /// routed through [LinearizableEntityServe], over the SAME arc the write fence uses — so the read
    /// fence and the write fence can never disagree about which ownership arc a key belongs to.
    static <K, S> DurableEntity<K, S> partitionFencedDurableEntity(String keyspace,
                                                                   EntityLogSubstrate substrate,
                                                                   EntityPartitionArc arc,
                                                                   Serializer serializer,
                                                                   Deserializer deserializer,
                                                                   NodeId selfNodeId,
                                                                   CommittedPartitionOwnerSource committedOwnerSource,
                                                                   Option<OwnershipEpochHighWater> epochHighWater,
                                                                   Option<EntityLinearizableBarrier> barrier) {
        return new PartitionFencedDurableEntity<>(keyspace,
                                                  substrate,
                                                  arc,
                                                  serializer,
                                                  deserializer,
                                                  selfNodeId,
                                                  committedOwnerSource,
                                                  epochHighWater,
                                                  barrier);
    }

    /// Expose the fold so the node's checkpoint driver can snapshot it. Package-private: the fold is an
    /// implementation detail of this entity, not part of the [DurableEntity] contract.
    EntityFold fold() {
        return fold;
    }

    @Override
    public Promise<S> create(K key, S initial) {
        return perKey.submit(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return perKey.submit(key, () -> doGet(key));
    }

    @Override
    public Promise<Option<S>> get(K key, ReadConsistency consistency) {
        return switch (consistency) {
            case BOUNDED_STALE -> get(key);
            case LINEARIZABLE -> readLinearizable(key);
        };
    }

    private Promise<Option<S>> readLinearizable(K key) {
        return linearizableServe.fold(() -> get(key), serve -> serve.serve(key));
    }

    @Override
    public Promise<S> update(K key, Fn1<S, S> mutator) {
        return perKey.submit(key, () -> doUpdate(key, mutator));
    }

    @Override
    public Promise<Unit> delete(K key) {
        return perKey.submit(key, () -> doDelete(key));
    }

    @Override
    public Promise<TimerToken> scheduleTimer(K key, Duration delay, Fn1<S, S> onFire) {
        return new EntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    @Override
    public Promise<Unit> cancelTimer(K key, TimerToken token) {
        return new EntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    /// Every operation waits for its partition's fold before touching state. A partition still replaying
    /// refuses with the transient [EntityLogError.FoldInProgress] rather than serving or mutating a
    /// half-built view — the alternative is a create that succeeds because a prior value has not been
    /// replayed yet, which would overwrite committed state.
    private Promise<S> doCreate(K key, S initial) {
        return admitWrite(key).fold(Cause::promise, _ -> ready(key).flatMap(_ -> createAdmitted(key, initial)));
    }

    private Promise<S> createAdmitted(K key, S initial) {
        return readState(key).flatMap(existing -> existing.fold(() -> commit(key, initial), _ -> keyAlreadyExists(key)));
    }

    private Promise<Option<S>> doGet(K key) {
        return ready(key).flatMap(_ -> readState(key));
    }

    private Promise<S> doUpdate(K key, Fn1<S, S> mutator) {
        return admitWrite(key).fold(Cause::promise, _ -> ready(key).flatMap(_ -> updateAdmitted(key, mutator)));
    }

    private Promise<S> updateAdmitted(K key, Fn1<S, S> mutator) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key),
                                                              state -> commit(key, mutator.apply(state))));
    }

    private Promise<Unit> doDelete(K key) {
        return admitWrite(key).fold(Cause::promise, _ -> ready(key).flatMap(_ -> deleteAdmitted(key)));
    }

    private Promise<Unit> deleteAdmitted(K key) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key), _ -> removeState(key)));
    }

    private Promise<Unit> ready(K key) {
        return fold.ready(partitionOf(key));
    }

    /// Owner admission, ahead of the read-modify-write and ahead of the log's epoch fence: only the
    /// committed owner of the key's arc may mutate it. See [EntityOwnerAdmission].
    private Result<Unit> admitWrite(K key) {
        return admission.fold(Result::unitResult, gate -> gate.admit(key));
    }

    /// Read from the fold and decode.
    ///
    /// The decode is LIFTED, not called inline. [Deserializer] signals failure by throwing — by design,
    /// since a codec miss is a configuration fault — and a throw escaping here would propagate out of the
    /// [PerKeySerialExecutor] tail, leaving the operation's promise unresolved: the caller hangs until its
    /// own timeout and the key's serialization tail is wedged for good. A hang is a strictly worse failure
    /// than a typed one.
    private Promise<Option<S>> readState(K key) {
        return fold.get(partitionOf(key),
                        String.valueOf(key))
                   .fold(() -> Promise.success(Option.none()),
                         raw -> Result.lift(throwable -> codecFailed(key, throwable),
                                            () -> Option.some(decode(raw)))
                                      .async());
    }

    /// Encode, append to the fenced log, then apply to the fold.
    ///
    /// The encode is LIFTED for the same reason the decode is: [Serializer] throws on a codec miss, and a
    /// throw escaping the per-key tail hangs the caller instead of failing it.
    private Promise<S> commit(K key, S next) {
        return Result.lift(throwable -> codecFailed(key, throwable),
                           () -> serializer.encode(next))
                     .async()
                     .flatMap(encoded -> appendAndApply(key,
                                                        EntityLogRecord.upsert(String.valueOf(key),
                                                                               encoded)))
                     .map(_ -> next);
    }

    private Promise<Unit> removeState(K key) {
        return appendAndApply(key,
                              EntityLogRecord.delete(String.valueOf(key))).mapToUnit();
    }

    /// Append, then apply the record to the fold at the offset the log assigned it.
    ///
    /// `onSuccess` rather than `map` is deliberate: the fold must be updated for a record that reached the
    /// log even when the promise afterwards carries [EntityLogError.ReplicationBarrierUnmet]. That cause
    /// is raised by the substrate AFTER the offset exists and the record is durable, so refusing to apply
    /// it here would leave this node serving a view that disagrees with the log it recovers from.
    private Promise<Long> appendAndApply(K key, EntityLogRecord record) {
        var partition = partitionOf(key);

        return substrate.append(keyspace,
                                partition,
                                record.encode())
                        .onSuccess(offset -> fold.apply(partition, offset, record))
                        .mapError(cause -> translateAppendFailure(key, partition, record, cause));
    }

    /// Two translations, both preserving vocabulary callers already depend on.
    ///
    /// A fence rejection becomes [EntityError.StaleOwnerEpoch] — the cause this entity has always
    /// raised for a deposed owner. The fence moved from the storage engine to the log in I3; the cause a
    /// caller sees must not move with it.
    ///
    /// A barrier failure names the offset the record landed at, so the fold is still advanced: the record
    /// is durable and a recovering node will replay it, so refusing to apply it locally would leave this
    /// node serving a view that disagrees with the log it recovers from. Any other failure means nothing
    /// reached the log and there is nothing to apply.
    private Cause translateAppendFailure(K key, int partition, EntityLogRecord record, Cause cause) {
        if (cause instanceof EntityLogError.ReplicationBarrierUnmet unmet) {
            fold.apply(partition, unmet.offset(), record);

            return cause;
        }

        return cause instanceof EntityLogError.StaleOwnerAppend stale
               ? new EntityError.StaleOwnerEpoch(String.valueOf(key), stale.detail())
               : new EntityError.StorageFailed(String.valueOf(key), cause);
    }

    private int partitionOf(K key) {
        return arc.partitionOf(String.valueOf(key));
    }

    /// A codec fault for `key`'s state, rendered as a typed cause. [Serializer]/[Deserializer] report by
    /// throwing (their contract calls a codec miss a fatal misconfiguration), so this is the boundary
    /// where that exception becomes a value.
    private EntityError codecFailed(K key, Throwable throwable) {
        return new EntityError.StorageFailed(String.valueOf(key), Causes.fromThrowable(throwable));
    }

    @SuppressWarnings("unchecked")
    private S decode(byte[] bytes) {
        return (S) deserializer.decode(bytes);
    }

    private static <S> Promise<S> keyAlreadyExists(Object key) {
        return new EntityError.EntityAlreadyExists(String.valueOf(key)).promise();
    }

    private static <S> Promise<S> keyNotFound(Object key) {
        return new EntityError.EntityNotFound(String.valueOf(key)).promise();
    }
}
