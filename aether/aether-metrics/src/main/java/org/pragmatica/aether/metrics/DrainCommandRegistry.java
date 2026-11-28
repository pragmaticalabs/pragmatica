// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Membership v2 (B5a) — leader-local registry of nodes the leader wants to command DRAIN.
///
/// Owned by the leader. The set of registered targets is snapshotted by the ping dispatch path
/// (`ClusterSyncContext.broadcastPing`) and stamped as the GLOBAL `ClusterSyncPing.drainNodes` set
/// on the single uniform broadcast ping; each receiver self-checks `drainNodes.contains(self)`. A
/// node is cleared once its drain is acknowledged / observed complete by the leader-side reconciler.
///
/// **Drain identity (#2315).** An entry is a [DrainPin]: the drain's [DrainId], the boot token of the target process and its
/// incarnation high-water mark, read from the bound [DrainSubject] when the drain is set. A node holds one entry per [Requester]:
/// the entry is identified by the life of the target process and by who asked, so the operator's drain and the topology manager's
/// drain of the same process are two entries with two ids, and neither one's clear ends the other. Every clear names the drain it
/// ends, and the registry refuses the ones that name another: [#clearDrain] by [DrainId], and the two edge clears (`clearEndedLife`, `clearPriorLives`) by the identity
/// the pin holds. A refused clear is a no-op; nothing here is a clock or a count. The invariant: an entry is cleared when, and only when,
/// the drain that set it is done or withdrawn, and a node that rejoins as a new process never inherits an earlier drain.
///
/// **One entry per command.** A drain whose attempt ends without ending its command (an eviction at its gate, a failed request) is
/// [#release]d: its pin moves from the live slot to the released slot of the same requester. A request joins only a pin of the live
/// slot, so a later request never takes a released command: it gets a new pin and a new id, and the released command ends only at the
/// node's DEAD edge (the two edge clears read both slots). The move and every request and clear run under the registry's monitor, so
/// there is no instant at which a request can join a pin that is being released; that monitor, and the type of the slot, are what
/// enforce it.
///
/// Thread-safe: every write holds the registry's monitor, in a private `synchronized` step behind the public method; the reads
/// (the periodic ping tick via [`#isDrainRequested`] / [`#drainTargets`]) take no lock and read a `ConcurrentHashMap`. A move writes the released slot before it removes the live one, so
/// a read sees the command throughout. Every clear removes the exact pin it read (`remove(node, pin)`). Requests are idempotent.
/// Lock order: the caller's monitors (the topology manager's, the membership FSM's per-member guard) come first, then this one, then
/// the bound [DrainSubject]'s reads (a per-member monitor of the FSM, a leaf). Nothing here calls out except those two reads.
public final class DrainCommandRegistry {
    /// Who asked for a drain. One entry per node and requester: a request from another requester never joins an entry, so a clear
    /// by the id of one never reaches the other.
    public enum Requester {
        OPERATOR,
        MANAGER
    }

    /// Whether the entry is a drain that is still its attempt's (`LIVE`, joined by a repeated request) or a command its attempt left
    /// to the DEAD edge (`RELEASED`, joined by nobody).
    private enum Standing {
        LIVE,
        RELEASED
    }

    private record Slot(NodeId node, Requester requester, Standing standing) {
        static Slot slot(NodeId node, Requester requester) {
            return new Slot(node, requester, Standing.LIVE);
        }

        Slot released() {
            return new Slot(node, requester, Standing.RELEASED);
        }

        static List<Slot> liveSlotsOf(NodeId node) {
            return List.of(slot(node, Requester.OPERATOR), slot(node, Requester.MANAGER));
        }

        static List<Slot> slotsOf(NodeId node) {
            return List.of(slot(node, Requester.OPERATOR),
                           slot(node, Requester.MANAGER),
                           slot(node, Requester.OPERATOR).released(),
                           slot(node, Requester.MANAGER).released());
        }
    }

    private final Map<Slot, DrainPin> pins = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<DrainSubject> subject = new AtomicReference<>(DrainSubject.unknown());

    private DrainCommandRegistry() {}

    /// Default registry. Empty until the leader registers drain targets.
    public static DrainCommandRegistry drainCommandRegistry() {
        return new DrainCommandRegistry();
    }

    /// Bind the identity facts the pins and the edge clears read. Before it is bound every pin carries process and incarnation `0`
    /// (the boot window), which no edge clear takes as evidence.
    @Contract
    public void bindSubject(DrainSubject facts) {
        subject.set(facts);
    }

    /// The operator's drain of `node`: [#requestDrain(NodeId, Requester)] as [Requester#OPERATOR].
    public Option<DrainId> requestDrain(NodeId node) {
        return requestDrain(node, Requester.OPERATOR);
    }

