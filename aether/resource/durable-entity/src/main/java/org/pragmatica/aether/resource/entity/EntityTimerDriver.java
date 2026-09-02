// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Periodically fires every registered keyspace's due entity timers (#345 I4).
///
/// ## Why a driver and not a timer wheel per entity
/// A wheel would be process-local state describing something that is already durable: the pending set
/// lives in each partition's log and is folded from it. Keeping a second copy in a scheduler would mean
/// two things that can disagree — the classic shape where a handover leaves the wheel on the OLD owner and
/// the log on the new one, and no read can tell. So there is no wheel. This driver only asks each entity,
/// once per tick, "what is due now?", and the entity answers from its fold.
///
/// The cost of that choice is a tick-granularity floor on timer precision: a timer due at `t` fires at the
/// first tick at or after `t`, never before it. That is the trade — a coarser instant for a pending set
/// that survives handover and restart by construction.
///
/// ## Why it fails loudly rather than quietly
/// A driver that silently stops is invisible: writes keep working, reads keep working, and the only
/// symptom is timers that never fire — which reads, to the slice waiting on one, exactly like a timer
/// scheduled for later. Every failure path below therefore LOGS, and one keyspace's failure never stops
/// the others: the catch sits inside [#tickOne], per keyspace, so the iteration continues past it.
public final class EntityTimerDriver {
    private static final Logger LOG = LoggerFactory.getLogger(EntityTimerDriver.class);

    private final Map<String, PartitionFencedDurableEntity<?, ?, ?>> registrations = new ConcurrentHashMap<>();

    private EntityTimerDriver() {}

    public static EntityTimerDriver entityTimerDriver() {
        return new EntityTimerDriver();
    }

    /// Register a provisioned keyspace's entity for timer ticking. Idempotent per keyspace: a second
    /// registration of the same keyspace is ignored, so re-provisioning does not double the tick rate —
    /// which for timers would mean two ticks racing the same due set on every interval.
    ///
    /// The idempotence is EARNED by a single atomic [Map#putIfAbsent] rather than asserted over a
    /// check-then-add pair: each half of such a pair is atomic on its own, but two concurrent
    /// registrations of one keyspace can both pass the check before either adds, and the doubled tick
    /// rate that follows is exactly what this method claims to prevent.
    ///
    /// Package-private, honestly: the parameter type is package-private, so `public` here would have been
    /// decoration — no caller outside this package could name the argument. [DurableEntityFactory] is the
    /// only registrant and it lives here.
    @Contract
    void register(String keyspace, PartitionFencedDurableEntity<?, ?, ?> entity) {
        Option.option(registrations.putIfAbsent(keyspace, entity)).onEmpty(() -> LOG.info("Entity timers: keyspace '{}' registered",
                                                                                          keyspace));
    }

    /// Drop a keyspace's registration when its entity resource unloads. Idempotent: unregistering an
    /// unknown keyspace is a no-op. Without this, the tick keeps firing timers through an unloaded
    /// entity — an object whose slice classloader is gone — for the life of the node.
    ///
    /// Public where [#register] is not, and the asymmetry is the same rule applied twice: this takes a
    /// `String`, so it is genuinely callable from outside the package.
    @Contract
    public void unregister(String keyspace) {
        Option.option(registrations.remove(keyspace)).onPresent(_ -> LOG.info("Entity timers: keyspace '{}' unregistered",
                                                                              keyspace));
    }

    /// One tick at the current wall-clock instant — the entry a scheduler drives, mirroring
    /// [EntityCheckpointDriver#tick]. The instant is read ONCE and handed to every keyspace, so a slow tick
    /// cannot make a timer due for one keyspace and not-yet-due for the next.
    @Contract
    public void tick() {
        tick(System.currentTimeMillis());
    }

    /// One tick at an explicit instant — package-private, because a test seam is all it is. It exists so a
    /// test can pin the due/not-yet-due boundary, which no amount of sleeping demonstrates.
    @Contract
    void tick(long nowMillis) {
        registrations.forEach((keyspace, entity) -> tickOne(keyspace, entity, nowMillis));
    }

    /// One keyspace's tick, with the catch INSIDE it so the iteration survives it.
    ///
    /// The scheduler does NOT need protecting from a throw: [org.pragmatica.lang.utils.SharedScheduler]
    /// drives a `VirtualThreadScheduler`, which runs every body through `runGuarded` and re-enqueues the
    /// task unconditionally — a periodic entity tick is not cancelled by an escaping exception, and
    /// believing otherwise is what put this catch around the whole loop.
    ///
    /// What the catch actually buys is two things the alternative does not. First, ISOLATION: a
    /// deterministic fault in one keyspace's [PartitionFencedDurableEntity#fireDueTimers] would otherwise
    /// abandon every keyspace ordered after it, on every tick, forever — timers silently disabled for the
    /// tail of the map, with the symptom (timers that never fire) indistinguishable from timers scheduled
    /// for later. Second, ATTRIBUTION: the scheduler's own guard logs a generic "scheduled task body
    /// threw" that names neither this driver nor the keyspace, so an operator cannot tell which keyspace
    /// broke. This is an adapter-boundary lift, not business logic swallowing an error.
    @Contract
    private static void tickOne(String keyspace, PartitionFencedDurableEntity<?, ?, ?> entity, long nowMillis) {
        try {
            entity.fireDueTimers(nowMillis);
        } catch (RuntimeException e) {
            LOG.warn("Entity timer tick for keyspace '{}' failed: {} — every other keyspace still ticked;"
                    + " retried next tick",
                     keyspace,
                     e.toString(),
                     e);
        }
    }
}
