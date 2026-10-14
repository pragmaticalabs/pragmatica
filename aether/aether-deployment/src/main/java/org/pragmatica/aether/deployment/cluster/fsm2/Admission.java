// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;


/// What membership says about one node id (spec CI-15): the `process` it admitted. A process whose `bootToken` is
/// `0` is unknown (C8): the registry has recorded no token for it, the seam is absent, or it was not refreshed yet.
///
/// Membership supplies no epoch (MQ-4): the admission epoch is leader-assigned in the committed `NodeDepartureValue`
/// (R-4.3.0, `Departures#epochOf`).
public record Admission(ProcessIncarnation process) {
    public static final Admission UNKNOWN = new Admission(new ProcessIncarnation(0L, 0L));

    public static Admission admission(ProcessIncarnation process) {
        return new Admission(process);
    }

    public long token() {
        return process.bootToken();
    }

    public boolean tokenKnown() {
        return token() != 0L;
    }

    public Admission withProcess(ProcessIncarnation process) {
        return new Admission(process);
    }

    /// R-2.11 / C8: two admissions are a **different process** only on positive evidence: both tokens known and unequal.
    public boolean isDifferentProcessFrom(ProcessIncarnation other) {
        return tokenKnown()
               && other.bootToken() != 0L
               && other.bootToken() != token();
    }
}
