// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;

/// Test helper: opens the package-private WAL-backed factory ([StreamPartitionManager#streamPartitionManagerOverLogs]) to tests
/// of other packages, so a replication test can run over a real WAL.
public final class WalBackedStreamPartitionManagers {
    private WalBackedStreamPartitionManagers() {}

    public static StreamPartitionManager overLogs(AppendLog.Opener opener) {
        return StreamPartitionManager.streamPartitionManagerOverLogs(Long.MAX_VALUE, Option.some(opener), LastSealedOffsetSource.none());
    }
}
