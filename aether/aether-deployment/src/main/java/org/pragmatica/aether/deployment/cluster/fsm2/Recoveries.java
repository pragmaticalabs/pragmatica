// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// The recovery codes of the cluster FSM's standing warnings, as the owner ruled the D.6 table (spec 9.4, know 7cc9e79f61). Each recovery
/// is raised with the warning's own subject, only for a subject whose warning this leader raised, and closes it in one of two ways: the
/// condition ended, or (reason [#SUBJECT_GONE]) the subject left the FSM's model, so abandonment closes the warning as success does.
///
/// Limit: the open sets are leader-local, like every warned set of the model. A warning raised by a deposed leader is not closed by its
/// successor, and the event layer would not publish a recovery from a node that never published the warning (#752).
public sealed interface Recoveries {
    /// The reason text of a recovery raised because its subject left the FSM's model.
    String SUBJECT_GONE = "subject-gone";
    String DEPARTURE_INFERRED_REVERSED = "departure-inferred-reversed";
    String DEPARTURE_INFERRED_CONFIRMED = "departure-inferred-confirmed";
    String DEPARTURE_BREAKER_CLEARED = "departure-breaker-cleared";
    String PLACEMENT_VALID = "placement-valid";
    String JOIN_MATCHED = "join-matched";
    String RETRY_EXHAUSTED_SETTLED = "retry-exhausted-settled";
    String SCHEMA_HOLD_CLEARED = "schema-hold-cleared";
    String SCHEMA_STATUS_READABLE = "schema-status-readable";
    String SLICE_UNRESPONSIVE_EXPIRED = "slice-unresponsive-expired";
    String STRAY_INSTANCE_REMOVED = "stray-instance-removed";
    String WORKER_ABSENCE_JUDGEABLE = "worker-absence-judgeable";
    String WORKER_COMMUNITY_POLICY_MATCHED = "worker-community-policy-matched";

    /// The message of a recovery raised because `subject` left the model: the reason, then what removed it.
    static String gone(String subject, String why) {
        return subject + ": " + SUBJECT_GONE + " (" + why + ")";
    }

    record unused() implements Recoveries {}
}
