// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Reads over the departure records (spec R-4.3.0) that the indexes, the join table and the backstop share.
public sealed interface Departures {
    /// `epochOf(n)` (R-4.3.0, MQ-4): the epoch of the committed record, or `0` with no record. The epoch is leader-assigned: only an
    /// admission write advances it, and a departure keeps the epoch of the admission it ends.
    static long epochOf(Option<NodeDepartureValue> record) {
        return record.map(NodeDepartureValue::epoch)
                     .or(0L);
    }

    /// **Superseded on read** (R-4.3.0, R-2.11): a committed `DEPARTED(e_d, p_d)` is superseded when `node` is **joined** (evidence this
    /// leader observed after its `Rebuild`) and `p_d` is not a different process from the node's current admission (R-2.11, C8). The
    /// `Rebuild` seed never supersedes: `joined` starts empty (D-4 (ii)). An ADMITTED record is never superseded: it is never a
    /// departure.
    static boolean isSuperseded(ClusterModel m, NodeId node, NodeDepartureValue record) {
        return record.state() == DepartureState.DEPARTED
               && m.joined.contains(node)
               && !m.admissions.get(node)
                               .or(Admission.UNKNOWN)
                               .isDifferentProcessFrom(record.process());
    }

    static boolean isDeparted(NodeDepartureValue record) {
        return record.state() == DepartureState.DEPARTED;
    }

    static boolean isAdmitted(NodeDepartureValue record) {
        return record.state() == DepartureState.ADMITTED;
    }

    record unused() implements Departures {}
}
