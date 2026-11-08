// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// The recovery codes of the cluster FSM's standing warnings, as the owner ruled the D.6 table (spec 9.4, know 7cc9e79f61). Each recovery
/// is raised with the warning's own subject and closes it in one of two ways: the condition ended, or (reason [#SUBJECT_GONE]) the subject
/// left the FSM's model, so abandonment closes the warning as success does. A warning whose subject covers several instances (rows 15/16,
/// 18/19 and 20: the artifact) is closed only when the last of them leaves. A recovery whose opener leaves a leader-local mark is raised
/// only for a subject in this leader's open set; rows 18/19 are raised from the committed exclusions (`Judgments`).
///
/// Across a leader change (CTO ruling A2 on #2831): the recoveries of rows 6/7 and 19 close across nodes (`closesAcrossNodes`), because
/// their open condition is committed state (the inferred-departure record, the committed exclusions); a successor raises them from that
/// state and its event layer publishes them although it never saw the warning. The other families are leader-local: a successor raises
/// their warning again where it re-evaluates the condition and finds it holding, and then closes it itself.
///
/// Limit (#2834): the open sets of the leader-local families are not committed. A warning a deposed leader raised is never closed by its
/// successor when the condition ended before the successor saw it, or when the successor does not raise it again (a family raised only at
/// an event, such as an exhaustion or a join).
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