    /// Register `node` as a DRAIN target for `requester` and return the drain's id. A repeated request of the same requester while its
    /// drain is live is the same drain and returns the held id. A drain that was [#release]d is not live: the request after it is a new
    /// drain with a new id. Another requester's entry is never joined: it gets its own id. The pin records the node's boot token as
    /// the registry reads it, and a request never compares it: the production token moves only from unknown to known (`BootTokens`
    /// `putIfAbsent`), and a restarted process is a new life, which the edge clears end; a replace on a token change would guard only
    /// a token model that does not exist (#3858 F1).
    /// `null` is ignored (no id).
    public Option<DrainId> requestDrain(NodeId node, Requester requester) {
        return Option.option(node)
                     .map(id -> pinDrain(Slot.slot(id, requester)))
                     .map(DrainPin::id);
    }

    /// Clear the drain named by `id`. A no-op unless `id` is the id of one of the node's current entries: the completion of an
    /// earlier drain, or of another requester's drain, never removes a later one.
    @Contract
    public void clearDrain(DrainId id) {
        clearIf(id.node(),
                pin -> pin.id()
                          .equals(id));
    }

    /// The attempt that set the drain `id` is over and the command is not (an eviction at its gate, a failed request): the pin moves
    /// to the released slot, where no request joins it, and only the node's DEAD edge ends it. A no-op unless `id` is the id of one
    /// of the node's live entries.
    @Contract
    public void release(DrainId id) {
        releaseLive(id);
    }

    /// The life of `node` that reached incarnation `incarnation` ended (the edge into DEAD): clears the entry unless it was
    /// pinned at a later incarnation, which is a drain of a life this edge does not describe.
    @Contract
    public void clearEndedLife(NodeId node, long incarnation) {
        clearIf(node, pin -> pin.incarnation() <= incarnation);
    }

    /// `node` was re-admitted at incarnation `incarnation` (the edge out of DEAD): clears an entry pinned at an earlier
    /// incarnation, i.e. a drain commanded while the node was DEAD. A drain pinned at `incarnation` or later is the new life's.
    @Contract
    public void clearPriorLives(NodeId node, long incarnation) {
        clearIf(node, pin -> pin.incarnation() < incarnation);
    }

    /// The pin of `node`'s current entry, if any; the operator's when both requesters hold one.
    public Option<DrainPin> pinOf(NodeId node) {
        return Option.option(node).flatMap(this::firstPin);
    }

    /// Whether `node` is currently a DRAIN target. Read by the ping dispatch path.
    public boolean isDrainRequested(NodeId node) {
        return pinOf(node).isPresent();
    }

    /// Immutable snapshot of the current DRAIN target set.
    public Set<NodeId> drainTargets() {
        return pins.keySet()
                   .stream()
                   .map(Slot::node)
                   .collect(Collectors.toUnmodifiableSet());
    }

    private Option<DrainPin> firstPin(NodeId node) {
        return Option.from(Slot.slotsOf(node)
                               .stream()
                               .flatMap(slot -> Option.option(pins.get(slot)).stream())
                               .findFirst());
    }

    private synchronized DrainPin pinDrain(Slot slot) {
        return pins.computeIfAbsent(slot, key -> newPin(key.node()));
    }

    private DrainPin newPin(NodeId node) {
        return DrainPin.drainPin(DrainId.drainId(node, sequence.incrementAndGet()),
                                 subject.get().process().applyAsLong(node),
                                 subject.get().incarnation().applyAsLong(node));
    }

    private synchronized void releaseLive(DrainId id) {
        Slot.liveSlotsOf(id.node()).forEach(slot -> releaseSlot(slot, id));
    }

    private void releaseSlot(Slot slot, DrainId id) {
        Option.option(pins.get(slot)).filter(pin -> pin.id()
                                                       .equals(id)).onPresent(pin -> leaveToDeadEdge(slot, pin));
    }

    /// A pin already released by this requester is overwritten: both end at the same DEAD edge, and the edge clears judge a pin by
    /// its incarnation, which the node's high-water mark never lowers, so the newer pin outlives no edge the older one survives.
    private void leaveToDeadEdge(Slot slot, DrainPin pin) {
        pins.put(slot.released(), pin);
        pins.remove(slot, pin);
    }

    private synchronized void clearIf(NodeId node, Predicate<DrainPin> refersToEnded) {
        Slot.slotsOf(node).forEach(slot -> clearSlotIf(slot, refersToEnded));
    }

    private void clearSlotIf(Slot slot, Predicate<DrainPin> refersToEnded) {
        Option.option(pins.get(slot)).filter(refersToEnded).onPresent(pin -> pins.remove(slot, pin));
    }
}
