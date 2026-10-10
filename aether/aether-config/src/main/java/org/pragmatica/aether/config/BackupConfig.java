// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


/// `[backup]` (#1532, #1533): the change-triggered KV backup under `<path>/kv-backup`, pushed to `remote`
/// when one is set, and what a cold restart does with it (`restore`).
public record BackupConfig(boolean enabled, String path, String remote, RestoreMode restore) {
    /// `[backup] restore`: what a cold restart does with the backup.
    public enum RestoreMode {
        /// Restore the backup head when there is one; start fresh only when the backup is empty.
        AUTO,
        /// Ignore the backup and start a fresh cluster — the operator's escape hatch.
        FRESH;
        private static final Fn1<Cause, String> UNKNOWN_MODE = Causes.forOneValue("unknown [backup] restore '%s' — use auto or fresh");
        public static Result<RestoreMode> restoreMode(String raw) {
            return switch (raw.strip()) {
                case "auto" -> success(AUTO);
                case "fresh" -> success(FRESH);
                default -> UNKNOWN_MODE.apply(raw).result();
            };
        }
    }

    /// This `[backup]` as the `AETHER_BACKUP_*` environment of [ClusterIdentityEnv#BACKUP_VARS] (#1968): what a node without a node
    /// TOML of its own (a Docker replacement) needs to run the SAME backup. Empty when the backup is disabled or has no path (the
    /// node does not run it either, `Main.resolveBackup`). The restore mode is rendered the way [RestoreMode#restoreMode] reads it.
    public java.util.Map<String, String> asEnvironment() {
        if (!enabled || path.isBlank()) {
            return java.util.Map.of();
        }

        var env = new java.util.LinkedHashMap<String, String>();

        env.put(org.pragmatica.aether.environment.ClusterIdentityEnv.BACKUP_ENABLED, "true");
        env.put(org.pragmatica.aether.environment.ClusterIdentityEnv.BACKUP_PATH, path);
        if (!remote.isBlank()) {
            env.put(org.pragmatica.aether.environment.ClusterIdentityEnv.BACKUP_REMOTE, remote);
        }

        env.put(org.pragmatica.aether.environment.ClusterIdentityEnv.BACKUP_RESTORE,
                restore.name().toLowerCase(java.util.Locale.ROOT));

        return java.util.Map.copyOf(env);
    }

    public static BackupConfig backupConfig() {
        return new BackupConfig(false, "", "", RestoreMode.AUTO);
    }

    public static BackupConfig backupConfig(boolean enabled, String path, String remote, RestoreMode restore) {
        return new BackupConfig(enabled, path, remote, restore);
    }

    public static BackupConfig backupConfig(Environment env) {
        var defaultPath = switch (env) {
            case LOCAL -> "./aether-backups";
            case DOCKER -> "/data/backups";
            case KUBERNETES -> "/var/aether/backups";
        };

        return new BackupConfig(false, defaultPath, "", RestoreMode.AUTO);
    }
}
