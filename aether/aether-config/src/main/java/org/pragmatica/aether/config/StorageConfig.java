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
///
/// `maxBlockBytes` is the largest block the instance accepts (`max_block_bytes`, default 16 MiB): a write of more bytes
/// is refused, and a content store whose chunks would make larger blocks is refused when it is provisioned. It is read
/// for every instance but `streams`, whose sealed segments are not capped. The loader refuses a value that is not a
/// positive integer.
///
/// `shared` (#249) is the instance's cluster-shared last tier, from `[storage.<instance>.shared]`: the in-memory
/// DHT by default, or an S3-compatible bucket ([SharedTierConfig]).
///
/// `derivesPaths` (#249) is true when the configuration gives no paths of its own, so the node derives them: a
/// [#sharedOnly] configuration (no `[storage.<instance>]` section was written; the synthesized instances also derive
/// their `encrypted` default), or, through [#derivingPaths], a section of another name that sets neither `disk_path`
/// nor `snapshot_path` (N3). Every other shape carries false and is placed exactly as written -- a blank `diskPath`
/// included, which never reads as "derive".
/// `diskPathOperatorSet` (A0b) is true when the operator wrote `disk_path` for this instance. A disk tier that cannot be
/// opened at an operator-set path refuses the boot (the operator named a place and it is not there); at a default or
/// derived path (the synthesized `artifacts` and `content`, an instance that wrote no `disk_path`) the instance degrades
/// to memory plus the shared tier, with a warning, a gauge and a cluster event, because containers legitimately have no
/// mount at the default.
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
                            int prefetchMaxInFlight,
                            SharedTierConfig shared,
                            boolean derivesPaths,
                            int maxBlockBytes,
                            boolean diskPathOperatorSet) {
    /// The default for `prefetchMaxInFlight`; the prefetch manager's own default is pinned to the same number by a test.
    public static final int DEFAULT_PREFETCH_MAX_IN_FLIGHT = 4;
    /// The default for `maxBlockBytes`; the storage instance's own default is pinned to the same number by a test.
    public static final int DEFAULT_MAX_BLOCK_BYTES = 16 * 1024 * 1024;
    /// #912: names the root the default `disk_path`/`snapshot_path` resolve under, instead of `/data/aether`.
    /// A node whose storage sections omit their paths (the shipped image config sets none) otherwise writes
    /// under that fixed, machine-global root; tests and co-located runs point it at their own directory.
    public static final String DEFAULT_ROOT_PROPERTY = "aether.storage.defaultRoot";
    private static final String DEFAULT_ROOT = "/data/aether";

    /// The shape before `diskPathOperatorSet` existed: the disk path is not the operator's (it is a default or a derived
    /// placement), so a disk tier that cannot open degrades instead of refusing the boot.
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
                         boolean prefetch,
                         int prefetchMaxInFlight,
                         SharedTierConfig shared,
                         boolean derivesPaths,
                         int maxBlockBytes) {
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
             prefetchMaxInFlight,
             shared,
             derivesPaths,
             maxBlockBytes,
             false);
    }

    /// The shape before `maxBlockBytes` existed: the limit takes its default.
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
                         boolean prefetch,
                         int prefetchMaxInFlight,
                         SharedTierConfig shared,
                         boolean derivesPaths) {
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
             prefetchMaxInFlight,
             shared,
             derivesPaths,
             DEFAULT_MAX_BLOCK_BYTES);
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

    /// The pre-#249 shape: the shared tier is the in-memory DHT ([SharedTierConfig#dht()]), today's behaviour, and the
    /// paths are used as written ([#derivesPaths] false).
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
             DEFAULT_PREFETCH_MAX_IN_FLIGHT,
             SharedTierConfig.dht(),
             false);
    }

    /// The shape before prefetching existed: `prefetch` is on and the cap takes its default.
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
                         SharedTierConfig shared,
                         boolean derivesPaths) {
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
             DEFAULT_PREFETCH_MAX_IN_FLIGHT,
             shared,
             derivesPaths);
    }

    /// The shape with the `prefetch` switch but before the in-flight cap existed: the cap takes its default; the
    /// shared tier is the in-memory DHT and the paths are used as written.
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
             DEFAULT_PREFETCH_MAX_IN_FLIGHT,
             SharedTierConfig.dht(),
             false);
    }

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
                                 prefetchMaxInFlight,
                                 SharedTierConfig.dht(),
                                 false);
    }

    /// `maxBlockBytes` caps the block size the instance accepts (`[storage.<name>] max_block_bytes`).
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
                                              int prefetchMaxInFlight,
                                              int maxBlockBytes) {
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
                                 prefetchMaxInFlight,
                                 SharedTierConfig.dht(),
                                 false,
                                 maxBlockBytes);
    }

    /// #249: this configuration with `shared` as the last tier of the instance's stack.
    public StorageConfig withShared(SharedTierConfig shared) {
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
                                 prefetchMaxInFlight,
                                 shared,
                                 derivesPaths,
                                 maxBlockBytes,
                                 diskPathOperatorSet);
    }

    /// #249: an instance configured ONLY through `[storage.<name>.shared]`, with no `[storage.<name>]`
    /// section of its own. Paths are left empty to mean "as if the section were absent": the node resolves
    /// them as it would for a missing section -- the synthesized `artifacts`/`content` placement for those
    /// two, their own subdirectories for any other name ([#derivesPaths]).
    public static StorageConfig sharedOnly(SharedTierConfig shared) {
        var defaults = storageConfig();

        return new StorageConfig(defaults.memoryMaxBytes(),
                                 defaults.diskMaxBytes(),
                                 "",
                                 "",
                                 defaults.snapshotMutationThreshold(),
                                 defaults.snapshotMaxInterval(),
                                 defaults.snapshotRetentionCount(),
                                 defaults.walPath(),
                                 defaults.encrypted(),
                                 defaults.promotion(),
                                 shared,
                                 true);
    }

    /// A0b: this configuration with its memory tier sized to `memoryMaxBytes`; every other setting is kept.
    public StorageConfig withMemoryMaxBytes(long memoryMaxBytes) {
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
                                 prefetchMaxInFlight,
                                 shared,
                                 derivesPaths,
                                 maxBlockBytes,
                                 diskPathOperatorSet);
    }

    /// A0b: this configuration with its `disk_path` marked as the operator's own ([#diskPathOperatorSet]): a disk tier
    /// that cannot open there refuses the boot. The loader sets it when the `disk_path` key was written.
    public StorageConfig withOperatorDiskPath() {
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
                                 prefetchMaxInFlight,
                                 shared,
                                 derivesPaths,
                                 maxBlockBytes,
                                 true);
    }

    /// #249 N3: this configuration, marked as giving no paths of its own ([#derivesPaths]); the node derives them.
    public StorageConfig derivingPaths() {
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
                                 prefetchMaxInFlight,
                                 shared,
                                 true,
                                 maxBlockBytes,
                                 diskPathOperatorSet);
    }

    /// #249 N3: this configuration placed on `diskPath` and `snapshotPath`, which are its own from now on
    /// ([#derivesPaths] false). Every other setting is kept.
    public StorageConfig withPaths(String diskPath, String snapshotPath) {
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
                                 prefetchMaxInFlight,
                                 shared,
                                 false,
                                 maxBlockBytes,
                                 false);
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
