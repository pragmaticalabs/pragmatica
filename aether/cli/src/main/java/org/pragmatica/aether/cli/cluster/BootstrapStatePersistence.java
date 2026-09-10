// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
        var path = stateFilePath(clusterName);

        if (!Files.exists(path)) {
            return none();
        }

        return Result.lift(PersistenceError::new,
                           () -> Files.readString(path))
                     .flatMap(BootstrapState::fromJson)
                     .onFailure(cause -> System.err.println("Warning: failed to load bootstrap state: " + cause.message()))
                     .option();
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
