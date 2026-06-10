// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

public record BackupConfig(boolean enabled, String interval, String path, String remote) {
    public static BackupConfig backupConfig() {
        return new BackupConfig(false, "5m", "", "");
    }

    public static BackupConfig backupConfig(boolean enabled, String interval, String path, String remote) {
        return new BackupConfig(enabled, interval, path, remote);
    }

    public static BackupConfig backupConfig(Environment env) {
        var defaultPath = switch (env) {
            case LOCAL -> "./aether-backups";
            case DOCKER -> "/data/backups";
            case KUBERNETES -> "/var/aether/backups";
        };

        return new BackupConfig(false, "5m", defaultPath, "");
    }
}
