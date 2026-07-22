// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.backup;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface BackupService {
    Result<Unit> backupNow();
    Result<List<BackupInfo>> listBackups();
    Result<Unit> restore(String commitId);

    record BackupInfo(String commitId, String message, String timestamp) {
        public static BackupInfo backupInfo(String commitId, String message, String timestamp) {
            return new BackupInfo(commitId, message, timestamp);
        }
    }

    sealed interface BackupError extends Cause {
        record BackupFailed(Cause cause) implements BackupError {
            @Override
            public String message() {
                return "Backup failed: " + cause.message();
            }
        }

        enum RestoreNotAllowed implements BackupError {
            INSTANCE;
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

        enum BackupDisabled implements BackupError {
            INSTANCE;
            @Override
            public String message() {
                return "Backup is not enabled";
            }
        }

        static BackupError backupFailed(Cause cause) {
            return new BackupFailed(cause);
        }

        static BackupError restoreNotAllowed() {
            return RestoreNotAllowed.INSTANCE;
        }

        static BackupError commitNotFound(String id) {
            return new CommitNotFound(id);
        }

        static BackupError backupDisabled() {
            return BackupDisabled.INSTANCE;
        }
    }

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
