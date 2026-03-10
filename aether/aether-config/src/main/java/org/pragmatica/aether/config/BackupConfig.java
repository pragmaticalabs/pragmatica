/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.aether.config;
/// Backup configuration for Aether cluster state persistence.
///
/// @param enabled  whether backup is enabled
/// @param interval backup interval like "5m" (parsed elsewhere)
/// @param path     backup directory path
/// @param remote   optional git remote URL (empty = no remote)
public record BackupConfig(boolean enabled,
                           String interval,
                           String path,
                           String remote) {
    /// Create a disabled backup configuration with defaults.
    public static BackupConfig backupConfig() {
        return new BackupConfig(false, "5m", "", "");
    }

    /// Create a backup configuration with all parameters.
    public static BackupConfig backupConfig(boolean enabled, String interval, String path, String remote) {
        return new BackupConfig(enabled, interval, path, remote);
    }

    /// Create a backup configuration with environment-aware default path.
    public static BackupConfig backupConfig(Environment env) {
        var defaultPath = switch (env) {
            case LOCAL -> "./aether-backups";
            case DOCKER -> "/data/backups";
            case KUBERNETES -> "/var/aether/backups";
        };
        return new BackupConfig(false, "5m", defaultPath, "");
    }
}
