// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.junit.jupiter.api.Assertions.fail;

/// A storage instance a seal can succeed on (#1567): `seal` refuses an instance with no durable tier, and a
/// log is truncatable only past what a seal made durable. Memory plus a local-disk tier under `dir`.
final class DurableTestStorage {
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private DurableTestStorage() {}

    static StorageInstance durableStorage(String name, Path dir) {
        return StorageInstance.storageInstance(name,
                                               List.of(MemoryTier.memoryTier(ONE_GB),
                                                       LocalDiskTier.localDiskTier(dir.resolve("blocks"), ONE_GB)
                                                                    .unwrap()));
    }

    /// Seal `segment`'s range of `log` through `storage` -- the only way the log's records become truncatable.
    /// A partition without a log has nothing to seal against.
    static Promise<Unit> sealThrough(StorageInstance storage, SealedSegment segment, Option<AppendLog> log) {
        return log.fold(Promise::unitPromise,
                        wal -> storage.seal(wal,
                                            segment.startOffset(),
                                            segment.endOffset(),
                                            "test/" + segment.streamName() + "/" + segment.partition() + "/"
                                            + segment.startOffset() + "-" + segment.endOffset(),
                                            segment.serializedEvents())
                                      .mapToUnit());
    }

    /// Seal `[fromOffset, toOffset]` of `wal` through `storage` with an arbitrary block.
    static Unit sealRange(StorageInstance storage, AppendLog wal, long fromOffset, long toOffset) {
        storage.seal(wal, fromOffset, toOffset, "test/range/" + fromOffset + "-" + toOffset, new byte[]{1, 2, 3})
               .await()
               .onFailure(cause -> fail(cause.message()));

        return Unit.unit();
    }
}
