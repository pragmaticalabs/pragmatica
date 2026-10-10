// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// #1543 part F — the operator-facing entry points of the rolling-upgrade run. The run itself is advanced by
/// [UpgradeRunReconciler]; these only create it and record an operator's request, always by compare-and-set on the leader.
public interface UpgradeRunService {
    /// Start a run that replaces every node, one at a time, by a node running `targetVersion`. Refused while a run is live.
    Promise<UpgradeRunValue> start(String targetVersion);
    /// The committed run, if there ever was one.
    Option<UpgradeRunValue> status();
    /// Stop after the replacement in flight reaches a terminal state (immediately when none is in flight). Resumable.
    Promise<UpgradeRunValue> pause();
    /// Continue a paused run (a node whose replacement was rolled back is tried again).
    Promise<UpgradeRunValue> resume();
    /// End the run after the replacement in flight reaches a terminal state (immediately when none is in flight). Not resumable.
    Promise<UpgradeRunValue> abort();

    static UpgradeRunService unavailable() {
        return new UpgradeRunService() {
            @Override
            public Promise<UpgradeRunValue> start(String targetVersion) {
                return new Refusal.Unavailable().promise();
            }

            @Override
            public Option<UpgradeRunValue> status() {
                return Option.none();
            }

            @Override
            public Promise<UpgradeRunValue> pause() {
                return new Refusal.Unavailable().promise();
            }

            @Override
            public Promise<UpgradeRunValue> resume() {
                return new Refusal.Unavailable().promise();
            }

            @Override
            public Promise<UpgradeRunValue> abort() {
                return new Refusal.Unavailable().promise();
            }
        };
    }

    sealed interface Refusal extends Cause {
        record NotLeader() implements Refusal {
            @Override
            public String message() {
                return "Only the leader starts or changes an upgrade run";
            }
        }

        record Unavailable() implements Refusal {
            @Override
            public String message() {
                return "Rolling upgrade is not available on this node";
            }
        }

        record AlreadyRunning(String targetVersion) implements Refusal {
            @Override
            public String message() {
                return "An upgrade run to " + targetVersion
                     + " is already live; pause/resume/abort it, or wait for it to end";
            }
        }

        record NoRun() implements Refusal {
            @Override
            public String message() {
                return "There is no upgrade run";
            }
        }

        record NotApplicable(String state, String operation) implements Refusal {
            @Override
            public String message() {
                return "Cannot " + operation + " a run that is " + state;
            }
        }

        record NothingToReplace(String targetVersion) implements Refusal {
            @Override
            public String message() {
                return "Every replaceable node already reports " + targetVersion + "; there is nothing to upgrade";
            }
        }

        record Conflict() implements Refusal {
            @Override
            public String message() {
                return "The upgrade run changed concurrently; retry";
            }
        }
    }
}
