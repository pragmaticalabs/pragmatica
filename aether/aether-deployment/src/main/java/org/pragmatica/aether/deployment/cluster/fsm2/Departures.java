// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.lang.Option;


/// Reads over the departure records (spec R-4.3.0) that the indexes, the join table and the backstop share.
public sealed interface Departures {
    /// **Ignore on read** (R-4.3.0): a committed `DEPARTED(e_d, p_d)` is ignored when `e_d < admissions[n].epoch` and
    /// `p_d` is not a different process from the current admission (R-2.11, C8). It names an older incarnation than the
    /// current admission. An ADMITTED record is never "ignored": it is never a departure.
    static boolean isIgnored(NodeDepartureValue record, Option<Admission> admission) {
        return record.state() == DepartureState.DEPARTED
               && admission.map(current -> record.epoch() < current.epoch() && !current.isDifferentProcessFrom(record.process()))
                           .or(false);
    }

    static boolean isDeparted(NodeDepartureValue record) {
        return record.state() == DepartureState.DEPARTED;
    }

    static boolean isAdmitted(NodeDepartureValue record) {
        return record.state() == DepartureState.ADMITTED;
    }

    record unused() implements Departures {}
}
