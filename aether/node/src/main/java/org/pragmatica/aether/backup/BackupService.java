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

package org.pragmatica.aether.backup;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

/// Service interface for cluster state backup operations.
public interface BackupService {
    /// Trigger a manual backup now.
    Result<Unit> backupNow();

    /// List available backup snapshots.
    Result<List<BackupInfo>> listBackups();

    /// Restore cluster state from a specific backup commit.
    Result<Unit> restore(String commitId);

    /// Backup snapshot metadata.
    record BackupInfo(String commitId, String message, String timestamp) {
        public static BackupInfo backupInfo(String commitId, String message, String timestamp) {
            return new BackupInfo(commitId, message, timestamp);
        }
    }

    /// Errors that can occur during backup operations.
    sealed interface BackupError extends Cause {
        record BackupFailed(Cause cause) implements BackupError {
            @Override
            public String message() {
                return "Backup failed: " + cause.message();
            }
        }

        record RestoreNotAllowed() implements BackupError {
            @Override
            public String message() {
                return "Restore not allowed while cluster is active";
            }
        }

        record CommitNotFound(String commitId) implements BackupError {
            @Override
            public String message() {
                return "Backup commit not found: " + commitId;
            }
        }

        record BackupDisabled() implements BackupError {
            @Override
            public String message() {
                return "Backup is not enabled";
            }
        }

        static BackupError backupFailed(Cause cause) {
            return new BackupFailed(cause);
        }

        static BackupError restoreNotAllowed() {
            return new RestoreNotAllowed();
        }

        static BackupError commitNotFound(String id) {
            return new CommitNotFound(id);
        }

        static BackupError backupDisabled() {
            return new BackupDisabled();
        }
    }

    /// No-op implementation for when backup is disabled.
    static BackupService disabled() {
        var disabledError = BackupError.backupDisabled();
        record disabledBackupService(BackupError error) implements BackupService {
            @Override
            public Result<Unit> backupNow() {
                return error.result();
            }

            @Override
            public Result<List<BackupInfo>> listBackups() {
                return error.result();
            }

            @Override
            public Result<Unit> restore(String commitId) {
                return error.result();
            }
        }
        return new disabledBackupService(disabledError);
    }
}
