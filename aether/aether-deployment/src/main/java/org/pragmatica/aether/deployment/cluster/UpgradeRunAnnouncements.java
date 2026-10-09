// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;


/// #1543 part F — the operator events of the upgrade run, derived from ONE committed transition (`before` → `after`), so the flood
/// guard is the commit itself: one event per transition, never one per tick. Each condition has its recovery:
/// STARTED → COMPLETED / ABORTED, PAUSED → RESUMED / PAUSE_ENDED. The subject is always `upgrade` (there is one run), which is what
/// pairs a recovery with its condition.
public final class UpgradeRunAnnouncements {
    public static final String SUBJECT = "upgrade";

    private UpgradeRunAnnouncements() {}

    public record Announcement(OperatorWarningCode code, String subject, String message) {}

    public static List<Announcement> of(Option<UpgradeRunValue> before, UpgradeRunValue after) {
        var out = new ArrayList<Announcement>();
        var prior = before.map(UpgradeRunValue::state).or(UpgradeRunState.UNKNOWN);

        if (before.isEmpty() || (!before.unwrap().live() && after.live() && before.unwrap().startedAtMs() != after.startedAtMs())) {
            out.add(new Announcement(OperatorWarningCode.UPGRADE_STARTED,
                                     SUBJECT,
                                     "Rolling upgrade to " + after.targetVersion()
                                    + " started: " + after.order().size()
                                    + " node(s) will be replaced one at a time"));

            return out;
        }

        if (after.state() == prior) {
            return out;
        }

        switch (after.state()) {
            case PAUSED -> out.add(new Announcement(OperatorWarningCode.UPGRADE_PAUSED,
                                                    SUBJECT,
                                                    "Rolling upgrade to " + after.targetVersion() + " paused: " + after.reason()));
            case RUNNING -> {
                if (prior == UpgradeRunState.PAUSED) {
                    out.add(new Announcement(OperatorWarningCode.UPGRADE_RESUMED,
                                             SUBJECT,
                                             "Rolling upgrade to " + after.targetVersion() + " resumed"));
                }
            }
            case COMPLETED -> out.add(new Announcement(OperatorWarningCode.UPGRADE_COMPLETED,
                                                       SUBJECT,
                                                       "Rolling upgrade to " + after.targetVersion() + " completed: every node reports it"));
            case ABORTED -> {
                if (prior == UpgradeRunState.PAUSED) {
                    out.add(new Announcement(OperatorWarningCode.UPGRADE_PAUSE_ENDED,
                                             SUBJECT,
                                             "The paused rolling upgrade to " + after.targetVersion() + " was aborted"));
                }

                out.add(new Announcement(OperatorWarningCode.UPGRADE_ABORTED,
                                         SUBJECT,
                                         "Rolling upgrade to " + after.targetVersion() + " aborted: " + after.reason() + "; the cluster runs the versions it has now, no replacement is left half-done"));
            }
            case UNKNOWN -> {}
        }

        return out;
    }
}
