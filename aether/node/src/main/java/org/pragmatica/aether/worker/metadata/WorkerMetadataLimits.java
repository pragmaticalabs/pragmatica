// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Explicit resource envelope. Oversize fails rather than silently truncating worker knowledge.
public record WorkerMetadataLimits(int chunkBytes,
                                   int scopeBytes,
                                   long cacheBytes,
                                   long bytesPerSecond,
                                   int manifests,
                                   int scopesPerWorker,
                                   TimeSpan manifestTtl,
                                   TimeSpan pollInterval) {
    public static final WorkerMetadataLimits DEFAULT = new WorkerMetadataLimits(64 * 1024,
                                                                                8 * 1024 * 1024,
                                                                                128L * 1024 * 1024,
                                                                                16L * 1024 * 1024,
                                                                                16384,
                                                                                512,
                                                                                timeSpan(30).seconds(),
                                                                                timeSpan(1).seconds());
}
