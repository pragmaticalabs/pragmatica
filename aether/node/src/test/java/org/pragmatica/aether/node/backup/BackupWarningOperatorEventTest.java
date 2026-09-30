// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1533 × #1617: the node's backup warning sink raises the restore-blocked, dropped-checkpoints and forked
/// conditions as `OperatorWarning` cluster events, and only those. Each test first emits a code that is
/// NOT an event: the hand-off is one ordered queue, so had it been raised it would arrive first.
class BackupWarningOperatorEventTest {
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final BackupWarning.Sink sink = BackupWarning.Sink.operatorWarnings(OperatorWarningSink.handingOffTo(raised::add));

    @Test
    void droppedEntityCheckpoints_areRaisedAsAnOperatorWarning() {
        assertRaisedAlone(Code.BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED,
                          OperatorWarningCode.BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED);
    }

    @Test
    void aBlockedRestore_isRaisedAsAnOperatorWarning() {
        assertRaisedAlone(Code.BACKUP_RESTORE_BLOCKED, OperatorWarningCode.BACKUP_RESTORE_BLOCKED);
    }

    @Test
    void aFork_isRaisedAsAnOperatorWarning() {
        assertRaisedAlone(Code.BACKUP_FORKED, OperatorWarningCode.BACKUP_FORKED);
    }

    private void assertRaisedAlone(Code code, OperatorWarningCode expected) {
        sink.emit(BackupWarning.backupWarning(Code.BACKUP_GATED, "not an event"));
        sink.emit(BackupWarning.backupWarning(code, "detail for " + code));

        await().atMost(Duration.ofSeconds(10))
               .until(() -> !raised.isEmpty());
        assertThat(raised).singleElement()
                          .satisfies(warning -> {
                              assertThat(warning.code()).isEqualTo(expected);
                              assertThat(warning.subject()).isEqualTo(BackupWarning.SUBJECT);
                              assertThat(warning.message()).isEqualTo("detail for " + code);
                          });
    }
}
