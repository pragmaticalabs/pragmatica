// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Result.success;


/// A fencing epoch: `(incarnation, rabiaTerm, localCounter)`, ordered lexicographically.
///
/// `incarnation` is the cluster incarnation (#1529): minted at genesis, incremented by every restore
/// (#1533). It ranks first because a cold restart restarts the Rabia term, so without it every
/// epoch-bearing write of the new run would lose to the restored, numerically higher epochs of the
/// previous run. Within one incarnation the order is the pre-#1529 `(rabiaTerm, localCounter)`.
///
/// [limit: incarnation-advances-only-with-1533] This fixes a cold restart only once the restore advances the
/// incarnation (#1533, `ClusterIncarnation.restoring`). Until #1533 lands, `GitBackedPersistence`
/// restores the committed `ClusterIncarnationKey` as-is, the incarnation is unchanged across a cold
/// restart, and these epochs order exactly as they did before #1529 — never worse.
@Codec
public record Epoch(long incarnation, long rabiaTerm, long localCounter) implements Comparable<Epoch> {
    /// `ClusterIncarnation.NONE`: the incarnation before genesis, and the mirror's value until a commit is observed.
    public static final long NO_INCARNATION = 0L;
    public static final Epoch ZERO = new Epoch(0L, 0L, 0L);

    public static Epoch epoch(long incarnation, long rabiaTerm, long localCounter) {
        return new Epoch(incarnation, rabiaTerm, localCounter);
    }

    /// #2654: this epoch as the base of an ownership or lineage mint, refused with
    /// [EpochMintError.General#INCARNATION_NOT_COMMITTED] while its incarnation is `ClusterIncarnation.NONE` (0). Until
    /// the life's incarnation commits, a mint would rank below every epoch a restored life committed, so the writer
    /// writes nothing and retries on its next pass.
    public Result<Epoch> mintable() {
        return success(this).filter(EpochMintError.General.INCARNATION_NOT_COMMITTED, Epoch::incarnationCommitted);
    }

    private boolean incarnationCommitted() {
        return incarnation != NO_INCARNATION;
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
