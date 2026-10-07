// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;


/// #1543 part E — the operator events of a replacement, derived from ONE committed transition (`before` → `after`), so the
/// flood guard is the commit itself: one event per transition, never one per tick. Each condition has its recovery
/// (`OperatorWarningCode#recoveryOf`): STARTED → COMPLETED / ROLLED_BACK, JOIN_OVERDUE → JOINED,
/// DRAIN_BLOCKED → DRAIN_UNBLOCKED, FAILED_KEPT_BOTH → SETTLED. The subject is the ORIGINAL node, stable for the whole
/// replacement, which is what pairs a recovery with its condition.
public final class NodeReplacementAnnouncements {
    private NodeReplacementAnnouncements() {}

    public record Announcement(OperatorWarningCode code, String subject, String message) {}

    public static List<Announcement> of(NodeId original,
                                        Option<NodeReplacementValue> before,
                                        NodeReplacementValue after) {
        var out = new ArrayList<Announcement>();
        var subject = original.id();
        var prior = before.map(NodeReplacementValue::phase).or(NodeReplacementPhase.UNKNOWN);

        if (before.isEmpty()) {
            out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_STARTED,
                                     subject,
                                     "Replacing node " + subject
                                    + " with " + after.replacement().id()
                                    + " (role " + after.role() + (after.targetVersion().isEmpty()
                                                                  ? ""
                                                                  : ", target version " + after.targetVersion())
                                    + ")"));

            return out;
        }

        closeConditions(out, subject, before.unwrap(), after);
        openConditions(out, subject, before.unwrap(), after);
        if (after.phase() != prior) {
            terminal(out, subject, prior, after);
        }

        return out;
    }

    private static void closeConditions(List<Announcement> out,
                                        String subject,
                                        NodeReplacementValue before,
                                        NodeReplacementValue after) {
        if (before.reason().equals(NodeReplacementPlanner.JOIN_OVERDUE) && after.phase() == NodeReplacementPhase.SWAPPING) {
            out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_JOINED,
                                     subject,
                                     "Replacement " + after.replacement().id() + " joined and is caught up"));
        }

        if (before.reason().startsWith(NodeReplacementPlanner.DRAIN_BLOCKED) && !after.reason()
                                                                                      .startsWith(NodeReplacementPlanner.DRAIN_BLOCKED) && after.phase() != NodeReplacementPhase.FAILED_KEPT_BOTH) {
            out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_DRAIN_UNBLOCKED,
                                     subject,
                                     "Draining " + subject + " is no longer blocked by the slice floor"));
        }

        if (before.phase() == NodeReplacementPhase.FAILED_KEPT_BOTH && after.phase() != NodeReplacementPhase.FAILED_KEPT_BOTH) {
            out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_SETTLED,
                                     subject,
                                     "The kept-both replacement of " + subject + " was settled: " + after.phase()));
        }
    }

    private static void openConditions(List<Announcement> out,
                                       String subject,
                                       NodeReplacementValue before,
                                       NodeReplacementValue after) {
        if (after.reason().equals(NodeReplacementPlanner.JOIN_OVERDUE) && !before.reason()
                                                                                 .equals(NodeReplacementPlanner.JOIN_OVERDUE)) {
            out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_JOIN_OVERDUE,
                                     subject,
                                     "Replacement " + after.replacement().id()
                                    + " of " + subject
                                    + " has not joined and caught up within half its join budget"));
        }

        if (after.reason().startsWith(NodeReplacementPlanner.DRAIN_BLOCKED) && !before.reason()
                                                                                      .startsWith(NodeReplacementPlanner.DRAIN_BLOCKED)) {
            out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_DRAIN_BLOCKED,
                                     subject,
                                     "Draining " + subject
                                    + " is blocked: " + after.reason()
                                                             .substring(NodeReplacementPlanner.DRAIN_BLOCKED.length())));
        }
    }

    private static void terminal(List<Announcement> out,
                                 String subject,
                                 NodeReplacementPhase prior,
                                 NodeReplacementValue after) {
        switch (after.phase()) {
            case DONE -> out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_COMPLETED,
                                                  subject,
                                                  "Replaced " + subject + " with " + after.replacement().id() + (after.reason().isEmpty()
                                                                                                                 ? ""
                                                                                                                 : " (" + after.reason() + ")")));
            case ROLLED_BACK -> out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_ROLLED_BACK,
                                                         subject,
                                                         "Replacement of " + subject + " by " + after.replacement().id() + " rolled back: " + after.reason()));
            case FAILED_KEPT_BOTH -> out.add(new Announcement(OperatorWarningCode.NODE_REPLACEMENT_FAILED_KEPT_BOTH,
                                                              subject,
                                                              "Replacement of " + subject + " by " + after.replacement().id() + " stopped, both nodes kept: " + after.reason()));
            case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING, UNKNOWN -> {}
        }
    }
}
