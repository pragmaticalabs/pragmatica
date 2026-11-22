// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.function.Supplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The role a read is made in, which decides how a ring answers a read below its tail. [#COPY] is every reader that is not the
/// partition's owner: it is told [StreamError.BelowCopyTail] below the first offset this copy held. The owner value is bound to
/// one ring (its stream, its partition and the ring's incarnation) and is told what the record proves, `LIFE_LOST`.
///
/// The owner value is never handed out: [#readingAs] is a LOAN. It runs the admission, then runs `use` synchronously with a
/// value that exists only for that call, and returns what `use` returned, so no caller holds one past the admission's check.
/// This file holds the only two constructor calls in the module's main sources. The owner gate M-2 wires into the admission
/// is its own lane.
public final class ReadAs {
    /// Every reader that is not the owner.
    public static final ReadAs COPY = new ReadAs("", -1, 0L);

    private final String streamName;
    private final int partition;
    private final long incarnation;

    private ReadAs(String streamName, int partition, long incarnation) {
        this.streamName = streamName;
        this.partition = partition;
        this.incarnation = incarnation;
    }

    /// Run `use` with the owner value of the ring `(streamName, partition, incarnation)` once `admission` succeeded.
    public static <T> Result<T> readingAs(String streamName,
                                          int partition,
                                          long incarnation,
                                          Supplier<Result<Unit>> admission,
                                          Fn1<Result<T>, ReadAs> use) {
        return admission.get()
                        .flatMap(_ -> use.apply(new ReadAs(streamName, partition, incarnation)));
    }

    boolean isOwner() {
        return this != COPY;
    }

    boolean isBoundTo(String ringStream, int ringPartition, long ringIncarnation) {
        return partition == ringPartition
               && incarnation == ringIncarnation
               && streamName.equals(ringStream);
    }
}
