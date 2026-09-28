// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// An operator-facing backup condition (#1532), emitted on TRANSITIONS only — entering a failing or
/// gated state, and recovering from one — never once per retry.
///
/// `detail` always names what the operator can do about it.
public record BackupWarning(Code code, String detail) {
    public enum Code {
        /// A foreign or older lineage holds the backup head; this cluster will not overwrite it.
        BACKUP_GATED,
        /// The backup head exists but cannot be read as a backup document.
        BACKUP_REMOTE_UNREADABLE,
        /// Commits are queued locally but have not reached the remote for longer than the lag bound.
        BACKUP_PUSH_FAILING,
        /// The local repository could not take a commit (disk, permissions, git missing).
        BACKUP_COMMIT_FAILED,
        /// A failing or gated backup is current again.
        BACKUP_RECOVERED
    }

    public static BackupWarning backupWarning(Code code, String detail) {
        return new BackupWarning(code, detail);
    }

    /// Where warnings go. Loud operator warnings are also cluster events (owner ruling); until the
    /// `OperatorWarning` event exists this sink logs.
    @FunctionalInterface
    public interface Sink {
        @Contract
        void emit(BackupWarning warning);

        /// WARN (INFO for a recovery). TODO(#1574): also emit as an `OperatorWarning` cluster event.
        static Sink logging() {
            return BackupWarning::log;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BackupWarning.class);

    @Contract
    private static void log(BackupWarning warning) {
        if (warning.code() == Code.BACKUP_RECOVERED) {
            LOG.info("{}: {}", warning.code(), warning.detail());
        } else {
            LOG.warn("{}: {}", warning.code(), warning.detail());
        }
    }
}
