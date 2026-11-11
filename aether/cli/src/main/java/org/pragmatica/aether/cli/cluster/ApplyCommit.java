// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_APPLY;


/// The commit of an apply (cluster-apply spec 4.6): once the waves have run, the desired config is persisted on the cluster with
/// the version the apply started from as its fence. The ledger moves to `COMMITTING` BEFORE the request is sent, so a crash or a
/// timeout anywhere inside it leaves exactly the state [ApplyCommitRecovery] decides on.
///
/// A commit is a function of the expected config version, so `--resume` can send the version its LEDGER recorded.
/// Its outcome is told apart: a refusal by the cluster (HTTP 4xx: version conflict, not admissible) is definitive and opens only
/// `--rollback`; anything else (no answer, 5xx) is not known to have failed, so the ledger stays `COMMITTING` for `--resume`.
final class ApplyCommit {
    private ApplyCommit() {}

    /// `POST CLUSTER_CONFIG_APPLY` of the config exactly as the operator wrote it (references unresolved).
    static Fn1<Result<String>, Long> toCluster(String tomlContent) {
        return expectedVersion -> ClusterHttpClient.post(CLUSTER_CONFIG_APPLY, applyJson(tomlContent, expectedVersion));
    }

    static Result<ApplyResult> finish(ApplyState state, Fn1<Result<String>, Long> commit, ApplyResult result) {
        return ApplyState.save(state.withPhase(ApplyPhase.COMMITTING))
                         .flatMap(_ -> commit.apply(state.storedVersion())
                                             .mapError(cause -> classify(state, cause)))
                         .flatMap(_ -> clear(state, result));
    }

    /// A config change that needs no wave: nothing was provisioned, so there is no ledger to protect.
    static Result<ApplyResult> commitWithoutLedger(Fn1<Result<String>, Long> commit,
                                                   long expectedVersion,
                                                   ApplyResult result) {
        return commit.apply(expectedVersion)
                     .map(_ -> result);
    }

    private static Cause classify(ApplyState state, Cause cause) {
        return isDefinitiveRefusal(cause)
               ? refused(state, cause)
               : new ApplyOrchestrator.ApplyAborted("The commit of the new config was not confirmed (" + cause.message()
                                                   + "). The nodes the apply created are kept and recorded; run `aether cluster apply --resume` to retry the commit.");
    }

    private static Cause refused(ApplyState state, Cause cause) {
        ApplyState.save(ApplyCommitRecovery.afterDefinitiveRefusal(state)).onFailure(failure -> System.err.println("Warning: the ledger could not be marked ABORTED: " + failure.message()));

        return new ApplyOrchestrator.ApplyAborted("The cluster refused the new config (" + cause.message()
                                                 + "). The nodes the apply created are recorded; run `aether cluster apply --rollback` to remove them, then re-plan.");
    }

    private static boolean isDefinitiveRefusal(Cause cause) {
        return cause instanceof ClusterHttpClient.HttpError.ApiError error
               && error.statusCode() >= 400
               && error.statusCode() < 500;
    }

    // FER: the apply and its commit succeeded, so a ledger that cannot be removed is reported, not failed. Guarantee earned:
    // the exit status tells the truth about the VMs. Mechanism: the leftover COMMITTING ledger is read by the next apply, whose
    // gate sees the committed config and finalises it.
    @Contract
    private static Result<ApplyResult> clear(ApplyState state, ApplyResult result) {
        ApplyState.delete(state.clusterName()).onFailure(cause -> System.err.println("Warning: the apply succeeded but its state file could not be removed: " + cause.message()));

        return Result.success(result);
    }

    static String applyJson(String tomlContent, long expectedVersion) {
        return "{\"tomlContent\":\"" + escape(tomlContent) + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
