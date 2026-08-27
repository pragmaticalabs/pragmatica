// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


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
/// ## Timers (#345 I4)
/// [#scheduleTimer] and [#cancelTimer] are ordinary fenced writes on the key's own log, and
/// [#fireDueTimers] is the tick that consumes them. Everything a timer needs — the instant, the command,
/// the fact that it is still pending — is folded from that log, so a new owner inherits the timer wheel by
/// replaying rather than by being told. What that does and does not guarantee is stated on each method;
/// the three facts worth knowing here are that the instant is WALL-CLOCK and stamped by the committed
/// OWNER at append — a schedule arriving from elsewhere carries a delay, not an instant — that a fire
/// whose command cannot be applied CONSUMES its timer rather than retrying it, and that the timer's token
/// comes from the CALLER and is fixed before any hop — so a schedule re-sent under that token is
/// recognised as the same schedule instead of planting a second timer.
///
/// @param <K> entity key type — rendered to bytes via `String.valueOf` for the log record
/// @param <S> entity state type — an application-defined immutable value, encoded via [Serializer]
final class PartitionFencedDurableEntity<K, S, C extends Mutator<S>> implements DurableEntity<K, S, C>, EntityForwardRegistry.ForwardTarget {
    private static final Logger LOG = LoggerFactory.getLogger(PartitionFencedDurableEntity.class);
    private static final long READINESS_RETRY_BASE_MILLIS = 1_000L;
    private static final long READINESS_RETRY_CAP_MILLIS = 60_000L;
    private static final long MAX_BACKOFF_DOUBLINGS = 16L;
    private static final long READINESS_WARN_EVERY_N_FAILURES = 10L;
    private static final long CONSUME_ERROR_EVERY_N_FAILURES = 60L;

    private final String keyspace;
    private final EntityLogSubstrate substrate;
    private final EntityFold fold;
    private final EntityPartitionArc arc;
    private final Serializer serializer;
    private final Deserializer deserializer;
    /// Keyed on the RENDERED key, not on `K`. Two reasons, and the second is the one that forced it: the
    /// fold, the log and the partition mapping have always identified a key by `String.valueOf(K)`, so two
    /// `K` values rendering alike were already ONE entity while getting two independent serialization
    /// tails; and a timer fire recovers only the rendered key from the log, so it could not otherwise join
    /// the same tail as a concurrent [#update] on that key.
    private final PerKeySerialExecutor<String> perKey;
    private final Option<EntityOwnerAdmission> admission;
    private final Map<Integer, ReadinessBackoff> readinessBackoff = new ConcurrentHashMap<>();
    /// Consecutive consume-append failures per stuck timer, so the ERROR that reports one can be
    /// rate-limited while still carrying how long it has been stuck. See [#failedConsumes].
    private final Map<TimerId, AtomicLong> unconsumedFires = new ConcurrentHashMap<>();
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

