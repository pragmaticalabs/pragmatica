// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.Set;

import org.pragmatica.lang.Option;


/// Which of `--resume` and `--rollback` may run on a ledger, a pure function of the ledger's phase and of what the
/// cluster says about its committed config (cluster-apply spec 4.6). The point is that a crash anywhere in the commit
/// leaves EXACTLY ONE verb usable once the cluster can be read, and never zero (a wedge: VMs billed, both verbs
/// refused) or two (a rollback that terminates nodes belonging to a config the cluster is about to hold).
@SuppressWarnings("JBCT-UTIL-02")
final class ApplyCommitRecovery {
    private ApplyCommitRecovery() {}

    enum Verb {
        RESUME,
        ROLLBACK
    }

    /// What the cluster holds: the hash of its stored desired config and its config version.
    record ClusterConfigView(String configHash, long configVersion) {}

    /// `usable` is the set of verbs allowed; `phase` is the phase the ledger must be moved to before anything runs
    /// (the caller writes it when it differs); `explanation` is what an operator is told for a refused verb.
    record Decision(Set<Verb> usable, ApplyPhase phase, String explanation) {
        boolean allows(Verb verb) {
            return usable.contains(verb);
        }
    }

    /// `view` is empty when the cluster could not be read (unreachable, no leader): that is not a state of the apply,
    /// so no verb proceeds and the operator is told to retry.
    static Decision decide(ApplyState state, Option<ClusterConfigView> view) {
        return switch (state.phase()) {
            case PLANNED, RUNNING -> both(state.phase());
            case ROLLING_BACK -> only(Verb.ROLLBACK,
                                      state.phase(),
                                      "a rollback is in progress; finish it with --rollback");
            case ABORTED -> only(Verb.ROLLBACK,
                                 ApplyPhase.ABORTED,
                                 "this apply was aborted (its lease or its commit was lost, so another holder or the leader may have acted);" + " the ledger is not trustworthy for continuing. Use --rollback, then re-plan");
            case COMMITTED -> only(Verb.RESUME,
                                   ApplyPhase.COMMITTED,
                                   "the config is committed; apply the previous config instead of rolling back");
            case COMMITTING -> view.map(seen -> decideCommitting(state, seen)).or(() -> new Decision(Set.of(),
                                                                                                     ApplyPhase.COMMITTING,
                                                                                                     "cannot determine the commit state, the cluster could not be read; retry when it is reachable"));
        };
    }

    private static Decision decideCommitting(ApplyState state, ClusterConfigView seen) {
        var sameConfig = seen.configHash().equals(state.configHash());
        var moved = seen.configVersion() != state.storedVersion();

        if (sameConfig && seen.configVersion() > state.storedVersion()) {
            return only(Verb.RESUME,
                        ApplyPhase.COMMITTED,
                        "the desired config is committed; --resume finalises, a rollback would delete the nodes it holds");
        }

        if (!moved) {
            return only(Verb.RESUME,
                        ApplyPhase.COMMITTING,
                        "the commit may still land (a timed-out request can be applied late); --resume retries it, and only its definitive answer makes --rollback safe");
        }

        return only(Verb.ROLLBACK,
                    ApplyPhase.ABORTED,
                    "the cluster config moved without this apply's config in it; roll back, then re-plan");
    }

    /// The commit retry was definitively refused (lease lost, version conflict, not admissible): not committed, and this
    /// apply cannot commit. Opens `--rollback` only.
    static ApplyState afterDefinitiveRefusal(ApplyState state) {
        return state.withPhase(ApplyPhase.ABORTED);
    }

    private static Decision both(ApplyPhase phase) {
        return new Decision(Set.of(Verb.RESUME, Verb.ROLLBACK), phase, "");
    }

    private static Decision only(Verb verb, ApplyPhase phase, String explanation) {
        return new Decision(Set.of(verb), phase, explanation);
    }
}
