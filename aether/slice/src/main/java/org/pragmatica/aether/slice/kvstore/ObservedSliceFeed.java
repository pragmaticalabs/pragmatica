// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// The committed `SliceObservedKey` rows, as the readers outside the two deployment FSMs see them under FSM2 (deployment state machine spec
/// 1.6.1, R-1.6.2, R-1.6.6, C8). It turns the KV notifications of the observed rows into what its [ObservedSliceListener]s act on:
///
/// - a row is **shown** while it is current and is not the UNLOADED tombstone. Current is decided with `tokenOf` (the reader's own process
///   registry) **at delivery**: stale only when both boot tokens are known and differ, so an unknown token shows the row (C8);
/// - a **traffic** feed ([#trafficFeed], R-1.6.6) also hides every row of a node whose committed departure record is DEPARTED, and shows
///   them again when the record is superseded (rewritten ADMITTED) or removed;
/// - a listener is told a row is gone only if it was shown, so a removal it never saw a put for, or a second removal, reaches nobody.
///
/// Every `on*` method is a KV notification handler: the store delivers them one at a time, in commit order.
public final class ObservedSliceFeed {
    private final ToLongFunction<NodeId> tokenOf;
    private final boolean excludesDeparted;
    private final List<ObservedSliceListener> listeners;
    private final Map<SliceObservedKey, SliceObservedValue> rows = new ConcurrentHashMap<>();
    private final Set<SliceObservedKey> shown = ConcurrentHashMap.newKeySet();
    private final Set<NodeId> departed = ConcurrentHashMap.newKeySet();

    private ObservedSliceFeed(ToLongFunction<NodeId> tokenOf,
                              boolean excludesDeparted,
                              List<ObservedSliceListener> listeners) {
        this.tokenOf = tokenOf;
        this.excludesDeparted = excludesDeparted;
        this.listeners = List.copyOf(listeners);
    }

    /// A feed for readers that track slice state (counts, maps, events): current, non-tombstone rows.
    public static ObservedSliceFeed stateFeed(ToLongFunction<NodeId> tokenOf, List<ObservedSliceListener> listeners) {
        return new ObservedSliceFeed(tokenOf, false, listeners);
    }

    /// A feed for traffic readers (R-1.6.6): as [#stateFeed], and no row of a node with a committed DEPARTED record.
    public static ObservedSliceFeed trafficFeed(ToLongFunction<NodeId> tokenOf, List<ObservedSliceListener> listeners) {
        return new ObservedSliceFeed(tokenOf, true, listeners);
    }

    /// Whether this feed hides the rows of departed nodes (a [#trafficFeed]).
    public boolean excludesDeparted() {
        return excludesDeparted;
    }

    @Contract
    public void onObservedPut(ValuePut<SliceObservedKey, SliceObservedValue> put) {
        var key = put.cause().key();
        var row = put.cause().value();

        rows.put(key, row);
        reclassify(key, row, true);
    }

    @Contract
    public void onObservedRemove(ValueRemove<SliceObservedKey, SliceObservedValue> remove) {
        var key = remove.cause().key();

        rows.remove(key);
        hide(key);
    }

    /// A departure record was committed: DEPARTED hides the node's rows on a traffic feed; ADMITTED (the record superseded) shows them again.
    @Contract
    public void onDeparturePut(ValuePut<NodeDepartureKey, NodeDepartureValue> put) {
        var node = put.cause().key().nodeId();

        if (put.cause().value().state() == DepartureState.DEPARTED) {
            departed.add(node);
        } else {
            departed.remove(node);
        }

        reclassifyNode(node);
    }

    /// A departure record was removed: the node is no longer recorded as departed.
    @Contract
    public void onDepartureRemove(ValueRemove<NodeDepartureKey, NodeDepartureValue> remove) {
        var node = remove.cause().key().nodeId();

        departed.remove(node);
        reclassifyNode(node);
    }

    private void reclassifyNode(NodeId node) {
        rows.entrySet()
            .stream()
            .filter(entry -> entry.getKey()
                                  .nodeId()
                                  .equals(node))
            .toList()
            .forEach(entry -> reclassify(entry.getKey(),
                                         entry.getValue(),
                                         false));
    }

    /// Shows or hides `key`. A fresh write of a shown row is handed on again (`written`); a re-classification of an unchanged row is handed
    /// on only when its visibility changes.
    private void reclassify(SliceObservedKey key, SliceObservedValue row, boolean written) {
        if (isShown(key, row)) {
            show(key, row, written);
        } else {
            hide(key);
        }
    }

    private void show(SliceObservedKey key, SliceObservedValue row, boolean written) {
        if (shown.add(key) || written) {
            listeners.forEach(listener -> listener.onObservedSlice(key, row));
        }
    }

    private void hide(SliceObservedKey key) {
        if (shown.remove(key)) {
            listeners.forEach(listener -> listener.onObservedSliceGone(key));
        }
    }

    private boolean isShown(SliceObservedKey key, SliceObservedValue row) {
        var node = key.nodeId();

        return ObservedSlices.currentState(row,
                                           tokenOf.applyAsLong(node))
                             .isPresent()
               && !(excludesDeparted && departed.contains(node));
    }
}
