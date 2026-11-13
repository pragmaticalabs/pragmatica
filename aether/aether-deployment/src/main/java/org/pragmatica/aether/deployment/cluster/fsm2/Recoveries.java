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
/// Across a leader change (CTO ruling A2 on #2831, and #2834): every D.6 recovery closes across nodes (`closesAcrossNodes`), because
/// every open condition is committed state. Rows 6/7 and 19 read theirs from the inferred-departure record and the committed exclusions;
/// the leader-local families commit their open set (`FsmWarningKey`, `Warnings`), and a successor inherits it at `Rebuild`. A successor
/// never raises an inherited open warning again, closes one whose subject left the model between the leaders with [#SUBJECT_GONE], and
/// closes the rest when its own level-triggered checks find the condition ended; its event layer publishes those recoveries although it
/// never saw the warning. Residual: a warning published in a drain whose leader was deposed before the drain's WARNINGS write committed
/// is not in the inherited set.
public sealed interface Recoveries {
    /// The reason text of a recovery raised because its subject left the FSM's model.
    String SUBJECT_GONE = "subject-gone";
    String DEPARTURE_INFERRED_REVERSED = "departure-inferred-reversed";
    String DEPARTURE_INFERRED_CONFIRMED = "departure-inferred-confirmed";
    String DEPARTURE_BREAKER_CLEARED = "departure-breaker-cleared";
    String PLACEMENT_VALID = "placement-valid";
    String JOIN_MATCHED = "join-matched";
    String APPLY_DEADLINE_CLEARED = "apply-deadline-cleared";
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
