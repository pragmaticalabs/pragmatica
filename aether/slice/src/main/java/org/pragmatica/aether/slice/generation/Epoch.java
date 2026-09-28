// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.serialization.Codec;


/// A fencing epoch: `(incarnation, rabiaTerm, localCounter)`, ordered lexicographically.
///
/// `incarnation` is the cluster incarnation (#1529): minted at genesis, incremented by every restore
/// (#1533). It ranks first because a cold restart restarts the Rabia term, so without it every
/// epoch-bearing write of the new run would lose to the restored, numerically higher epochs of the
/// previous run. Within one incarnation the order is the pre-#1529 `(rabiaTerm, localCounter)`.
@Codec
public record Epoch(long incarnation, long rabiaTerm, long localCounter) implements Comparable<Epoch> {
    public static final Epoch ZERO = new Epoch(0L, 0L, 0L);

    public static Epoch epoch(long incarnation, long rabiaTerm, long localCounter) {
        return new Epoch(incarnation, rabiaTerm, localCounter);
    }

    @Override
    public int compareTo(Epoch other) {
        var byIncarnation = Long.compare(incarnation, other.incarnation);

        if (byIncarnation != 0) {
            return byIncarnation;
        }

        var byTerm = Long.compare(rabiaTerm, other.rabiaTerm);

        return byTerm != 0
               ? byTerm
               : Long.compare(localCounter, other.localCounter);
    }

    public boolean isAtLeast(Epoch other) {
        return compareTo(other) >= 0;
    }

    public boolean isStrictlyAfter(Epoch other) {
        return compareTo(other) > 0;
    }

    public Epoch nextCounter() {
        return new Epoch(incarnation, rabiaTerm, localCounter + 1);
    }

    public Epoch withTerm(long newRabiaTerm) {
        return new Epoch(incarnation, newRabiaTerm, 0L);
    }

    /// Same incarnation and term, with `newLocalCounter` as the local counter.
    public Epoch withCounter(long newLocalCounter) {
        return new Epoch(incarnation, rabiaTerm, newLocalCounter);
    }

    @Override
    public String toString() {
        return incarnation + ":" + rabiaTerm + ":" + localCounter;
    }
}
