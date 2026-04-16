// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Persistence for bootstrap state files at `~/.aether/clusters/<name>/bootstrap-state.json`.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-EX-01"}) sealed interface BootstrapStatePersistence {
    record unused() implements BootstrapStatePersistence{}

    Path AETHER_DIR = Path.of(System.getProperty("user.home"), ".aether", "clusters");

    String STATE_FILE_NAME = "bootstrap-state.json";

    static Result<Unit> save(BootstrapState state) {
        return Result.lift(PersistenceError::new, () -> doSave(state));
    }

    static Option<BootstrapState> load(String clusterName) {
        var path = stateFilePath(clusterName);
        if (!Files.exists(path)) {return none();}
        return Result.lift(PersistenceError::new,
                           () -> Files.readString(path)).flatMap(BootstrapState::fromJson)
                          .onFailure(cause -> System.err.println("Warning: failed to load bootstrap state: " + cause.message()))
                          .option();
    }

    static Result<Unit> delete(String clusterName) {
        return Result.lift(PersistenceError::new, () -> doDelete(clusterName));
    }

    private static Unit doSave(BootstrapState state) throws Exception {
        var dir = AETHER_DIR.resolve(state.clusterName());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(STATE_FILE_NAME), state.toJson());
        return Unit.unit();
    }

    private static Unit doDelete(String clusterName) throws Exception {
        var path = stateFilePath(clusterName);
        Files.deleteIfExists(path);
        return Unit.unit();
    }

    private static Path stateFilePath(String clusterName) {
        return AETHER_DIR.resolve(clusterName).resolve(STATE_FILE_NAME);
    }

    record PersistenceError(Throwable cause) implements Cause {
        @Override public String message() {
            return "Bootstrap state persistence error: " + cause.getMessage();
        }
    }
}
