// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.config.StorageConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// #1276: storage configuration for node tests, rooted in a per-test directory instead of
/// [StorageConfig#storageConfig()]'s machine-global `/data/aether/...` default. On a host where `/data`
/// is writable, tests that resolved that default shared persistent state across runs, trees and
/// branches: one run's encryption marker made every later keyring-less run refuse to boot.
///
/// The roots built here sit UNDER A REGULAR FILE, so no directory can be created beneath them, not
/// even by root. That reproduces exactly what these tests always got on CI and on laptops, where
/// `/data` is not writable: every disk tier degrades to memory + DHT (`handleDiskTierUnavailable`),
/// and nothing touches a shared filesystem.
public sealed interface HermeticStorage {
    /// Name of the regular file the uncreatable storage root is placed under.
    String BLOCKER_FILE = "not-a-directory";

    /// Default-shaped [StorageConfig] with its disk and snapshot paths under `root`.
    static StorageConfig storageConfigAt(Path root, boolean encrypted) {
        var defaults = StorageConfig.storageConfig();

        return StorageConfig.storageConfig(defaults.memoryMaxBytes(),
                                           defaults.diskMaxBytes(),
                                           root.resolve("storage").toString(),
                                           root.resolve("metadata-snapshots").toString(),
                                           defaults.snapshotMutationThreshold(),
                                           defaults.snapshotMaxInterval(),
                                           defaults.snapshotRetentionCount(),
                                           defaults.walPath(),
                                           encrypted);
    }

    /// A storage root inside `tempDir` beneath which no directory can be created: `tempDir` gains a
    /// regular file, and the root is a path under it.
    static Path uncreatableRootIn(Path tempDir) {
        var blocker = tempDir.resolve(BLOCKER_FILE);

        if (!Files.exists(blocker)) {
            writeBlocker(blocker);
        }

        return blocker.resolve("aether");
    }

    private static void writeBlocker(Path blocker) {
        try {
            Files.writeString(blocker, "#1276: storage roots under this file cannot be created");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The synthesis defaults for `StorageFactory.createAll`'s `defaults` overload: the production
    /// default's sizes and intervals with its disk paths under an uncreatable root in `tempDir`.
    static StorageConfig synthesisDefaultsIn(Path tempDir) {
        return storageConfigAt(uncreatableRootIn(tempDir), false);
    }

    /// The `storageConfig` map for an [AetherNodeConfig] that boots through `AetherNode`: an explicit
    /// `artifacts` instance under an uncreatable root in `tempDir`. `content` and the stream data dir are
    /// derived by the node as siblings of the artifacts disk path, so they land under the same root.
    ///
    /// `encrypted` stands in for the synthesized instance's `encrypted = keyring present`: an explicit
    /// section is taken as written, so a test that configures `[storage.encryption]` passes `true` to
    /// keep the artifacts instance covered exactly as the synthesized one would have been.
    static Map<String, StorageConfig> nodeStorageIn(Path tempDir, boolean encrypted) {
        return Map.of("artifacts", storageConfigAt(uncreatableRootIn(tempDir), encrypted));
    }

    record unused() implements HermeticStorage {}
}
