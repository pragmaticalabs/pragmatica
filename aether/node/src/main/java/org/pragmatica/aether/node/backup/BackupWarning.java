// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

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
        /// #1533: another cluster (a different incarnation id) holds the head at this cluster's own lineage and incarnation (two
        /// clusters restored from the same backup); neither is written over the other.
        BACKUP_FORKED,
        /// The head of this cluster's own lineage has stayed ahead of its state past the bound: nothing is
        /// being backed up, and once this cluster's revision passes the head's its state replaces it.
        BACKUP_HEAD_AHEAD,
        /// The backup head exists but cannot be read as a backup document.
        BACKUP_REMOTE_UNREADABLE,
        /// Commits are queued locally but have not reached the remote for longer than the lag bound.
        BACKUP_PUSH_FAILING,
        /// The local repository could not take a commit (disk, permissions, git missing).
        BACKUP_COMMIT_FAILED,
        /// A failing or gated backup is current again.
        BACKUP_RECOVERED,
        /// A head that stayed ahead of this cluster's state was replaced once this cluster's revision passed
        /// it (hazard d). The replaced commit stays in git history. Never an all-clear.
        BACKUP_HEAD_REPLACED,
        /// #1533: the cold-restart restore cannot read the backup (unreachable, undecodable); cluster-state
        /// writes stay refused until it can, or until a restart with `[backup] restore = "fresh"`.
        BACKUP_RESTORE_BLOCKED,
        /// #1533: `[backup]` has no remote, so a restore reads only the deciding leader's local repository.
        BACKUP_RESTORE_SOURCE_LOCAL,
        /// #1533: the restore withheld the previous cluster's entity checkpoints; entity state restarts empty.
        BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED
    }

    public static BackupWarning backupWarning(Code code, String detail) {
        return new BackupWarning(code, detail);
    }

    /// Where warnings go. Loud operator warnings are also cluster events (owner ruling, #1617).
    @FunctionalInterface
    public interface Sink {
        @Contract
        void emit(BackupWarning warning);

        /// WARN (INFO for a recovery), log only.
        static Sink logging() {
            return BackupWarning::log;
        }

        /// The node's sink: the restore-blocked, dropped-checkpoints and forked conditions are raised as
        /// `OperatorWarning` cluster events through `operatorWarnings` (which also logs them); every other
        /// code is logged as by [#logging].
        static Sink operatorWarnings(OperatorWarningSink operatorWarnings) {
            return warning -> operatorWarningCode(warning.code()).onPresent(code -> OperatorWarnings.raise(LOG,
                                                                                                           operatorWarnings,
                                                                                                           code,
                                                                                                           SUBJECT,
                                                                                                           "{}",
                                                                                                           warning.detail()))
                                                 .onEmpty(() -> log(warning));
        }
    }

    /// The `OperatorWarning` subject of every backup condition: the node's KV backup.
    static final String SUBJECT = "kv-backup";

    /// The backup conditions that are also cluster events (#1617).
    static Option<OperatorWarningCode> operatorWarningCode(Code code) {
        return switch (code) {
            case BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED -> Option.some(OperatorWarningCode.BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED);
            case BACKUP_RESTORE_BLOCKED -> Option.some(OperatorWarningCode.BACKUP_RESTORE_BLOCKED);
            case BACKUP_FORKED -> Option.some(OperatorWarningCode.BACKUP_FORKED);
            default -> Option.none();
        };
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
