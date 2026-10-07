// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;


/// #1543: the committed replacement pairings (original → replacement), mirrored from KV so the reaper, the voter
/// selection and worker surplus selection read them without a KV snapshot per decision. With no pairing every
/// query is empty and those decisions are exactly what they were before pairing existed.
///
/// Phase semantics, in one place:
/// - a LIVE pairing (anything but `DONE` / `ROLLED_BACK`) protects its replacement from retirement, so a fresh
///   +1 node is never reaped as surplus;
/// - it also protects the original until `RETIRING_OLD`, so a rollback still has the original to fall back to;
/// - `SWAPPING` onwards (through `RETIRING_OLD`) authorizes the voter swap original → replacement;
/// - `UNKNOWN` (a phase from a newer peer) protects both and authorizes nothing.
public final class NodeReplacementIndex {
    private volatile Map<NodeId, NodeReplacementValue> pairings = Map.of();

    private NodeReplacementIndex() {}

    public static NodeReplacementIndex nodeReplacementIndex() {
        return new NodeReplacementIndex();
    }

    /// Told of every committed record as it is applied, with the record it replaced: the one place a transition is seen
    /// exactly as the cluster committed it, on every node.
    @FunctionalInterface
    public interface TransitionListener {
        Unit onTransition(NodeId original, Option<NodeReplacementValue> before, NodeReplacementValue after);
    }

    private final AtomicReference<TransitionListener> listener = new AtomicReference<>((_, _, _) -> Unit.unit());

    /// Replaces the transition listener. It runs outside the index's lock.
    public Unit onTransition(TransitionListener transitionListener) {
        listener.set(transitionListener);

        return Unit.unit();
    }

    public Unit put(NodeReplacementKey key, NodeReplacementValue value) {
        var before = swap(key, value);

        return listener.get()
                       .onTransition(key.original(),
                                     before,
                                     value);
    }

    private synchronized Option<NodeReplacementValue> swap(NodeReplacementKey key, NodeReplacementValue value) {
        var before = Option.option(pairings.get(key.original()));
        var updated = new HashMap<>(pairings);

        updated.put(key.original(), value);
        pairings = Map.copyOf(updated);

        return before;
    }

    public synchronized Unit remove(NodeReplacementKey key) {
        var updated = new HashMap<>(pairings);

        updated.remove(key.original());
        pairings = Map.copyOf(updated);

        return Unit.unit();
    }

    /// Replace the whole view from a KV snapshot (state restore): any entry that is not a pairing is ignored.
    public synchronized Unit restore(Map<?, ?> snapshot) {
        var updated = new HashMap<NodeId, NodeReplacementValue>();

        snapshot.forEach((key, value) -> collectPairing(updated, key, value));
        pairings = Map.copyOf(updated);

        return Unit.unit();
    }

    private static Unit collectPairing(Map<NodeId, NodeReplacementValue> target, Object key, Object value) {
        if (key instanceof NodeReplacementKey pairing && value instanceof NodeReplacementValue replacement) {
            target.put(pairing.original(), replacement);
        }

        return Unit.unit();
    }

    /// Nodes no surplus reaper may retire: every live pairing's replacement, and its original before `RETIRING_OLD`.
    public Set<NodeId> retirementProtected() {
        var current = pairings;
        var originals = originalsWhere(current, NodeReplacementIndex::protectsOriginal);
        var replacements = replacementsWhere(current, NodeReplacementIndex::isLive);

        return Stream.concat(originals.stream(),
                             replacements.stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// seat-leaver → seat-taker for every pairing whose phase authorizes a voter swap: original → replacement going
    /// forward, and the REVERSE (replacement → original) for a pairing being `REVERTING` after a failed canary.
    public Map<NodeId, NodeId> voterSwaps() {
        var current = pairings;
        var forward = originalsWhere(current, NodeReplacementIndex::authorizesSwap).stream()
                                    .collect(Collectors.toMap(Function.identity(),
                                                              original -> current.get(original)
                                                                                 .replacement()));

        originalsWhere(current, NodeReplacementPhase.REVERTING::equals).forEach(original -> forward.put(current.get(original)
                                                                                                               .replacement(),
                                                                                                        original));

        return Map.copyOf(forward);
    }

    /// The committed record for `original`, if any.
    public Option<NodeReplacementValue> recordFor(NodeId original) {
        return Option.option(pairings.get(original));
    }

    /// Every committed pairing, original → record.
    public Map<NodeId, NodeReplacementValue> all() {
        return pairings;
    }

    /// Replacements that are surge capacity, not surplus: a live pairing's replacement before `RETIRING_OLD`.
    public Set<NodeId> surgeReplacements() {
        return replacementsWhere(pairings, NodeReplacementIndex::protectsOriginal);
    }

    /// Originals whose replacement has taken over and that are now due for retirement.
    public Set<NodeId> retiringOriginals() {
        return originalsWhere(pairings, NodeReplacementPhase.RETIRING_OLD::equals);
    }

    private static Set<NodeId> originalsWhere(Map<NodeId, NodeReplacementValue> pairings,
                                              Predicate<NodeReplacementPhase> phase) {
        return pairings.entrySet()
                       .stream()
                       .filter(entry -> phase.test(entry.getValue().phase()))
                       .map(Map.Entry::getKey)
                       .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<NodeId> replacementsWhere(Map<NodeId, NodeReplacementValue> pairings,
                                                 Predicate<NodeReplacementPhase> phase) {
        return pairings.values()
                       .stream()
                       .filter(value -> phase.test(value.phase()))
                       .map(NodeReplacementValue::replacement)
                       .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isLive(NodeReplacementPhase phase) {
        return switch (phase) {
            case DONE, ROLLED_BACK -> false;
            case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING, FAILED_KEPT_BOTH, UNKNOWN -> true;
        };
    }

    /// Live and not yet `RETIRING_OLD`: the original is still needed, and the replacement is still surge.
    private static boolean protectsOriginal(NodeReplacementPhase phase) {
        return isLive(phase) && phase != NodeReplacementPhase.RETIRING_OLD;
    }

    private static boolean authorizesSwap(NodeReplacementPhase phase) {
        return switch (phase) {
            case SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD -> true;
            case PROVISIONING, JOINING, REVERTING, DONE, ROLLED_BACK, FAILED_KEPT_BOTH, UNKNOWN -> false;
        };
    }
}
