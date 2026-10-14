// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The one observed write in flight for a key (spec R-3.4.9 (2)). `commands` is resubmitted unchanged on a retry, so a
/// retry is the same version-fenced Put (R-3.4.4); `cycle` counts FAILED-write cycles (N19). `probing` is the write probe's
/// state for the current attempt (spec O1, R-3.4.4).
public record InFlight(long correlation,
                       long version,
                       SliceObservedState state,
                       List<KVCommand<AetherKey>> commands,
                       int attempt,
                       Purpose purpose,
                       int cycle,
                       Probing probing) {
    /// The write probe's state for one attempt (spec R-3.4.4, O1).
    ///
    /// - `builders`: the local steps that built this batch's other commands (BUILD_LEGS, DESCRIBE_ACTIVE, PREPARE_TEARDOWN), in
    ///   order. A probe runs the same steps again, so its batch is built afresh from the current mirror and the batch built at
    ///   first submission is never replayed. Empty: the batch is the observed command alone.
    /// - `submittedAtMs`: when the attempt was submitted, for the sensor's elapsed time.
    /// - `fired`: how many probes of this attempt have fired.
    /// - `resubmissions`: the correlations of this attempt's probe resubmissions. Their settlements are metric-only: they never
    ///   advance the attempt counter and never start a retry.
    /// - `rebuild`: the rebuild a probe has under way, if any.
    public record Probing(List<LocalStep> builders,
                          long submittedAtMs,
                          int fired,
                          List<Long> resubmissions,
                          Option<Rebuild> rebuild) {
        public Probing {
            builders = List.copyOf(builders);
            resubmissions = List.copyOf(resubmissions);
        }

        public static Probing probing(List<LocalStep> builders) {
            return new Probing(builders, 0L, 0, List.of(), none());
        }

        public Probing submittedAt(long now) {
            return new Probing(builders, now, fired, resubmissions, rebuild);
        }

        public Probing probed() {
            return new Probing(builders, submittedAtMs, fired + 1, resubmissions, rebuild);
        }

        public Probing rebuilding(Rebuild next) {
            return new Probing(builders, submittedAtMs, fired, resubmissions, some(next));
        }

        public Probing notRebuilding() {
            return new Probing(builders, submittedAtMs, fired, resubmissions, none());
        }

        public Probing resubmitted(long correlation) {
            var all = new ArrayList<>(resubmissions);

            all.add(correlation);

            return new Probing(builders, submittedAtMs, fired, all, none());
        }

        public Probing restarted() {
            return new Probing(builders, 0L, 0, List.of(), none());
        }
    }

    /// A probe's rebuild under way: `token` is the running local step's token, `remaining` the steps after it, `collected` what the
    /// finished steps produced.
    public record Rebuild(long token, List<LocalStep> remaining, List<LegCommand> collected) {
        public Rebuild {
            remaining = List.copyOf(remaining);
            collected = List.copyOf(collected);
        }

        public static Rebuild rebuild(List<LocalStep> steps) {
            return new Rebuild(0L, steps, List.of());
        }

        public Rebuild collecting(List<LegCommand> commands) {
            var all = new ArrayList<>(collected);

            all.addAll(commands);

            return new Rebuild(token, remaining, all);
        }

        public Rebuild next(long newToken) {
            return new Rebuild(newToken,
                               remaining.subList(1, remaining.size()),
                               collected);
        }
    }

    /// Why the write was made. Decides what its confirmation or its exhaustion leads to.
    public enum Purpose {
        /// A step of the live chain.
        CHAIN,
        /// A FAILED row (N3, N5, N6, N14, N19).
        FAILED,
        /// The witnessed Remove of the node's own tombstone (R-1.6.3).
        TOMBSTONE_REMOVE
    }

    public InFlight {
        commands = List.copyOf(commands);
    }

    public static InFlight inFlight(long correlation,
                                    long version,
                                    SliceObservedState state,
                                    List<KVCommand<AetherKey>> commands,
                                    Purpose purpose,
                                    int cycle) {
        return inFlight(correlation, version, state, commands, purpose, cycle, List.of());
    }

    public static InFlight inFlight(long correlation,
                                    long version,
                                    SliceObservedState state,
                                    List<KVCommand<AetherKey>> commands,
                                    Purpose purpose,
                                    int cycle,
                                    List<LocalStep> builders) {
        return new InFlight(correlation, version, state, commands, 1, purpose, cycle, Probing.probing(builders));
    }

    public InFlight retried(long newCorrelation) {
        return new InFlight(newCorrelation, version, state, commands, attempt + 1, purpose, cycle, probing.restarted());
    }

    public InFlight nextCycle(long newCorrelation) {
        return new InFlight(newCorrelation, version, state, commands, 1, purpose, cycle + 1, probing.restarted());
    }

    public InFlight withProbing(Probing next) {
        return new InFlight(correlation, version, state, commands, attempt, purpose, cycle, next);
    }

    /// The observed command of the batch: the fenced Put, or the witnessed Remove of a tombstone. It is always first.
    public KVCommand<AetherKey> observedCommand() {
        return commands.getFirst();
    }
}
