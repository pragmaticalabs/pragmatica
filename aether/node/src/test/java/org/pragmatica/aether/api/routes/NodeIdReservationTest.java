// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.stream.Stream;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class NodeIdReservationTest {
    @Test
    void ordinalOf_matchingId_parses() {
        assertThat(NodeIdReservation.ordinalOf("eu-core-12", "eu", "core")).isEqualTo(Option.some(12));
    }

    @Test
    void ordinalOf_otherSourceOrRole_isNone() {
        assertThat(NodeIdReservation.ordinalOf("eu-core-12", "us", "core")).isEqualTo(Option.none());
        assertThat(NodeIdReservation.ordinalOf("eu-core-12", "eu", "worker")).isEqualTo(Option.none());
    }

    @Test
    void ordinalOf_prefixOccurringOnlyInsideTheId_isNone() {
        assertThat(NodeIdReservation.ordinalOf("x-eu-core-3", "eu", "core")).isEqualTo(Option.none());
    }

    @Test
    void ordinalOf_dashedSourceDoesNotCaptureANeighbour() {
        assertThat(NodeIdReservation.ordinalOf("a-b-core-1", "a", "b-core")).isEqualTo(Option.some(1));
        assertThat(NodeIdReservation.ordinalOf("a-b-core-1", "a-b", "core")).isEqualTo(Option.some(1));
        assertThat(NodeIdReservation.ordinalOf("a-b-core-1", "a", "core")).isEqualTo(Option.none());
    }

    @Test
    void ordinalOf_nonNumericOrEmptyOrOversizedSuffix_isNone() {
        assertThat(NodeIdReservation.ordinalOf("eu-core-", "eu", "core")).isEqualTo(Option.none());
        assertThat(NodeIdReservation.ordinalOf("eu-core-x1", "eu", "core")).isEqualTo(Option.none());
        assertThat(NodeIdReservation.ordinalOf("eu-core-01ARZ3NDEKTSV4RRFFQ69G5FAV", "eu", "core")).isEqualTo(Option.none());
        assertThat(NodeIdReservation.ordinalOf("eu-core-99999999999", "eu", "core")).isEqualTo(Option.none());
    }

    @Test
    void reserve_takesTheOrdinalsAboveTheHighWater() {
        var reservation = NodeIdReservation.reserve(4, "eu", "core", 3);

        assertThat(reservation.ids()).containsExactly("eu-core-5", "eu-core-6", "eu-core-7");
        assertThat(reservation.next().highWater()).isEqualTo(7);
    }

    @Test
    void reserve_nothingTakenYet_startsAtZero() {
        assertThat(NodeIdReservation.reserve(- 1, "eu", "core", 1).ids()).containsExactly("eu-core-0");
    }

    @Test
    void seedHighWater_isTheLargerOfConfiguredCountAndHighestKnownOrdinal() {
        assertThat(NodeIdReservation.seedHighWater(3, "eu", "core", Stream.empty())).isEqualTo(2);
        assertThat(NodeIdReservation.seedHighWater(1, "eu", "core", Stream.of("eu-core-0", "eu-core-6", "us-core-9", "aether-node-X"))).isEqualTo(6);
        assertThat(NodeIdReservation.seedHighWater(0, "eu", "core", Stream.empty())).isEqualTo(- 1);
    }
}
