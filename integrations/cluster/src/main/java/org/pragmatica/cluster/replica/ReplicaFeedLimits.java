// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

/// The bounds of one replica feed server. Every number is a guess to be tuned by the M-OBS measurement.
///
/// @param chunkBytes size of one snapshot chunk
/// @param maxServes snapshot transfers in flight at once; a further asker is answered `BUSY`
/// @param cacheMillis how long an encoded snapshot is reused for the same `(tag, revision)`
/// @param cacheEntries how many encoded snapshots the cache holds
/// @param queueBytes byte bound of one subscriber's outbound queue
/// @param maxSubscribers subscriptions held at once; a further subscriber is answered `BUSY`
/// @param transferIdleMillis a snapshot transfer nobody pulls from for this long is abandoned and frees its serve slot
/// @param drainMessagesPerTick messages one subscriber is sent per drain tick, so one busy subscriber cannot starve the others
/// @param catchUpWeight effects (plus one per entry) a single catch-up message carries
public record ReplicaFeedLimits(int chunkBytes,
                                int maxServes,
                                long cacheMillis,
                                int cacheEntries,
                                long queueBytes,
                                int maxSubscribers,
                                long transferIdleMillis,
                                int drainMessagesPerTick,
                                int catchUpWeight) {
    public static final ReplicaFeedLimits DEFAULT = new ReplicaFeedLimits(64 * 1024,
                                                                          2,
                                                                          5_000L,
                                                                          2,
                                                                          8L * 1024 * 1024,
                                                                          512,
                                                                          30_000L,
                                                                          256,
                                                                          256);
}
