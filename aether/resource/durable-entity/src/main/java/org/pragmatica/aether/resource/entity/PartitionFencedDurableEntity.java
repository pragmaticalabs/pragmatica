// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Deadline;
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
final class PartitionFencedDurableEntity<K, S, C extends Mutator<S>> implements DurableEntity<K, S, C>, EntityForwardRegistry.ForwardTarget {
    private final String keyspace;
    private final EntityLogSubstrate substrate;
    private final EntityFold fold;
    private final EntityPartitionArc arc;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final PerKeySerialExecutor<K> perKey;
    private final Option<EntityOwnerAdmission> admission;
    private Option<EntityOwnerForward> forward = Option.none();
    private final AtomicReference<Option<Runnable>> closeHook = new AtomicReference<>(Option.none());
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
    static <K, S, C extends Mutator<S>> DurableEntity<K, S, C> partitionFencedDurableEntity(String keyspace,
                                                                                            EntityLogSubstrate substrate,
                                                                                            EntityPartitionArc arc,
                                                                                            Serializer serializer,
                                                                                            Deserializer deserializer) {
        return new PartitionFencedDurableEntity<>(keyspace, substrate, arc, serializer, deserializer);
    }

    /// The wired form the node provisions: owner admission plus a [ReadConsistency#LINEARIZABLE] read
    /// routed through [LinearizableEntityServe], over the SAME arc the write fence uses — so the read
    /// fence and the write fence can never disagree about which ownership arc a key belongs to.
    static <K, S, C extends Mutator<S>> DurableEntity<K, S, C> partitionFencedDurableEntity(String keyspace,
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
        return submitWithDeadline(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return submitWithDeadline(key, () -> doGet(key));
    }

    @Override
    public Promise<Option<S>> get(K key, ReadConsistency consistency) {
        return switch (consistency) {
            case BOUNDED_STALE -> get(key);
            case LINEARIZABLE -> readLinearizable(key);
        };
    }

    private Promise<Option<S>> readLinearizable(K key) {
        // Unwired fallback note (#596 review): with no serve wired this degrades to the bounded-stale
        // read — which may now FORWARD on a non-holding node. Unreachable in production wiring
        // (DurableEntityFactory always provisions the serve); recorded, not defended.
        return linearizableServe.fold(() -> get(key), serve -> serve.serve(key));
    }

    @Override
    public Promise<S> update(K key, C mutator) {
        return submitWithDeadline(key, () -> doUpdate(key, mutator));
    }

    @Override
    public Promise<Unit> delete(K key) {
        return submitWithDeadline(key, () -> doDelete(key));
    }

    /// The caller's ambient request budget, carried across the [PerKeySerialExecutor] hop. The
    /// ScopedValue binding survives only synchronous chains, and the per-key task runs later on
    /// another thread — captured here on the caller side, re-bound inside the task, so the owner
    /// forward path can cap its wait by what the client is still willing to wait for. Callers
    /// outside any request scope capture [Deadline#unbounded()] and keep today's behavior.
    private <R> Promise<R> submitWithDeadline(K key, Supplier<Promise<R>> operation) {
        var deadline = Deadline.current();

        return perKey.submit(key, () -> Deadline.runWith(deadline, operation));
    }

    @Override
    public Promise<TimerToken> scheduleTimer(K key, Duration delay, C onFire) {
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
        return forwardTarget(key).fold(() -> admitWrite(key).fold(Cause::promise,
                                                                  _ -> ready(key).flatMap(_ -> createAdmitted(key,
                                                                                                              initial))),
                                       owner -> forwardCreate(owner, key, initial));
    }

    private Promise<S> createAdmitted(K key, S initial) {
        return readState(key).flatMap(existing -> existing.fold(() -> commit(key, initial), _ -> keyAlreadyExists(key)));
    }

    private Promise<Option<S>> doGet(K key) {
        return readForwardTarget(key).fold(() -> getLocal(key), owner -> forwardGet(owner, key));
    }

    private Promise<Option<S>> getLocal(K key) {
        return ready(key).flatMap(_ -> readState(key));
    }

    /// The `BOUNDED_STALE` read half of #596. A read is served LOCALLY by any node that HOLDS the
    /// partition — owner or replica alike; the [#ready] gates bound its staleness in offsets, which
    /// is the consistency level's whole contract. Only a node with NO local log forwards: its fold
    /// would refuse with `PartitionNotHeld` (and before the hosting-set fix it answered ABSENT from
    /// a void — the ticket's original defect). Holding is the ring-presence primitive, never
    /// inferred from a replica descriptor (#345 I3). The destination reuses the write path's
    /// positive-remote-owner-AND-wired-transport gate, so uncommitted ownership or an unwired
    /// transport keeps the honest local refusal instead of inventing a hop.
    private Option<NodeId> readForwardTarget(K key) {
        return substrate.holdsPartition(keyspace, partitionOf(key))
               ? Option.none()
               : forwardTarget(key);
    }

    private Promise<Option<S>> forwardGet(NodeId owner, K key) {
        return transport().flatMap(transport -> encodedKey(key).flatMap(encoded -> transport.forwardGet(owner,
                                                                                                        keyspace,
                                                                                                        encoded)))
                        .mapError(cause -> retypeForwarded(cause, key))
                        .flatMap(this::decodedOptionalState);
    }

    /// A remote committed owner is FORWARDED to rather than refused (#596) when a transport is wired.
    /// The owner re-runs admission on arrival, so its epoch fence still decides — the hop cannot land a
    /// write the owner would have rejected, and the per-key total order stays the owner's to enforce.
    private Promise<S> doUpdate(K key, C mutator) {
        return forwardTarget(key).fold(() -> admitWrite(key).fold(Cause::promise,
                                                                  _ -> ready(key).flatMap(_ -> updateAdmitted(key,
                                                                                                              mutator))),
                                       owner -> forwardUpdate(owner, key, mutator));
    }

    private Promise<S> updateAdmitted(K key, C mutator) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key),
                                                              state -> commit(key, mutator.apply(state))));
    }

    private Promise<Unit> doDelete(K key) {
        return forwardTarget(key).fold(() -> admitWrite(key).fold(Cause::promise,
                                                                  _ -> ready(key).flatMap(_ -> deleteAdmitted(key))),
                                       owner -> forwardDelete(owner, key));
    }

    private Promise<Unit> deleteAdmitted(K key) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key), _ -> removeState(key)));
    }

    /// Ready is TWO gates: the memoized rebuild, then a catch-up to the log's current head. The second is
    /// what keeps a fold FRESH after its one-time rebuild — a replica's fold otherwise freezes at rebuild
    /// time (nothing but the owner's own append path ever fed it), which made `BOUNDED_STALE` unbounded
    /// there and let a later-promoted owner mutate on top of a stale view, silently dropping records
    /// replicated after its rebuild. `LinearizableEntityServe` reads through [#get], so the linearizable
    /// path inherits both gates.
    private Promise<Unit> ready(K key) {
        var partition = partitionOf(key);
        // #596 review catch (MAJOR): holding is re-checked at SERVE time, not only at routing time.
        // The fold memoizes rebuild SUCCESS forever, and a ring released AFTER that rebuild leaves a
        // frozen fold whose catch-up gate is vacuous (an empty ring reports headOffset -1, so
        // appliedThrough >= -1 passes trivially) — a read served from it has NO staleness bound at
        // all, the answer-from-a-void class this ticket removes. Reachable both locally and through
        // getForwarded during the ownership-reconcile lag after a release.
        return substrate.holdsPartition(keyspace, partition)
               ? fold.ready(partition)
                     .flatMap(_ -> fold.caughtUp(partition))
               : new EntityLogError.PartitionNotHeld(keyspace, partition).promise();
    }

    /// Owner admission, ahead of the read-modify-write and ahead of the log's epoch fence: only the
    /// committed owner of the key's arc may mutate it. See [EntityOwnerAdmission].
    /// Wire owner-forwarding (#596). Absent by default so an unwired deployment behaves EXACTLY as
    /// before — a non-owner is refused rather than silently reaching a different node.
    @Contract
    void withOwnerForward(EntityOwnerForward ownerForward) {
        this.forward = Option.option(ownerForward);
    }

    /// Install the unload hook [#unload] runs — the factory builds it from the same provision-time
    /// collaborators it registered this entity with (registrar, forward registry, checkpoint driver),
    /// which are unreachable at close time otherwise.
    @Contract
    void withCloseHook(Runnable closeHook) {
        this.closeHook.set(Option.option(closeHook));
    }

    /// Unload: run the factory-installed hook exactly once — the atomic swap makes that literal, even
    /// under a concurrent double-close (each action in the hook is idempotent anyway; the swap makes the
    /// doc claim true rather than approximately true). Reached ONLY through `DurableEntityFactory.close`
    /// when the keyspace's last local consumer slice stops. Deliberately NOT `AutoCloseable.close`: the
    /// entity is handed to slices as `DurableEntity`, and a public close would let one
    /// `instanceof AutoCloseable` in slice code retract the keyspace's registration and unhook its
    /// forward target on a LIVE node — silently removing it from the hosting set until redeploy, with no
    /// re-declare path because provisioning is cached. Package-private, so only the factory can unload.
    @Contract
    void unload() {
        closeHook.getAndSet(Option.none()).onPresent(Runnable::run);
    }

    /// Forward only on a POSITIVE remote-owner reading AND a wired transport. `remoteOwner` is empty
    /// both when this node owns the arc and when no ownership is committed yet, so neither case can be
    /// mistaken for a destination.
    private Option<NodeId> forwardTarget(K key) {
        return forward.isEmpty()
               ? Option.none()
               : admission.flatMap(gate -> gate.remoteOwner(key));
    }

    /// The encodes are LIFTED for the same reason [#commit]'s is: [Serializer] throws on a codec miss,
    /// and these run INSIDE the per-key tail, so an escaping throw would leave the caller's promise
    /// unresolved — a hang rather than a typed failure.
    private Promise<S> forwardUpdate(NodeId owner, K key, C mutator) {
        return transport().flatMap(transport -> encodedPair(key,
                                                            () -> serializer.encode(mutator)).flatMap(payload -> transport.forwardUpdate(owner,
                                                                                                                                         keyspace,
                                                                                                                                         payload.key(),
                                                                                                                                         payload.body())))
                        .mapError(cause -> retypeForwarded(cause, key))
                        .flatMap(this::decodedState);
    }

    private Promise<S> forwardCreate(NodeId owner, K key, S initial) {
        return transport().flatMap(transport -> encodedPair(key,
                                                            () -> serializer.encode(initial)).flatMap(payload -> transport.forwardCreate(owner,
                                                                                                                                         keyspace,
                                                                                                                                         payload.key(),
                                                                                                                                         payload.body())))
                        .mapError(cause -> retypeForwarded(cause, key))
                        .flatMap(this::decodedState);
    }

    /// Answers with empty bytes by contract, so the result is discarded rather than decoded — decoding
    /// an empty payload as an `S` would fail on a delete that actually succeeded.
    private Promise<Unit> forwardDelete(NodeId owner, K key) {
        return transport().flatMap(transport -> encodedKey(key).flatMap(encoded -> transport.forwardDelete(owner,
                                                                                                           keyspace,
                                                                                                           encoded)))
                        .mapError(cause -> retypeForwarded(cause, key))
                        .mapToUnit();
    }

    /// Reconstruct the owner's TYPED refusal from the wire carrier. The wire flattens causes to
    /// strings, and the slice reports failures by cause TYPE — so a forwarded duplicate-create that
    /// surfaced as a generic failure instead of [EntityError.EntityAlreadyExists] read as an
    /// unexplained error to every matcher keyed on the type (02w counts acked creates exactly that
    /// way). Only the variants that legitimately cross this boundary are reconstructed; anything
    /// else keeps the carrier, whose message already names the owner's reason.
    private Cause retypeForwarded(Cause cause, K key) {
        return cause instanceof EntityOwnerForward.ForwardRefused(var failureType, var ignored)
               ? switch (failureType) {
            case "EntityAlreadyExists" -> new EntityError.EntityAlreadyExists(String.valueOf(key));
            case "EntityNotFound" -> new EntityError.EntityNotFound(String.valueOf(key));
            default -> cause;
        }
               : cause;
    }

    private Promise<EntityOwnerForward> transport() {
        return forward.toResult(FORWARD_UNWIRED)
                      .async();
    }

    private Promise<byte[]> encodedKey(K key) {
        return Result.lift(throwable -> codecFailed(key, throwable),
                           () -> serializer.encode(key))
                     .async();
    }

    private Promise<EncodedPayload> encodedPair(K key, Supplier<byte[]> body) {
        return Result.lift(throwable -> codecFailed(key, throwable),
                           () -> new EncodedPayload(serializer.encode(key),
                                                    body.get()))
                     .async();
    }

    /// One lift covers BOTH encodes: either can throw, and both failures are the same codec fault.
    private record EncodedPayload(byte[] key, byte[] body) {}

    /// Apply a command that ARRIVED from a non-owner. Goes through this instance's own per-key queue and
    /// its own admission, so the hop neither bypasses the single-writer total order nor escapes the
    /// epoch fence — a write forwarded to a node that has since been deposed is refused here, exactly as
    /// a local write would be.
    ///
    /// Deliberately calls the LOCAL path rather than [#doUpdate]: re-entering the forwarding decision on
    /// the receiving side would let a stale ownership view bounce a command between two nodes.
    @Override
    public Promise<byte[]> applyForwarded(byte[] encodedKey, byte[] encodedCommand) {
        return decoded(encodedKey).flatMap(key -> decodedCommand(encodedCommand).flatMap(mutator -> admittedOnOwner(key,
                                                                                                                    () -> updateAdmitted(key,
                                                                                                                                         mutator))))
                      .flatMap(this::encodedState);
    }

    /// The create half. Runs [#createAdmitted], so the owner's own already-exists check governs — a
    /// forwarded create cannot overwrite a key a local create would have refused.
    @Override
    public Promise<byte[]> createForwarded(byte[] encodedKey, byte[] encodedInitial) {
        return decoded(encodedKey).flatMap(key -> decodedState(encodedInitial).flatMap(initial -> admittedOnOwner(key,
                                                                                                                  () -> createAdmitted(key,
                                                                                                                                       initial))))
                      .flatMap(this::encodedState);
    }

    /// The delete half. Answers EMPTY bytes rather than an encoded state: a delete has no post-state, and
    /// the sender discards the payload.
    @Override
    public Promise<byte[]> deleteForwarded(byte[] encodedKey) {
        return decoded(encodedKey).flatMap(key -> admittedOnOwner(key,
                                                                  () -> deleteAdmitted(key)))
                      .map(_ -> new byte[0]);
    }

    /// The shared receiving-side shape: this instance's own per-key queue and its own admission, so the
    /// hop neither bypasses the single-writer total order nor escapes the epoch fence.
    private <R> Promise<R> admittedOnOwner(K key, Supplier<Promise<R>> operation) {
        return perKey.submit(key,
                             () -> admitWrite(key).fold(Cause::promise,
                                                        _ -> ready(key).flatMap(_ -> operation.get())));
    }

    /// The read half, as [#applyForwarded] is update: the LOCAL bounded-stale path through this
    /// instance's own per-key queue — never [#doGet], which would re-enter the forwarding decision and
    /// let a stale ownership view bounce a read between two nodes. Deliberately WITHOUT the write
    /// admission: a local bounded-stale read runs none either, and the answer claims a staleness
    /// bound, not currency. Absence is an explicit empty Option end to end.
    @Override
    public Promise<Option<byte[]>> getForwarded(byte[] encodedKey) {
        return decoded(encodedKey).flatMap(key -> submitWithDeadline(key,
                                                                     () -> getLocal(key)))
                      .flatMap(this::encodedOptionalState);
    }

    private Promise<Option<byte[]>> encodedOptionalState(Option<S> state) {
        return state.fold(() -> Promise.success(Option.none()),
                          present -> encodedState(present).map(Option::some));
    }

    /// Decoding is LIFTED on arrival: [Deserializer] throws on a codec miss, and a throw escaping here
    /// would propagate into the message router instead of answering the sender — which turns a typed
    /// failure into the sender's 30s timeout.
    @SuppressWarnings("unchecked")
    private Promise<K> decoded(byte[] encodedKey) {
        return Result.lift(FORWARD_KEY_UNDECODABLE,
                           () -> (K) deserializer.decode(encodedKey))
                     .async();
    }

    @SuppressWarnings("unchecked")
    private Promise<C> decodedCommand(byte[] encodedCommand) {
        return Result.lift(FORWARD_COMMAND_UNDECODABLE,
                           () -> (C) deserializer.decode(encodedCommand))
                     .async();
    }

    @SuppressWarnings("unchecked")
    private Promise<S> decodedState(byte[] encodedState) {
        return Result.lift(FORWARD_STATE_UNDECODABLE,
                           () -> (S) deserializer.decode(encodedState))
                     .async();
    }

    /// #596 review catch (MAJOR): decoding a forwarded answer must be LIFTED like every other
    /// arrival-side decode in this file — `Deserializer.decode` throws on a codec miss, and a throw
    /// escaping a bare `map` on the response-dispatch thread leaves the caller's promise UNRESOLVED:
    /// a hang instead of a typed failure.
    private Promise<Option<S>> decodedOptionalState(Option<byte[]> bytes) {
        return bytes.fold(() -> Promise.success(Option.none()),
                          present -> decodedState(present).map(Option::some));
    }

    private Promise<byte[]> encodedState(S state) {
        return Result.lift(FORWARD_STATE_UNENCODABLE,
                           () -> serializer.encode(state))
                     .async();
    }

    private static final Fn1<Cause, Throwable> FORWARD_KEY_UNDECODABLE = throwable -> Causes.cause("forwarded entity key could not be decoded: " + throwable.getMessage());

    private static final Fn1<Cause, Throwable> FORWARD_COMMAND_UNDECODABLE = throwable -> Causes.cause("forwarded entity command could not be decoded: " + throwable.getMessage());

    private static final Fn1<Cause, Throwable> FORWARD_STATE_UNDECODABLE = throwable -> Causes.cause("forwarded entity initial state could not be decoded: " + throwable.getMessage());

    private static final Fn1<Cause, Throwable> FORWARD_STATE_UNENCODABLE = throwable -> Causes.cause("forwarded entity result state could not be encoded: " + throwable.getMessage());

    private static final Cause FORWARD_UNWIRED = Causes.cause("entity owner-forward transport disappeared between the target check and the send");

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
