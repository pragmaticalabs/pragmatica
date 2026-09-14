// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-EX-01"})
sealed interface BootstrapStatePersistence {
    record unused() implements BootstrapStatePersistence {}

    Path AETHER_DIR = Path.of(System.getProperty("user.home"), ".aether", "clusters");
    String STATE_FILE_NAME = "bootstrap-state.json";

    /// #980 — this file CONTAINS THE CLUSTER SECRET (`BootstrapState.clusterSecret`), and under the
    /// ruling that derives the bootstrap admin API key from that secret it is an admin-equivalent
    /// credential file, not merely bootstrap bookkeeping. It is therefore written through
    /// [`SecureFiles#writeSecure`] at owner-only `0600`, like the derived `api-key` file beside it
    /// (`BootstrapPhaseFormation.persistApiKeyFile`) and the runtime TOML (`BootstrapPhaseDeploy`) —
    /// the bare `Files.writeString` this replaces left the ROOT secret at default permissions while
    /// everything derived from it was locked down. Re-saving an existing state file repairs its
    /// permissions in place.
    static Result<Unit> save(BootstrapState state) {
        return ensureClusterDir(state).flatMap(dir -> SecureFiles.writeSecure(dir.resolve(STATE_FILE_NAME),
                                                                              state.toJson()));
    }

    private static Result<Path> ensureClusterDir(BootstrapState state) {
        return Result.lift(PersistenceError::new,
                           () -> Files.createDirectories(AETHER_DIR.resolve(state.clusterName().value())));
    }

    static Option<BootstrapState> load(ClusterName clusterName) {
        return read(clusterName).onFailure(cause -> System.err.println("Warning: failed to load bootstrap state: " + cause.message()))
                   .or(none());
    }

    /// #994 verification finding SF-1 — **ABSENT and UNREADABLE are different facts, and [#load] cannot
    /// tell a caller which it got.** Both arrive as `none()`, which is correct for a caller that only
    /// wants a state if there is one, and dangerous for the two callers whose decisions are
    /// money-bearing:
    ///
    ///   - `ClusterBootstrapOrchestrator.markPhaseFailed` falls back to the PRE-phase snapshot and saves
    ///     it. Over an absent file that is right — there is nothing to lose. Over a torn file it
    ///     **overwrites the only record of paid VMs with a VM-less one, and the result is valid JSON, so
    ///     nothing downstream can tell.** That is #994's exact outcome reached by a different route, and
    ///     it was measured rather than argued (verification report §4b).
    ///   - `ClusterDestroyCommand.cleanupCloudResources` reads an unreadable ledger as "no bootstrap
    ///     state", reports cleanup OK, removes the registry entry and exits 0 — while every server the
    ///     ledger named keeps billing and the operator's last handle on them is gone.
    ///
    /// So: absent is `success(none())`, present-and-parsed is `success(some(state))`, and present-but-
    /// unparseable is a FAILURE carrying the parse cause. A caller that must not act on a guess can now
    /// refuse instead.
    static Result<Option<BootstrapState>> read(ClusterName clusterName) {
        var path = stateFilePath(clusterName);

        if (!Files.exists(path)) {
            return Result.success(none());
        }

        return Result.lift(PersistenceError::new,
                           () -> Files.readString(path))
                     .flatMap(BootstrapState::fromJson)
                     .map(Option::some);
    }

    /// #1022 — the ONE read-modify-write append onto the persisted cleanup ledger, so every party that
    /// learns of a billable resource writes it through the same path rather than each growing its own.
    /// Two callers today: [BootstrapPhaseProvision#recordProvisionedVm] (the bootstrap recorder, #994)
    /// and [BootstrapCleanup#recordSweptVms] (the label sweep, which is the only channel by which an
    /// operator ever learns of a CTM auto-heal replacement — see that method for why).
    ///
    /// **It never CREATES a ledger.** An absent state file means this cluster was not bootstrapped from
    /// this machine, and fabricating a state for it would invent a cluster record with no secret, no
    /// source handle and no credential mapping — a file that reads as authoritative and can reap
    /// nothing. Absent is therefore a FAILURE the caller reports, not a file it writes.
    ///
    /// The three ways it can decline are distinct facts and each keeps its own wording, because a caller
    /// renders `cause.message()` verbatim into the operator's transcript and "unreadable" (the bytes are
    /// the only surviving trace of paid VMs), "absent" (nothing to append to) and "write failed" (a full
    /// disk) call for three different operator actions.
    static Result<Unit> appendResource(ClusterName clusterName, CreatedResource resource) {
        return read(clusterName).mapError(BootstrapStatePersistence::unreadable)
                   .flatMap(state -> state.toResult(LEDGER_ABSENT))
                   .map(state -> state.withResource(resource))
                   .flatMap(BootstrapStatePersistence::saveAppended);
    }

    Fn1<Cause, String> LEDGER_UNREADABLE = Causes.forOneValue("the persisted ledger is unreadable: %s");
    Cause LEDGER_ABSENT = Causes.cause("no bootstrap state is persisted for this cluster");
    Fn1<Cause, String> LEDGER_WRITE_FAILED = Causes.forOneValue("the ledger write failed: %s");

    private static Cause unreadable(Cause cause) {
        return LEDGER_UNREADABLE.apply(cause.message());
    }

    private static Result<Unit> saveAppended(BootstrapState state) {
        return save(state).mapError(BootstrapStatePersistence::writeFailed);
    }

    private static Cause writeFailed(Cause cause) {
        return LEDGER_WRITE_FAILED.apply(cause.message());
    }

    static Result<Unit> delete(ClusterName clusterName) {
        return Result.lift(PersistenceError::new, () -> doDelete(clusterName));
    }

    static Path statePath(ClusterName clusterName) {
        return stateFilePath(clusterName);
    }

    private static Unit doDelete(ClusterName clusterName) throws Exception {
        var path = stateFilePath(clusterName);

        Files.deleteIfExists(path);

        return Unit.unit();
    }

    private static Path stateFilePath(ClusterName clusterName) {
        return AETHER_DIR.resolve(clusterName.value()).resolve(STATE_FILE_NAME);
    }

    record PersistenceError(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Bootstrap state persistence error: " + cause.getMessage();
        }
    }
}
