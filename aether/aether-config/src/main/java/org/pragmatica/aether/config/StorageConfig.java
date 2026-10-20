// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;


/// Per-instance storage configuration (`[storage.<instance>]` TOML sections).
///
/// `walPath` (#634-3) is the stream WAL's BASE directory and is read from the `streams` instance
/// only. Empty (the default) means DERIVE: `<artifacts disk_path sibling>/stream-segments/<nodeId>/wal`
/// — the pre-#634-3 behaviour, byte-identical, so existing deployments need no config change. When
/// set, the effective directory is still suffixed with the node id (`<walPath>/<nodeId>`): multiple
/// nodes on one host (EmberCluster, co-located containers) must never share a WAL directory, and the
/// suffix is what keeps that invariant independent of operator input.
///
/// `promotion` is a kill switch for background tier promotion on this instance (default on): promotion copies hot
/// blocks into the node's faster tiers, per node. It has no effect on `streams`, which never promotes.
///
/// `prefetch` is the kill switch for cross-node block prefetching on this instance: on by default, it lets the
/// node gossip hints about the blocks it reads often and copy hinted blocks into its own cache tiers. It is read
/// for every instance but `streams`, which prefetches its own segments and takes no part in it.
///
/// `prefetchMaxInFlight` is the most prefetches that run at once on this instance (`prefetch_max_in_flight`, default
/// 4). A hint that finds every slot taken is dropped and counted, never queued. The loader refuses a value that is not
/// a positive integer; `prefetch = false` is how prefetching is switched off.
public record StorageConfig(long memoryMaxBytes,
                            long diskMaxBytes,
                            String diskPath,
                            String snapshotPath,
                            int snapshotMutationThreshold,
                            String snapshotMaxInterval,
                            int snapshotRetentionCount,
                            String walPath,
                            boolean encrypted,
                            boolean promotion,
                            boolean prefetch,
                            int prefetchMaxInFlight) {
    /// The default for `prefetchMaxInFlight`; the prefetch manager's own default is pinned to the same number by a test.
    public static final int DEFAULT_PREFETCH_MAX_IN_FLIGHT = 4;

    /// The shape this record had before prefetching existed: `prefetch` is on and the cap takes its default.
    public StorageConfig(long memoryMaxBytes,
                         long diskMaxBytes,
                         String diskPath,
                         String snapshotPath,
                         int snapshotMutationThreshold,
                         String snapshotMaxInterval,
                         int snapshotRetentionCount,
                         String walPath,
                         boolean encrypted,
                         boolean promotion) {
        this(memoryMaxBytes,
             diskMaxBytes,
             diskPath,
             snapshotPath,
             snapshotMutationThreshold,
             snapshotMaxInterval,
             snapshotRetentionCount,
             walPath,
             encrypted,
             promotion,
             true,
             DEFAULT_PREFETCH_MAX_IN_FLIGHT);
    }

    /// The shape before the in-flight cap existed: the cap takes its default.
    public StorageConfig(long memoryMaxBytes,
                         long diskMaxBytes,
                         String diskPath,
                         String snapshotPath,
                         int snapshotMutationThreshold,
                         String snapshotMaxInterval,
                         int snapshotRetentionCount,
                         String walPath,
                         boolean encrypted,
                         boolean promotion,
                         boolean prefetch) {
        this(memoryMaxBytes,
             diskMaxBytes,
             diskPath,
             snapshotPath,
             snapshotMutationThreshold,
             snapshotMaxInterval,
             snapshotRetentionCount,
             walPath,
             encrypted,
             promotion,
             prefetch,
             DEFAULT_PREFETCH_MAX_IN_FLIGHT);
    }

    /// Pre-promotion-key shape, kept so existing callers stay source-compatible; `promotion` defaults to on.
    public StorageConfig(long memoryMaxBytes,
                         long diskMaxBytes,
                         String diskPath,
                         String snapshotPath,
                         int snapshotMutationThreshold,
                         String snapshotMaxInterval,
                         int snapshotRetentionCount,
                         String walPath,
                         boolean encrypted) {
        this(memoryMaxBytes,
             diskMaxBytes,
             diskPath,
             snapshotPath,
             snapshotMutationThreshold,
             snapshotMaxInterval,
             snapshotRetentionCount,
             walPath,
             encrypted,
             true);
    }

    /// #912: names the root the default `disk_path`/`snapshot_path` resolve under, instead of `/data/aether`.
    /// A node whose storage sections omit their paths (the shipped image config sets none) otherwise writes
    /// under that fixed, machine-global root; tests and co-located runs point it at their own directory.
    public static final String DEFAULT_ROOT_PROPERTY = "aether.storage.defaultRoot";
    private static final String DEFAULT_ROOT = "/data/aether";

    public static StorageConfig storageConfig() {
        var root = Option.option(System.getProperty(DEFAULT_ROOT_PROPERTY))
                         .filter(Verify.Is::notBlank)
                         .or(DEFAULT_ROOT);

        return new StorageConfig(256 * 1024 * 1024,
                                 10L * 1024 * 1024 * 1024,
                                 root + "/storage",
                                 root + "/metadata-snapshots",
                                 1000,
                                 "60s",
                                 5,
                                 "",
                                 false,
                                 true);
    }

    /// Pre-#634-3 shape, kept so existing callers (and the defaults they encode) stay source-compatible;
    /// `walPath` defaults to DERIVE, `encrypted` defaults to false.
    public static StorageConfig storageConfig(long memoryMaxBytes,
                                              long diskMaxBytes,
                                              String diskPath,
                                              String snapshotPath,
                                              int snapshotMutationThreshold,
                                              String snapshotMaxInterval,
                                              int snapshotRetentionCount) {
        return new StorageConfig(memoryMaxBytes,
                                 diskMaxBytes,
                                 diskPath,
                                 snapshotPath,
                                 snapshotMutationThreshold,
                                 snapshotMaxInterval,
                                 snapshotRetentionCount,
                                 "",
                                 false,
                                 true);
    }

    /// Pre-#253 shape, kept so existing callers stay source-compatible; `encrypted` defaults to false.
    public static StorageConfig storageConfig(long memoryMaxBytes,
                                              long diskMaxBytes,
                                              String diskPath,
                                              String snapshotPath,
                                              int snapshotMutationThreshold,
                                              String snapshotMaxInterval,
                                              int snapshotRetentionCount,
                                              String walPath) {
        return new StorageConfig(memoryMaxBytes,
                                 diskMaxBytes,
                                 diskPath,
                                 snapshotPath,
                                 snapshotMutationThreshold,
                                 snapshotMaxInterval,
                                 snapshotRetentionCount,
                                 walPath,
                                 false,
                                 true);
    }

    /// #253: `encrypted` opts this instance's disk/DHT tiers into [org.pragmatica.storage.EncryptingStorageTier],
    /// resolved against the node's `[storage.encryption]` keyring (see [StorageEncryptionConfig]). Requires
    /// `[storage.encryption]` to be present -- enforced by `StorageEncryptionConfigValidator`, not here.
    public static StorageConfig storageConfig(long memoryMaxBytes,
                                              long diskMaxBytes,
                                              String diskPath,
                                              String snapshotPath,
                                              int snapshotMutationThreshold,
                                              String snapshotMaxInterval,
                                              int snapshotRetentionCount,
                                              String walPath,
                                              boolean encrypted) {
        return new StorageConfig(memoryMaxBytes,
                                 diskMaxBytes,
                                 diskPath,
                                 snapshotPath,
                                 snapshotMutationThreshold,
                                 snapshotMaxInterval,
                                 snapshotRetentionCount,
                                 walPath,
                                 encrypted,
                                 true);
    }

    /// `promotion` switches background tier promotion on this instance on or off (`[storage.<name>] promotion`).
    public static StorageConfig storageConfig(long memoryMaxBytes,
                                              long diskMaxBytes,
                                              String diskPath,
                                              String snapshotPath,
                                              int snapshotMutationThreshold,
                                              String snapshotMaxInterval,
                                              int snapshotRetentionCount,
                                              String walPath,
                                              boolean encrypted,
                                              boolean promotion) {
        return new StorageConfig(memoryMaxBytes,
                                 diskMaxBytes,
                                 diskPath,
                                 snapshotPath,
                                 snapshotMutationThreshold,
                                 snapshotMaxInterval,
                                 snapshotRetentionCount,
                                 walPath,
                                 encrypted,
                                 promotion);
    }

    /// `prefetch` switches cross-node block prefetching off for this instance (`[storage.<name>] prefetch = false`).
    public static StorageConfig storageConfig(long memoryMaxBytes,
                                              long diskMaxBytes,
                                              String diskPath,
                                              String snapshotPath,
                                              int snapshotMutationThreshold,
                                              String snapshotMaxInterval,
                                              int snapshotRetentionCount,
                                              String walPath,
                                              boolean encrypted,
                                              boolean promotion,
                                              boolean prefetch) {
        return new StorageConfig(memoryMaxBytes,
                                 diskMaxBytes,
                                 diskPath,
                                 snapshotPath,
                                 snapshotMutationThreshold,
                                 snapshotMaxInterval,
                                 snapshotRetentionCount,
                                 walPath,
                                 encrypted,
                                 promotion,
                                 prefetch);
    }

    /// `prefetchMaxInFlight` bounds the prefetches running at once (`[storage.<name>] prefetch_max_in_flight`).
    public static StorageConfig storageConfig(long memoryMaxBytes,
                                              long diskMaxBytes,
                                              String diskPath,
                                              String snapshotPath,
                                              int snapshotMutationThreshold,
                                              String snapshotMaxInterval,
                                              int snapshotRetentionCount,
                                              String walPath,
                                              boolean encrypted,
                                              boolean promotion,
                                              boolean prefetch,
                                              int prefetchMaxInFlight) {
        return new StorageConfig(memoryMaxBytes,
                                 diskMaxBytes,
                                 diskPath,
                                 snapshotPath,
                                 snapshotMutationThreshold,
                                 snapshotMaxInterval,
                                 snapshotRetentionCount,
                                 walPath,
                                 encrypted,
                                 promotion,
                                 prefetch,
                                 prefetchMaxInFlight);
    }

    /// True when an operator explicitly placed the WAL; false means derive the pre-#634-3 default.
    /// Null-safe via [Option] rather than an inline null check (JBCT null policy): the loader
    /// guarantees `""`, but the canonical constructor is public and a null must read as "derive".
    public boolean hasExplicitWalPath() {
        return Option.option(walPath)
                     .filter(Verify.Is::notBlank)
                     .isPresent();
    }
}
