// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;


/// What membership says about one node id (spec CI-15): the admission `epoch` (membership's incarnation
/// high-water mark) and the `process` that epoch belongs to. A process whose `bootToken` is `0` is unknown
/// (C8): the registry has recorded no token for it, the seam is absent, or it was not refreshed yet.
public record Admission(long epoch, ProcessIncarnation process) {
    public static final Admission UNKNOWN = new Admission(0L, new ProcessIncarnation(0L, 0L));

    public static Admission admission(long epoch, ProcessIncarnation process) {
        return new Admission(epoch, process);
    }

    public long token() {
        return process.bootToken();
    }

    public boolean tokenKnown() {
        return token() != 0L;
    }

    public Admission withProcess(ProcessIncarnation process) {
        return new Admission(epoch, process);
    }

    /// R-2.11 / C8: two admissions are a **different process** only on positive evidence: both tokens known and unequal.
    public boolean isDifferentProcessFrom(ProcessIncarnation other) {
        return tokenKnown()
               && other.bootToken() != 0L
               && other.bootToken() != token();
    }
}
