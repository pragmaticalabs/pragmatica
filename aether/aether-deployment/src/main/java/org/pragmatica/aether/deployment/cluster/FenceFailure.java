// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.lang.Cause;


/// Why [FencePort#fenced] did not run its effect. Every cause is typed, so a caller can tell a deposed leader from a slow commit.
public sealed interface FenceFailure extends Cause {
    /// No leader of this node is present at submit.
    record NotLeader() implements FenceFailure {
        public static NotLeader notLeader() {
            return new NotLeader();
        }

        @Override
        public String message() {
            return "Core leadership required before a fenced effect";
        }
    }

    /// The applier refused the witness transaction. `kind` is LEADER (the stored leader is not the one the transaction names) or
    /// WITNESS (a guard is unequal to the committed value); UNKNOWN when the result carried no refusal kind.
    record LeadershipNotWitnessed(RefusalKind kind) implements FenceFailure {
        public static LeadershipNotWitnessed leadershipNotWitnessed(RefusalKind kind) {
            return new LeadershipNotWitnessed(kind);
        }

        @Override
        public String message() {
            return "Fenced effect refused by the leader witness: " + kind;
        }
    }

    /// The witness was accepted, but more than `applyTimeout` passed on this node's monotonic clock since submit.
    record WitnessExpired() implements FenceFailure {
        public static WitnessExpired witnessExpired() {
            return new WitnessExpired();
        }

        @Override
        public String message() {
            return "Fenced effect witness expired before its continuation";
        }
    }

    /// The witness was accepted, but leadership was lost or lost and regained between submit and the continuation.
    record ActivationChanged() implements FenceFailure {
        public static ActivationChanged activationChanged() {
            return new ActivationChanged();
        }

        @Override
        public String message() {
            return "Core leadership changed during the fenced effect";
        }
    }

    /// A request's own gate answered `false`: its attempt was cancelled while the witness was in flight.
    record AttemptCancelled() implements FenceFailure {
        public static AttemptCancelled attemptCancelled() {
            return new AttemptCancelled();
        }

        @Override
        public String message() {
            return "The attempt was cancelled while its witness was in flight";
        }
    }
}
