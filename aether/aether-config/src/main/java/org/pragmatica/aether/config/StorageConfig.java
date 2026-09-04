// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
public record StorageConfig(long memoryMaxBytes,
                            long diskMaxBytes,
                            String diskPath,
                            String snapshotPath,
                            int snapshotMutationThreshold,
                            String snapshotMaxInterval,
                            int snapshotRetentionCount,
                            String walPath,
                            boolean encrypted) {
    public static StorageConfig storageConfig() {
        return new StorageConfig(256 * 1024 * 1024,
                                 10L * 1024 * 1024 * 1024,
                                 "/data/aether/storage",
                                 "/data/aether/metadata-snapshots",
                                 1000,
                                 "60s",
                                 5,
                                 "",
                                 false);
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
                                 false);
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
                                 false);
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
                                 encrypted);
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