        return perKey.submit(String.valueOf(key), () -> Deadline.runWith(deadline, operation));
    }

    /// Schedule a one-shot timer on `key`, due `delay` from NOW (#345 I4).
    ///
    /// A fenced write like any other: per-key serialized, owner-admitted, and gated on the partition's
    /// fold, appending a [EntityLogRecord.Op#TIMER_SCHEDULE] under the same replication barrier a
    /// [#update] rides. The key must EXIST — a timer on a key that holds no state has nothing to mutate on
    /// expiry, so it is refused with [EntityError.EntityNotFound] at schedule time rather than discovered
    /// as a failed fire minutes later.
    ///
    /// ## What the instant means, precisely
    /// `fireAt` is stamped by the committed OWNER, at append, from the owner's own wall clock — this
    /// method runs on the owner (a non-owner forwards, and a forwarded schedule carries a DELAY that the
    /// owner stamps on arrival), so the clock that mints the instant is the clock that later finds the
    /// timer due and sender/owner skew never enters. The instant then travels in the record, so the delay
    /// is not restarted by a handover, a checkpoint or a restart. Skew enters in one place only: across a
    /// HANDOVER, where the stamped instant is read by a SUCCESSOR owner's clock and the fire shifts by the
    /// difference between the two. Accepted deliberately **[design intent — unverified]**: a monotonic
    /// alternative cannot survive a process boundary, and bounding skew would mean making the entity
    /// depend on cluster time sync it does not otherwise need.
    ///
    /// ## Non-owners are FORWARDED to the committed owner
    /// Like [#create]/[#update]/[#delete], this consults the forward target: a remote committed owner
    /// receives the schedule and re-runs its own admission on arrival, so the owner's epoch fence still
    /// decides. With no transport wired, or with no ownership committed, the honest local refusal
    /// ([EntityError.NotCurrentOwner]) stands instead.
    ///
    /// ## The token ARRIVES; it is not minted here
    /// Minting happens at the caller — either at [DurableEntity#scheduleTimer(Object, Duration, Mutator)]'s
    /// default, or at an application that minted its own to be able to retry with. Either way the token is
    /// fixed before this method runs, which puts it ahead of [#submitWithDeadline] — ahead of the per-key
    /// tail, the admission check and the forwarding decision alike — so the local path and the forwarded
    /// path carry the SAME token. A token minted on the owner and returned in the response would be lost
    /// with the response: the schedule succeeds, the caller sees a failure, and the durable timer it planted
    /// can never be cancelled, because cancel takes a token and there is no cancel-by-key verb. Taking the
    /// token as a parameter makes a re-sent schedule the SAME schedule — the owner recognises an
    /// already-pending token and appends nothing — and leaves the caller holding a cancellable handle even
    /// when the acknowledgement is lost.
    ///
    /// @return `token`, once this timer is pending — the handle [#cancelTimer] takes
    @Override
    public Promise<TimerToken> scheduleTimer(K key, Duration delay, C onFire, TimerToken token) {
        return submitWithDeadline(key, () -> doScheduleTimer(key, delay, onFire, token));
    }

    /// A remote committed owner is FORWARDED to rather than refused, on the same terms as every other
    /// verb (#596): the owner re-runs admission on arrival, so its epoch fence still decides, and the
    /// timer is appended to the log the owner already serialises this key through.
    private Promise<TimerToken> doScheduleTimer(K key, Duration delay, C onFire, TimerToken token) {
        return forwardTarget(key).fold(() -> admittedLocally(key, () -> scheduleAdmitted(key, delay, onFire, token)),
                                       owner -> forwardScheduleTimer(owner, key, delay, onFire, token));
    }

    private Promise<TimerToken> scheduleAdmitted(K key, Duration delay, C onFire, TimerToken token) {
        return readState(key).flatMap(existing -> existing.fold(() -> keyNotFound(key),
                                                                _ -> scheduleUnlessPending(key, delay, onFire, token)));
    }

    /// The re-sent-schedule guard, and the reason a caller-minted token is safe to retry with.
    ///
    /// A schedule carrying a token this key already has pending IS that schedule, arriving a second time
    /// because the first acknowledgement was lost — not a request for a second timer. Appending again
    /// would plant a duplicate the caller holds only one handle for, so the answer is the same token with
    /// no record written. Asked inside the key's serialization tail, which is what makes it decisive
    /// rather than advisory: the same question, and the same fold predicate, that [#cancelAdmitted] and
    /// [#fireStillPending] ask.
    private Promise<TimerToken> scheduleUnlessPending(K key, Duration delay, C onFire, TimerToken token) {
        return fold.isTimerPending(partitionOf(key), String.valueOf(key), token.value())
               ? Promise.success(token)
               : appendSchedule(key, delay, onFire, token);
    }

    /// The encode is LIFTED for the same reason [#commit]'s is: [Serializer] throws on a codec miss, and a
    /// throw escaping the per-key tail hangs the caller instead of failing it. The token ARRIVES here,
    /// minted by the caller, and identifies the timer only WITHIN its key — the fold keys pending timers by
    /// `(key, token)`, so collisions across keys are not a concern and uniqueness is owed only against the
    /// caller's own concurrent schedules on the same key.
    private Promise<TimerToken> appendSchedule(K key, Duration delay, C onFire, TimerToken token) {
        var fireAt = fireInstant(System.currentTimeMillis(), delay);

        return Result.lift(throwable -> codecFailed(key, throwable),
                           () -> serializer.encode(onFire))
                     .async()
                     .flatMap(encoded -> appendAndApply(key,
                                                        EntityLogRecord.timerSchedule(String.valueOf(key),
                                                                                      token.value(),
                                                                                      fireAt,
                                                                                      encoded)))
                     .map(_ -> token);
    }

    /// The fire instant, SATURATING at [Long#MAX_VALUE] rather than wrapping.
    ///
    /// `now + delay` is unchecked arithmetic on a delay that reaches here from the WIRE — a forwarded
    /// schedule carries `EntityScheduleTimerForward.delayMillis`, which no sender is trusted to bound. Past
    /// [Long#MAX_VALUE] the sum wraps NEGATIVE, and [EntityFold#dueTimers] compares instants with `<=`, so
    /// a timer asked to fire in ten million years would fire on the very next tick. Saturating keeps the
    /// one promise [#scheduleTimer] makes unconditionally: at or after the requested instant, never before.
    ///
    /// The millis conversion saturates too, because [Duration#toMillis] THROWS above `Long.MAX_VALUE`
    /// millis and this runs inside the per-key serialization tail, where an escaping throw leaves the
    /// caller's promise unresolved and wedges the key for good.
    ///
    /// Underflow is deliberately not handled: `now` is a wall clock in the 1e12 range, so even
    /// [Long#MIN_VALUE] millis of delay lands far above [Long#MIN_VALUE]. A negative delay therefore yields
    /// a past instant, which is due immediately — the honest reading of "fire this many millis ago", and
    /// the arrival path refuses one outright (see [#scheduleTimerForwarded]).
    ///
    /// Package-private so the boundary is pinned as a RULE rather than inferred from a fire that did not
    /// happen for a century.
    static long fireInstant(long nowMillis, Duration delay) {
        var delayMillis = saturatedMillis(delay);
        var fireAt = nowMillis + delayMillis;

        return delayMillis > 0L && fireAt < nowMillis
               ? Long.MAX_VALUE
               : fireAt;
    }

    private static long saturatedMillis(Duration delay) {
        return delay.compareTo(MAX_DELAY) >= 0
               ? Long.MAX_VALUE
               : delay.toMillis();
    }

    private static final Duration MAX_DELAY = Duration.ofMillis(Long.MAX_VALUE);

    /// Cancel a previously scheduled timer (#345 I4). Idempotent once it reaches the owner, per spec §5.1:
    /// a token that already fired, was already cancelled, belongs to a key that was deleted (delete
    /// auto-cancels), or names a schedule that never landed at all is SUCCESS with NO record appended —
    /// there is nothing to consume, and appending a second cancel would grow the log for every retry of an
    /// operation that already happened.
    ///
    /// Non-owners are FORWARDED to the committed owner, exactly as [#scheduleTimer] is: the owner re-runs
    /// its own admission on arrival and its idempotence governs. With no transport wired, or with no
    /// ownership committed, the local refusal stands.
    @Override
    public Promise<Unit> cancelTimer(K key, TimerToken token) {
        return submitWithDeadline(key, () -> doCancelTimer(key, token));
    }

    /// Forwarded on the same terms as [#doScheduleTimer]. Cancel is idempotent, so a retry that races a
    /// fire or a delete still succeeds — the owner finds nothing pending and answers success.
    private Promise<Unit> doCancelTimer(K key, TimerToken token) {
        return forwardTarget(key).fold(() -> admittedLocally(key, () -> cancelAdmitted(key, token)),
                                       owner -> forwardCancelTimer(owner, key, token));
    }

    private Promise<Unit> cancelAdmitted(K key, TimerToken token) {
        return fold.isTimerPending(partitionOf(key), String.valueOf(key), token.value())
               ? appendAndApply(key,
                                EntityLogRecord.timerCancel(String.valueOf(key), token.value())).mapToUnit()
               : Promise.unitPromise();
    }

    /// Every operation waits for its partition's fold before touching state. A partition still replaying
    /// refuses with the transient [EntityLogError.FoldInProgress] rather than serving or mutating a
    /// half-built view — the alternative is a create that succeeds because a prior value has not been
    /// replayed yet, which would overwrite committed state.
    private Promise<S> doCreate(K key, S initial) {
        return forwardTarget(key).fold(() -> admittedLocally(key, () -> createAdmitted(key, initial)),
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
        return forwardTarget(key).fold(() -> admittedLocally(key, () -> updateAdmitted(key, mutator)),
                                       owner -> forwardUpdate(owner, key, mutator));
    }

    private Promise<S> updateAdmitted(K key, C mutator) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key),
                                                              state -> commit(key, mutator.apply(state))));
    }

    private Promise<Unit> doDelete(K key) {
        return forwardTarget(key).fold(() -> admittedLocally(key, () -> deleteAdmitted(key)),
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
    private Promise<Unit> ready(Object key) {
        return readyPartition(partitionOf(key));
    }

    private Promise<Unit> readyPartition(int partition) {
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

    /// The timer tick, driven by [EntityTimerDriver] (#345 I4): fire every timer of this keyspace whose
    /// instant has passed at `nowMillis`.
    ///
    /// Readiness is DRIVEN here rather than assumed. A node that has just taken a partition over has an
    /// empty fold until something touches it, and an empty fold reports no due timers — so a tick that
    /// only read the fold would leave every inherited timer dormant until unrelated traffic happened to
    /// rebuild the partition. That is the whole handover case this increment exists for, so the tick pays
    /// the (memoized, short-circuiting) readiness call itself.
    @Contract
    void fireDueTimers(long nowMillis) {
        for (var partition = 0; partition < arc.partitionCount(); partition++) {
            fireDuePartition(partition, nowMillis);
        }
    }

    /// A partition this node does not HOLD, or does not OWN, is skipped before anything else happens.
    ///
    /// The order is load-bearing. Driving readiness first would make every REPLICA rebuild and
    /// catch-up-poll every keyspace's fold on every tick — a checkpoint load and a log replay per
    /// partition, as a side effect of a tick that was always going to fire nothing. The ownership answer
    /// costs one map lookup and is available without a key, so it goes first and the expensive work stays
    /// on the node that will actually use it.
    ///
    /// The per-key filter in [#submitDueTimers] and the re-check in [#fireAdmitted] are still both there:
    /// this reading can go stale between here and the task running, and only the in-task check is inside
    /// the key's serialization tail.
    @Contract
    private void fireDuePartition(int partition, long nowMillis) {
        if (!substrate.holdsPartition(keyspace, partition) || !isPartitionOwned(partition)) {
            return;
        }

        if (isReadinessBackedOff(partition, nowMillis)) {
            return;
        }

        readyPartition(partition).onSuccess(_ -> fireReadyPartition(partition, nowMillis))
                      .onFailure(cause -> readinessFailed(partition, nowMillis, cause));
    }

    /// An entity built WITHOUT admission (the unwired fence-test form) owns everything, matching
    /// [#admitWrite]'s permissive fallback — the two must agree, or a unit-test entity would schedule
    /// timers it then refuses to fire.
    private boolean isPartitionOwned(int partition) {
        return admission.fold(() -> true, gate -> gate.isPartitionOwner(partition));
    }

    @Contract
    private void fireReadyPartition(int partition, long nowMillis) {
        backoffOf(partition).reset();
        submitDueTimers(partition, nowMillis);
    }

    /// A rebuild that keeps failing must not be re-attempted on every tick. `EntityFold` clears its
    /// rebuild memo on failure — deliberately, so a transient condition can resolve — which means an
    /// un-backed-off tick would reload a checkpoint and replay a log per partition per interval, forever,
    /// against shared storage. Attempts therefore back off exponentially from [#READINESS_RETRY_BASE_MILLIS]
    /// to [#READINESS_RETRY_CAP_MILLIS].
    ///
    /// Measured against the TICK's instant, not a private clock, and that is a real assumption rather than
    /// an implementation detail: the window only means anything if successive ticks carry non-decreasing
    /// instants. [EntityTimerDriver] passes `System.currentTimeMillis()`, so a large BACKWARD wall-clock
    /// correction would hold a partition off until the clock catches up. Accepted deliberately: `fireAt` is
    /// already wall-clock (see [#scheduleTimer]), so a second clock here would buy consistency in one place
    /// by breaking it in another, and the failure mode is a delayed retry rather than lost state.
    private boolean isReadinessBackedOff(int partition, long nowMillis) {
        return nowMillis < backoffOf(partition).retryNotBefore.get();
    }

    @Contract
    private void readinessFailed(int partition, long nowMillis, Cause cause) {
        var backoff = backoffOf(partition);
        var failures = backoff.consecutiveFailures.incrementAndGet();

        backoff.retryNotBefore.set(nowMillis + retryDelayMillis(failures));
        logReadinessFailure(partition, failures, cause);
    }

    /// The delay before a partition whose readiness failed `failures` times in a row is re-attempted:
    /// exponential from [#READINESS_RETRY_BASE_MILLIS], capped at [#READINESS_RETRY_CAP_MILLIS]. The cap
    /// matters as much as the growth — an uncapped backoff would eventually stop retrying a partition that
    /// is only transiently broken.
    ///
    /// Package-private so the schedule is pinned as a RULE rather than inferred from tick counts.
    static long retryDelayMillis(long failures) {
        return Math.min(READINESS_RETRY_CAP_MILLIS,
                        READINESS_RETRY_BASE_MILLIS<< Math.min(failures - 1L, MAX_BACKOFF_DOUBLINGS));
    }

    /// Which consecutive-failure counts get a WARN rather than a DEBUG. The first always does — it may be
    /// the only one — and then every [#READINESS_WARN_EVERY_N_FAILURES]-th, which at the capped backoff is
    /// one line every ten minutes.
    ///
    /// Package-private for the same reason [#retryDelayMillis] is: a rate limit that can only be observed
    /// as log volume cannot be asserted, and an un-asserted rate limit is how a WARN quietly becomes either
    /// a flood or a silence.
    static boolean isWarnWorthy(long consecutiveFailures) {
        return consecutiveFailures == 1L || consecutiveFailures % READINESS_WARN_EVERY_N_FAILURES == 0L;
    }

    /// WARN, not DEBUG, and rate-limited rather than silenced. A partition that never becomes ready fires
    /// NO timers at all, which to every slice waiting on one is indistinguishable from a timer scheduled
    /// for later — so an operator has to be able to see it without turning on debug logging.
    @Contract
    private void logReadinessFailure(int partition, long failures, Cause cause) {
        if (isWarnWorthy(failures)) {
            LOG.warn("Durable entity timer tick cannot ready keyspace '{}' partition {} after {} consecutive"
                    + " failure(s): {} — NO timer on this partition can fire until it rebuilds; retrying with"
                    + " backoff, and an operator must clear the underlying fold failure",
                     keyspace,
                     partition,
                     failures,
                     cause.message());
        } else {
            LOG.debug("Durable entity timer tick still cannot ready keyspace '{}' partition {} ({} consecutive"
                     + " failures): {}",
                      keyspace,
                      partition,
                      failures,
                      cause.message());
        }
    }

    private ReadinessBackoff backoffOf(int partition) {
        return readinessBackoff.computeIfAbsent(partition, _ -> new ReadinessBackoff());
    }

    /// Retry state for one partition whose readiness drive keeps failing. Reset on the first success, so a
    /// partition that recovers pays no penalty for having been broken.
    private static final class ReadinessBackoff {
        private final AtomicLong consecutiveFailures = new AtomicLong();
        private final AtomicLong retryNotBefore = new AtomicLong();

        @Contract
        void reset() {
            consecutiveFailures.set(0L);
            retryNotBefore.set(0L);
        }
    }

    /// Admission is asked THREE times on the way to a fire, at three different instants, and each reading
    /// covers a window the one before it cannot.
    ///
    /// [#fireDuePartition] asks first, per PARTITION and before anything expensive, so a node that HOLDS
    /// the partition without OWNING it never drives a rebuild for timers it will not fire. Here it is
    /// asked again, per KEY, because the readiness gate sitting between the two is ASYNCHRONOUS: a
    /// partition that must rebuild can sit there for as long as the replay takes, and a handover landing
    /// in that window would otherwise queue a task per due timer on a node that has just been deposed.
    /// Inside the task ([#fireAdmitted]) it DECIDES, because that is the only reading taken inside the
    /// key's serialization tail — the first two are filters, and either can go stale the moment it is
    /// taken.
    @Contract
    private void submitDueTimers(int partition, long nowMillis) {
        fold.dueTimers(partition, nowMillis)
            .stream()
            .filter(due -> admitWrite(due.key()).isSuccess())
            .forEach(due -> submitFire(partition, due));
    }

    /// The fire runs on the key's OWN serialization tail, so it is totally ordered against every
    /// concurrent create / update / delete on that key — the same guarantee an external update gets, which
    /// is what spec §4.5 means by "applies the scheduled operation via the same path as an external
    /// update". [Deadline#unbounded()] because there is no caller whose budget could bound it.
    @Contract
    private void submitFire(int partition, EntityFold.DueTimer due) {
        perKey.submit(due.key(),
                      () -> Deadline.runWith(Deadline.unbounded(),
                                             () -> fireAdmitted(partition, due)))
              .onFailure(cause -> logFireDeferred(due, cause));
    }

    private Promise<Unit> fireAdmitted(int partition, EntityFold.DueTimer due) {
        return admitWrite(due.key()).fold(Cause::promise,
                                          _ -> readyPartition(partition).flatMap(_ -> fireStillPending(partition, due)));
    }

    /// The double-fire guard. A fire is asynchronous and the token only leaves the fold once its record
    /// lands, so a tick that runs while an earlier fire of the SAME timer is still in flight re-observes it
    /// as due. Re-asking the fold inside the key's serialization tail is what makes that a no-op instead of
    /// a second application of the command.
    private Promise<Unit> fireStillPending(int partition, EntityFold.DueTimer due) {
        return fold.isTimerPending(partition, due.key(), due.token())
               ? fireTimer(due)
               : Promise.unitPromise();
    }

    /// The fire, split at the point where the failure MODE changes.
    ///
    /// Everything [#preparedFire] does — decode the command, read the state, apply the mutator, encode the
    /// result — is a pure function of bytes already in the fold, so any failure there is DETERMINISTIC and
    /// will recur identically on every future tick. Everything after it is an append, whose failures are
    /// ENVIRONMENTAL: a fence rejection on handover, a quorum or transport fault. The two get opposite
    /// treatment, and splitting the pipeline is what makes each branch's rationale true of everything it
    /// covers rather than of most of it.
    private Promise<Unit> fireTimer(EntityFold.DueTimer due) {
        return preparedFire(due).fold(result -> firePrepared(due, result));
    }

    private Promise<byte[]> preparedFire(EntityFold.DueTimer due) {
        return decodedCommand(due.command()).flatMap(command -> firedState(due, command))
                             .flatMap(next -> encodedFireState(due, next));
    }

    private Promise<Unit> firePrepared(EntityFold.DueTimer due, Result<byte[]> prepared) {
        return prepared.fold(cause -> consumeByCancel(due, cause), next -> appendPreparedFire(due, next));
    }

    private Promise<S> firedState(EntityFold.DueTimer due, C command) {
        return readState(due.key()).flatMap(current -> current.fold(() -> keyNotFound(due.key()),
                                                                    state -> appliedCommand(due, command, state)));
    }

    /// The mutator is the AUTHOR's code and it can throw. Lifted here for the same reason every codec call
    /// in this file is: an escaping throw on the per-key tail wedges the key's serialization for good, and
    /// a wedged key is a worse outcome than a consumed timer.
    private Promise<S> appliedCommand(EntityFold.DueTimer due, C command, S state) {
        return Result.lift(throwable -> timerFireFailed(due,
                                                        Causes.fromThrowable(throwable)),
                           () -> command.apply(state))
                     .async();
    }

    /// Typed as a timer failure rather than the generic codec fault, and placed in the PREPARATION half
    /// deliberately: a state that will not encode is a property of the state and the codec, so it fails the
    /// same way every tick. Leaving it inside the append half would have made it indistinguishable from a
    /// fence rejection, which is the exact confusion this split removes.
    private Promise<byte[]> encodedFireState(EntityFold.DueTimer due, S next) {
        return Result.lift(throwable -> timerFireFailed(due,
                                                        Causes.fromThrowable(throwable)),
                           () -> serializer.encode(next))
                     .async();
    }

    private Promise<Unit> appendPreparedFire(EntityFold.DueTimer due, byte[] encoded) {
        return appendAndApply(due.key(),
                              EntityLogRecord.timerFire(due.key(), due.token(), encoded)).fold(result -> settleAppendedFire(due,
                                                                                                                            result));
    }

    /// Consume-on-failure, for DETERMINISTIC failures only, with the recovery strategy stated plainly.
    ///
    /// Reached when the command did not decode, the key held no state, the mutator threw, or the post-fire
    /// state would not encode. Every one of those is a pure function of bytes the fold already holds, so
    /// the next tick fails identically — leaving the timer pending would be an unbounded retry loop wearing
    /// a one-shot API. The failure is therefore absorbed (**FER — degrade-and-continue**): the timer is
    /// consumed, the key's state is left exactly as it was, and the fault is logged with keyspace, key,
    /// token and cause.
    ///
    /// **Guarantee earned:** the command is applied AT MOST ONCE, and the timer leaves the pending set as
    /// soon as the consume record reaches the log — so a bad command costs one lost timer instead of a
    /// permanently spinning tick. **Mechanism:** a single attempt plus a durable
    /// [EntityLogRecord.Op#TIMER_CANCEL]; no retry, no dead-letter, no outbox. Retrying the COMMAND is
    /// REJECTED rather than unimplemented, on the determinism above. The operator's recovery action is to
    /// fix the command and schedule again — nothing clears this automatically.
    ///
    /// **The consume itself can fail, and then the timer does NOT leave the pending set.** If the cancel
    /// record reaches the log nowhere — a fence rejection, a quorum or transport fault — the timer stays
    /// pending, every tick re-prepares the same failing fire and re-attempts the consume, and
    /// [#logRetriedConsume] reports it at ERROR with the attempt count. That loop ends when the append
    /// fault clears, not when the command is fixed, so it carries its own recovery action. See
    /// [#settleConsumedFire] for the three outcomes and how each is worded.
    ///
    /// The report is emitted by [#settleConsumedFire], AFTER the consume append answers, because until
    /// then it is not known which of three different things happened — and the three need different words.
    ///
    /// Fire-append failures never reach here; see [#settleAppendedFire].
    private Promise<Unit> consumeByCancel(EntityFold.DueTimer due, Cause cause) {
        return appendAndApply(due.key(),
                              EntityLogRecord.timerCancel(due.key(), due.token())).fold(result -> settleConsumedFire(due,
                                                                                                                     cause,
                                                                                                                     result));
    }

    /// Three outcomes, three reports. The consume append either landed (the timer IS gone), landed without
    /// meeting its replication barrier (the timer is gone HERE), or never reached the log at all (the timer
    /// is still pending and every tick will retry it). One line claiming the third for all three is how a
    /// consumed timer got reported as a stuck one.
    private Promise<Unit> settleConsumedFire(EntityFold.DueTimer due, Cause cause, Result<Long> result) {
        return Promise.success(result.fold(consumeCause -> unsettledConsume(due, cause, consumeCause),
                                           _ -> consumedFire(due, cause)));
    }

    private Unit unsettledConsume(EntityFold.DueTimer due, Cause cause, Cause consumeCause) {
        return consumeCause instanceof EntityLogError.ReplicationBarrierUnmet
               ? underReplicatedConsume(due, cause, consumeCause)
               : retriedConsume(due, cause, consumeCause);
    }

    /// The consume landed. The timer is durably gone, so this can never repeat for it — no rate limit is
    /// needed or wanted, and the failure counter is dropped because the state it counted has changed.
    private Unit consumedFire(EntityFold.DueTimer due, Cause cause) {
        unconsumedFires.remove(fireKey(due));
        logFireFailed(due, cause);

        return unit();
    }

    /// [EntityLogError.ReplicationBarrierUnmet] on the CONSUME record, which is not a failed consume: the
    /// record is in the log and [#translateAppendFailure] has already applied it, so the token is gone here
    /// exactly as if the append had answered success. It is reported separately only because what happens
    /// if THIS node is lost differs — a recovering owner would find the timer still pending and fire it,
    /// which is the one path on which a consumed timer runs its command after all. Counted as settled, so
    /// the retry counter is dropped.
    private Unit underReplicatedConsume(EntityFold.DueTimer due, Cause cause, Cause consumeCause) {
        unconsumedFires.remove(fireKey(due));
        logUnderReplicatedConsume(due, cause, consumeCause);

        return unit();
    }

    /// Nothing reached the log — a fence rejection, a quorum or transport fault — so the timer really does
    /// stay pending and really will be retried on every tick. The attempt count is what makes the retry
    /// loop legible: without it, one line looks the same whether the fault started a second ago or an hour
    /// ago.
    private Unit retriedConsume(EntityFold.DueTimer due, Cause cause, Cause consumeCause) {
        logRetriedConsume(due, cause, consumeCause, failedConsumes(due).incrementAndGet());

        return unit();
    }

    /// Keyed on `(key, token)`, because that pair IS a timer's identity — a key may hold several timers and
    /// only one of them may be stuck. Entries are dropped the moment the consume settles, so the map holds
    /// one counter per CURRENTLY stuck timer. A key deleted while one of its timers is stuck leaves its
    /// counter behind (delete auto-cancels, so the timer never returns to ask); the residue is one small
    /// entry per such timer and is accepted rather than swept.
    private AtomicLong failedConsumes(EntityFold.DueTimer due) {
        return unconsumedFires.computeIfAbsent(fireKey(due), _ -> new AtomicLong());
    }

    private static TimerId fireKey(EntityFold.DueTimer due) {
        return new TimerId(due.key(), due.token());
    }

    /// A timer's identity as a map key. A record rather than a joined string, because a rendered entity
    /// key may contain ANY character: under a delimiter, key `a b` with token `c` and key `a` with token
    /// `b c` collide, and two unrelated timers would then share one failure counter.
    private record TimerId(String key, String token) {}

    /// The append half, where failures are ENVIRONMENTAL and therefore DEFERRED rather than consumed.
    ///
    /// A fence rejection ([EntityError.StaleOwnerEpoch]) means this node was deposed mid-tick; a
    /// [EntityError.StorageFailed] means a quorum or transport fault. Nothing reached the log in either
    /// case, both clear by themselves, and consuming would destroy a perfectly good timer on a routine
    /// handover while logging an ERROR that names a broken command when nothing is broken. So the record is
    /// NOT appended, the timer stays pending, and the owner that succeeds fires it.
    ///
    /// [EntityLogError.ReplicationBarrierUnmet] is the one append failure that is neither: its record IS in
    /// the log and IS applied. See [#underReplicatedFire].
    private Promise<Unit> settleAppendedFire(EntityFold.DueTimer due, Result<Long> result) {
        return Promise.success(result.fold(cause -> deferFailedFire(due, cause), _ -> unit()));
    }

    private Unit deferFailedFire(EntityFold.DueTimer due, Cause cause) {
        return cause instanceof EntityLogError.ReplicationBarrierUnmet
               ? underReplicatedFire(due, cause)
               : deferredFire(due, cause);
    }

    private Unit deferredFire(EntityFold.DueTimer due, Cause cause) {
        logFireDeferred(due, cause);

        return unit();
    }

    /// The barrier missed, but the fire's record IS in the log and IS applied here (see
    /// [#translateAppendFailure]) — the token is already gone and the state already advanced. There is no
    /// caller to hand that third outcome to and nothing left to consume, so this logs loudly and reports
    /// success. Deliberately NOT retried: a second fire would append a second record and apply the command
    /// twice, which is precisely the hazard [EntityLogError.ReplicationBarrierUnmet] names for `update`.
    private Unit underReplicatedFire(EntityFold.DueTimer due, Cause cause) {
        LOG.error("Durable entity timer {} for keyspace '{}' key '{}' FIRED and is durable on this owner but"
                 + " missed its replication barrier: {} — the state change stands and is NOT retried;"
                 + " it survives a restart here and would be lost with this node",
                  due.token(),
                  keyspace,
                  due.key(),
                  cause.message());

        return unit();
    }

    /// Emitted ONLY once the consume record has landed, which is what makes it one line per timer rather
    /// than one per tick: the token is durably gone, so this timer cannot come back to be reported again.
    @Contract
    private void logFireFailed(EntityFold.DueTimer due, Cause cause) {
        LOG.error("Durable entity timer {} for keyspace '{}' key '{}' could not fire: {}"
                 + " — the timer is CONSUMED (durably cancelled) and will not be retried;"
                 + " re-schedule it once the command is fixed",
                  due.token(),
                  keyspace,
                  due.key(),
                  cause.message());
    }

    /// The consume record IS in the log and IS applied, so this is NOT the stuck case — it is reported at
    /// ERROR only because the timer's disappearance is not replicated, and that is the single path on which
    /// a consumed timer can still run its command: on a recovering owner that never received the cancel.
    @Contract
    private void logUnderReplicatedConsume(EntityFold.DueTimer due, Cause cause, Cause consumeCause) {
        LOG.error("Durable entity timer {} for keyspace '{}' key '{}' could not fire ({}) and its consume"
                 + " record is durable on this owner but missed its replication barrier: {} — the timer IS"
                 + " consumed here and will not be retried, but it survives a restart only on this node;"
                 + " if this node is lost, a recovering owner finds the timer still pending and fires it",
                  due.token(),
                  keyspace,
                  due.key(),
                  cause.message(),
                  consumeCause.message());
    }

    /// ERROR, and RATE-LIMITED rather than silenced, on the [#isWarnWorthy] precedent one level up. Nothing
    /// reached the log, so the timer stays pending and every tick retries it — at the one-second entity
    /// timer tick, an unrated line here is one ERROR per second per stuck timer, indefinitely.
    ///
    /// One-shot-then-silent is rejected for the same reason the readiness warning is rate-limited rather
    /// than suppressed: a fault that persists must stay VISIBLE. The attempt count travels IN the line, so
    /// an operator can tell a fault that started a second ago from one that has been stuck for an hour,
    /// which a repeated identical line cannot express.
    @Contract
    private void logRetriedConsume(EntityFold.DueTimer due, Cause cause, Cause consumeCause, long attempts) {
        if (isConsumeErrorWorthy(attempts)) {
            LOG.error("Durable entity timer {} for keyspace '{}' key '{}' could not fire ({}) AND its consume"
                     + " record has failed to append {} time(s) in a row: {} — NOTHING reached the log, so the"
                     + " timer stays PENDING and every tick retries it; an operator must clear the underlying"
                     + " append failure",
                      due.token(),
                      keyspace,
                      due.key(),
                      cause.message(),
                      attempts,
                      consumeCause.message());
        } else {
            LOG.debug("Durable entity timer {} for keyspace '{}' key '{}' consume record still not appended"
                     + " ({} consecutive failures): {}",
                      due.token(),
                      keyspace,
                      due.key(),
                      attempts,
                      consumeCause.message());
        }
    }

    /// Which consecutive consume-append failures get an ERROR rather than a DEBUG. The first always does —
    /// it may be the only one — and then every [#CONSUME_ERROR_EVERY_N_FAILURES]-th, which at the
    /// one-second timer tick is one line per minute per stuck timer instead of sixty.
    ///
    /// Package-private for the same reason [#isWarnWorthy] is: a rate limit that can only be observed as
    /// log volume cannot be asserted, and an un-asserted rate limit is how an ERROR quietly becomes either
    /// a flood or a silence.
    static boolean isConsumeErrorWorthy(long consecutiveFailures) {
        return consecutiveFailures == 1L || consecutiveFailures % CONSUME_ERROR_EVERY_N_FAILURES == 0L;
    }

    /// DEBUG, not ERROR, and not a defect. Everything that reaches here is ENVIRONMENTAL and self-clearing,
    /// arriving by one of two routes. The tick's promise FAILS — the admission refusal on a tick racing a
    /// handover, or the readiness refusal on a partition still folding — and [#submitFire]'s `onFailure`
    /// lands here. Or the fire's append was refused and [#settleAppendedFire] absorbed it, in which case
    /// [#deferredFire] calls this directly: a fence rejection or a quorum/transport fault, never
    /// [EntityLogError.ReplicationBarrierUnmet], which is louder and goes to [#underReplicatedFire].
    /// All of them leave the timer PENDING, so the node that does succeed fires it, and at one line per
    /// timer per tick an ERROR here would flood every replica's log with an outcome that is correct.
    ///
    /// A failed CONSUME append does not reach here — the timer stays pending there too, but the fault is
    /// worth an ERROR and gets [#logRetriedConsume].
    @Contract
    private void logFireDeferred(EntityFold.DueTimer due, Cause cause) {
        LOG.debug("Durable entity timer {} for keyspace '{}' key '{}' not fired here: {} — left pending",
                  due.token(),
                  keyspace,
                  due.key(),
                  cause.message());
    }

    /// Wire owner-forwarding (#596). Absent by default, and an entity with no forward wired refuses a
    /// non-owner locally rather than silently reaching a different node.
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

    /// The DELAY is carried as MILLIS and the owner stamps the due instant on arrival, so only the
    /// OWNER's clock is ever read. A sender-stamped instant would import cross-node clock skew into the
    /// one guarantee this API makes unconditionally — `scheduleTimer` promises at-or-after, never
    /// before, and a sender running ahead of the owner would fire a timer EARLY. Paying one network hop
    /// of extra delay keeps that promise intact; the hop is bounded by the same wire budget every other
    /// forwarded verb carries, and is far inside the one-tick lateness the contract already admits.
    ///
    /// The TOKEN travels with it — minted by [#scheduleTimer] before this decision was taken — so the
    /// owner applies the caller's identity for the timer rather than one of its own. That is what makes a
    /// re-sent forward the same schedule, and what leaves this caller holding a cancellable handle even
    /// when the answer never arrives.
    private Promise<TimerToken> forwardScheduleTimer(NodeId owner, K key, Duration delay, C onFire, TimerToken token) {
        return transport().flatMap(transport -> encodedPair(key,
                                                            () -> serializer.encode(onFire)).flatMap(payload -> transport.forwardScheduleTimer(owner,
                                                                                                                                               keyspace,
                                                                                                                                               payload.key(),
                                                                                                                                               delay.toMillis(),
                                                                                                                                               payload.body(),
                                                                                                                                               token.value())))
                        .mapError(cause -> retypeForwarded(cause, key))
                        .flatMap(applied -> verifiedEcho(key, token, applied));
    }

    /// The owner echoes the token it actually applied, and the echo is CHECKED rather than trusted.
    ///
    /// A mismatch can only mean the token's identity was lost — in the wire encoding, or in the owner's
    /// already-pending check — and both would hand this caller a handle naming a timer that is not the one
    /// the owner planted. Failing loudly is the point: the alternative is a silently uncancellable durable
    /// timer, which is the exact defect caller-side minting exists to remove, reintroduced one layer down.
    private Promise<TimerToken> verifiedEcho(K key, TimerToken sent, String applied) {
        return sent.value()
                   .equals(applied)
               ? Promise.success(sent)
               : new EntityError.TimerTokenMismatch(String.valueOf(key), sent, applied).promise();
    }

    private Promise<Unit> forwardCancelTimer(NodeId owner, K key, TimerToken token) {
        return transport().flatMap(transport -> encodedKey(key).flatMap(encoded -> transport.forwardCancelTimer(owner,
                                                                                                                keyspace,
                                                                                                                encoded,
                                                                                                                token.value())))
                        .mapError(cause -> retypeForwarded(cause, key));
    }

    /// The owner answers with EMPTY bytes by contract, so the result is discarded rather than decoded —
    /// decoding an empty payload as an `S` would fail on a delete that actually succeeded.
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

    /// The shared LOCAL shape behind every verb this node serves itself: admission decides, then the
    /// partition's fold gates, then the operation runs. Create, update, delete, schedule and cancel all
    /// spelled this body out inline, which meant a change to the gate ORDER — the thing that keeps a
    /// deposed owner from reaching the fold — could land on four of the five and read as correct.
    private <R> Promise<R> admittedLocally(K key, Supplier<Promise<R>> operation) {
        return admitWrite(key).fold(Cause::promise,
                                    _ -> ready(key).flatMap(_ -> operation.get()));
    }

    /// The shared receiving-side shape: this instance's own per-key queue and its own admission, so the
    /// hop neither bypasses the single-writer total order nor escapes the epoch fence. Identical to the
    /// local shape once inside the queue, and says so by calling it.
    private <R> Promise<R> admittedOnOwner(K key, Supplier<Promise<R>> operation) {
        return perKey.submit(String.valueOf(key), () -> admittedLocally(key, operation));
    }

    /// The schedule half (#345 I4). Runs [#scheduleAdmitted], so the key-must-exist check, the
    /// already-pending guard and the fenced append are the owner's own — a forwarded schedule cannot plant
    /// a timer a local schedule would have refused, and a re-sent one cannot plant a second timer.
    ///
    /// The token ARRIVES from the sender, which minted it at its own [#scheduleTimer] entry; this node
    /// applies that token and ECHOES it, so the sender can verify the identity survived the hop. Answering
    /// a token of this node's own minting is what made a lost response unrecoverable.
    ///
    /// The fire instant is stamped HERE from the arrived DELAY, by [#appendSchedule], rather than travelling
    /// as an absolute instant: the clock that mints it is then the clock that later finds it due, so the
    /// sender/owner skew never enters the timer. The hop's latency is added to the delay in exchange,
    /// bounded by the sender's forward timeout.
    ///
    /// `delayMillis` is a WIRE field, so it is VALIDATED here rather than trusted. A negative delay names
    /// an instant in the past, and [EntityFold#dueTimers] would find it due on the next tick — a caller
    /// asking for "one-shot, later" would get "one-shot, now", with no way to tell from the answer. The
    /// local entry point has no equivalent check because a negative [Duration] there is the caller's own
    /// argument, on its own thread, and reads as "already due"; across the wire the same value is
    /// indistinguishable from a corrupted or hostile field.
    @Override
    public Promise<String> scheduleTimerForwarded(byte[] encodedKey,
                                                  long delayMillis,
                                                  byte[] encodedOnFire,
                                                  String token) {
        return decoded(encodedKey).flatMap(key -> scheduleArrived(key, delayMillis, encodedOnFire, token))
                      .map(TimerToken::value);
    }

    private Promise<TimerToken> scheduleArrived(K key, long delayMillis, byte[] encodedOnFire, String token) {
        return delayMillis < 0L
               ? new EntityError.TimerDelayInvalid(String.valueOf(key), delayMillis).promise()
               : scheduleArrivedDelay(key, delayMillis, encodedOnFire, token);
    }

    private Promise<TimerToken> scheduleArrivedDelay(K key, long delayMillis, byte[] encodedOnFire, String token) {
        return decodedCommand(encodedOnFire).flatMap(onFire -> admittedOnOwner(key,
                                                                               () -> scheduleAdmitted(key,
                                                                                                      Duration.ofMillis(delayMillis),
                                                                                                      onFire,
                                                                                                      TimerToken.timerToken(token))));
    }

    /// The cancel half (#345 I4). Runs [#cancelAdmitted], so the owner's idempotence governs — a token that
    /// already fired, was already cancelled, or belongs to a deleted key is success with no record appended.
    ///
    /// Answers [Unit] rather than empty bytes: a cancel has no post-state, and the sender's own
    /// [EntityOwnerForward#forwardCancelTimer] already answers `Promise<Unit>` — one operation cannot have
    /// two answers to "there is no payload", or the byte-convention end drifts into meaning something.
    @Override
    public Promise<Unit> cancelTimerForwarded(byte[] encodedKey, String token) {
        return decoded(encodedKey).flatMap(key -> admittedOnOwner(key,
                                                                  () -> cancelAdmitted(key, TimerToken.timerToken(token))));
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

    /// Owner admission, ahead of the read-modify-write and ahead of the log's epoch fence: only the
    /// committed owner of the key's arc may mutate it. See [EntityOwnerAdmission].
    ///
    /// Takes `Object` rather than `K` for the same reason [#readState] and [#partitionOf] do: the timer
    /// tick recovers only the RENDERED key from the log, and every one of these paths has always
    /// identified a key by that rendering. Passing the rendered form is therefore not a widening of the
    /// check — it is the check's actual input, spelled honestly.
    private Result<Unit> admitWrite(Object key) {
        return admission.fold(Result::unitResult, gate -> gate.admit(key));
    }

    /// Read from the fold and decode.
    ///
    /// The decode is LIFTED, not called inline. [Deserializer] signals failure by throwing — by design,
    /// since a codec miss is a configuration fault — and a throw escaping here would propagate out of the
    /// [PerKeySerialExecutor] tail, leaving the operation's promise unresolved: the caller hangs until its
    /// own timeout and the key's serialization tail is wedged for good. A hang is a strictly worse failure
    /// than a typed one.
    private Promise<Option<S>> readState(Object key) {
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
    private Promise<Long> appendAndApply(Object key, EntityLogRecord record) {
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
    private Cause translateAppendFailure(Object key, int partition, EntityLogRecord record, Cause cause) {
        if (cause instanceof EntityLogError.ReplicationBarrierUnmet unmet) {
            fold.apply(partition, unmet.offset(), record);

            return cause;
        }

        return cause instanceof EntityLogError.StaleOwnerAppend stale
               ? new EntityError.StaleOwnerEpoch(String.valueOf(key), stale.detail())
               : new EntityError.StorageFailed(String.valueOf(key), cause);
    }

    private int partitionOf(Object key) {
        return arc.partitionOf(String.valueOf(key));
    }

    /// A codec fault for `key`'s state, rendered as a typed cause. [Serializer]/[Deserializer] report by
    /// throwing (their contract calls a codec miss a fatal misconfiguration), so this is the boundary
    /// where that exception becomes a value.
    private EntityError codecFailed(Object key, Throwable throwable) {
        return new EntityError.StorageFailed(String.valueOf(key), Causes.fromThrowable(throwable));
    }

    /// A timer's own command failing is NOT a storage fault, and calling it one would send an operator to
    /// the log tier for a bug in a slice's mutator. Its own variant also carries the TOKEN, which is the
    /// only thing that distinguishes one of a key's timers from another.
    private static EntityError timerFireFailed(EntityFold.DueTimer due, Cause cause) {
        return new EntityError.TimerFireFailed(due.key(),
                                               DurableEntity.TimerToken.timerToken(due.token()),
                                               cause);
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
