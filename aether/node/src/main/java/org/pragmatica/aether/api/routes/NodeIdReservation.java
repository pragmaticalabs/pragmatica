// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdHighWaterValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// #1715: the arithmetic of `cluster apply`'s node-id reservation. An id is `<source>-<role>-<n>`, and `n` comes from ONE place: the
/// committed high-water of the (source, role), never from the live roster, because a roster-derived `max + 1` hands a departed node's id
/// back (#1467, #1543). Pure: the route reads and commits, this decides.
final class NodeIdReservation {
    /// The ordinals handed out by one reservation and the high-water that must be committed, in the same transaction, before they are used.
    record Reservation(NodeIdHighWaterValue next, List<String> ids) {
        Reservation {
            ids = List.copyOf(ids);
        }
    }

    private NodeIdReservation() {}

    /// `source-role-n`: the id a reservation hands out.
    static String nodeId(String source, String role, int ordinal) {
        return source + "-" + role + "-" + ordinal;
    }

    /// The ordinal of `id` when it is `<source>-<role>-<n>` for exactly this source and role, else none. The prefix is known, so a source
    /// or role that itself contains dashes cannot be confused with a neighbour (`a-b` + `core` vs `a` + `b-core`).
    static Option<Integer> ordinalOf(String id, String source, String role) {
        var prefix = source + "-" + role + "-";

        return id.startsWith(prefix)
               ? digits(id.substring(prefix.length()))
               : none();
    }

    private static Option<Integer> digits(String text) {
        return !text.isEmpty() && text.length() <= 9 && text.chars()
                                                            .allMatch(Character::isDigit)
               ? some(Integer.parseInt(text))
               : none();
    }

    /// The high-water of a (source, role) that has no committed one yet: the highest ordinal already taken. Bootstrap named its nodes
    /// `<source>-<role>-<i>` for `i < configuredCount`, and every node id ever admitted is kept in the identity records, so the larger of
    /// the two covers a scaled-down role. `-1` means nothing was ever taken.
    static int seedHighWater(int configuredCount, String source, String role, Stream<String> knownNodeIds) {
        var highestKnown = knownNodeIds.flatMap(id -> ordinalOf(id, source, role).stream())
                                       .mapToInt(Integer::intValue)
                                       .max()
                                       .orElse(- 1);

        return Math.max(configuredCount - 1, highestKnown);
    }

    /// `count` ordinals above `highWater`, and the high-water to commit for them.
    static Reservation reserve(int highWater, String source, String role, int count) {
        var ids = IntStream.rangeClosed(highWater + 1, highWater + count)
                           .mapToObj(ordinal -> nodeId(source, role, ordinal))
                           .toList();

        return new Reservation(new NodeIdHighWaterValue(highWater + count), ids);
    }
}
