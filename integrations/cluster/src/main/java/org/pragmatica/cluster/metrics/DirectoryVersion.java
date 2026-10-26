// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.serialization.Codec;


/// The position of a [ClusterDirectory] in its total order: the leader epoch triple first, then the per-epoch generation, compared
/// lexicographically. A directory is installed iff its version is strictly after the version of the directory the receiver holds (I-W14), so a
/// new leader's lower generation under a newer epoch installs and a stale leader's higher generation under an older epoch does not.
/// [#NONE] is the version of a node that holds no directory.
@Codec
public record DirectoryVersion(long epochIncarnation, long epochTerm, long epochCounter, long generation) {
    public static final DirectoryVersion NONE = new DirectoryVersion(0L, 0L, 0L, 0L);

    public static DirectoryVersion directoryVersion(long epochIncarnation,
                                                    long epochTerm,
                                                    long epochCounter,
                                                    long generation) {
        return new DirectoryVersion(epochIncarnation, epochTerm, epochCounter, generation);
    }

    /// True when this version is strictly greater than `other`, comparing the epoch triple and then the generation.
    public boolean isStrictlyAfter(DirectoryVersion other) {
        return compare(this, other) > 0;
    }

    private static int compare(DirectoryVersion left, DirectoryVersion right) {
        var byIncarnation = Long.compare(left.epochIncarnation, right.epochIncarnation);

        if (byIncarnation != 0) {
            return byIncarnation;
        }

        var byTerm = Long.compare(left.epochTerm, right.epochTerm);

        if (byTerm != 0) {
            return byTerm;
        }

        var byCounter = Long.compare(left.epochCounter, right.epochCounter);

        return byCounter != 0
               ? byCounter
               : Long.compare(left.generation, right.generation);
    }
}
