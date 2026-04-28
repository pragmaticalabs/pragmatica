// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.serialization.Codec;


@Codec public record Epoch(long rabiaTerm, long localCounter) implements Comparable<Epoch> {
    public static final Epoch ZERO = new Epoch(0L, 0L);

    public static Epoch epoch(long rabiaTerm, long localCounter) {
        return new Epoch(rabiaTerm, localCounter);
    }

    @Override public int compareTo(Epoch other) {
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
        return new Epoch(rabiaTerm, localCounter + 1);
    }

    public Epoch withTerm(long newRabiaTerm) {
        return new Epoch(newRabiaTerm, 0L);
    }

    @Override public String toString() {
        return rabiaTerm + ":" + localCounter;
    }
}
