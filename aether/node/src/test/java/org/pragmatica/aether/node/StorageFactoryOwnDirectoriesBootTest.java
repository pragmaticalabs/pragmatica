// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.Main;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #249 N3: an instance other than `artifacts`/`content` whose configuration gives no paths -- `[storage.vault.shared]`
/// alone, or an empty `[storage.vault]` -- gets its own directories beside `artifacts`, as `content` does:
/// `<root>/vault/blocks` and `<root>/vault/snapshots` by default.
/// On the default paths it shared `artifacts`' directories and the boot was refused (`StoragePathsOverlap`). Both
/// shapes run the production path: `Main.resolveStorage`, then `StorageFactory.createAll`, under a default root in a
/// temporary directory.
///
/// Mutation that reddens both: place such an instance on the default paths in `StorageFactory#derivedPaths`.
class StorageFactoryOwnDirectoriesBootTest {
    private static final String NODE_ID = "own-directories-node";

    @TempDir
    Path root;

    private String previousRoot;

    @BeforeEach
    void rootDefaultsInTempDir() {
        previousRoot = System.getProperty(StorageConfig.DEFAULT_ROOT_PROPERTY);
        System.setProperty(StorageConfig.DEFAULT_ROOT_PROPERTY, root.toString());
    }

    @AfterEach
    void restoreDefaultRoot() {
        Option.option(previousRoot)
              .onPresent(previous -> System.setProperty(StorageConfig.DEFAULT_ROOT_PROPERTY, previous))
              .onEmpty(() -> System.clearProperty(StorageConfig.DEFAULT_ROOT_PROPERTY));
    }

    private static Map<String, StorageConfig> loaded(String toml) {
        return ConfigLoader.loadFromString(toml)
                           .fold(cause -> fail(cause.message()), config -> config.storage());
    }

    /// The storage map `Main` hands the node for `toml`, after `createAll` booted it (past `StoragePathsOverlap`).
    private static Map<String, StorageConfig> bootedThroughMain(String toml, String... instances) {
        var storage = Main.resolveStorage(Option.some(ConfigLoader.loadFromString(toml)
                                                                  .fold(cause -> fail(cause.message()), config -> config)));
        var setups = StorageFactory.createAll(storage, NODE_ID, Option.none(), Option.none(), StorageConfig.storageConfig())
                                   .fold(cause -> fail("must boot: " + cause.message()), built -> built);

        assertThat(setups).containsKeys(instances);

        return storage;
    }

    private void assertBootsInItsOwnDirectories(String toml) {
        var storage = bootedThroughMain(toml, "vault", "artifacts", "content");

        assertThat(storage.get("vault").diskPath()).isEqualTo(root.resolve("vault").resolve("blocks").toString());
        assertThat(storage.get("vault").snapshotPath()).isEqualTo(root.resolve("vault").resolve("snapshots").toString());
        assertThat(Files.isDirectory(root.resolve("vault").resolve("blocks"))).isTrue();
        assertThat(storage.get("artifacts")).as("artifacts is not configured here and stays synthesized").isNull();
    }

    @Test
    void sharedOnlyVault_bootsInItsOwnDirectories() {
        assertBootsInItsOwnDirectories("[storage.vault.shared]\nkind = \"dht\"\n");
    }

    @Test
    void emptyVaultSection_bootsInItsOwnDirectories() {
        assertBootsInItsOwnDirectories("[storage.vault]\n");
    }

    /// A path-less instance follows `artifacts` when its disk path is moved, as `content` does. Mutation that reddens
    /// it: derive from the default disk path instead of the resolved `artifacts`.
    @Test
    void movedArtifacts_movesVaultWithIt() {
        var moved = root.resolve("moved");
        var storage = bootedThroughMain("[storage.artifacts]\ndisk_path = \"" + moved.resolve("storage") + "\"\nsnapshot_path = \""
                                        + moved.resolve("metadata-snapshots") + "\"\n[storage.vault]\n",
                                        "vault", "artifacts", "content");

        assertThat(storage.get("vault").diskPath()).isEqualTo(moved.resolve("vault").resolve("blocks").toString());
        assertThat(storage.get("vault").snapshotPath()).isEqualTo(moved.resolve("vault").resolve("snapshots").toString());
    }

    /// Two path-less instances get one directory each. Mutation that reddens it: a data directory not named after the
    /// instance.
    @Test
    void twoPathlessInstances_bootWithoutColliding() {
        var storage = bootedThroughMain("[storage.vault]\n[storage.ledger.shared]\nkind = \"dht\"\n", "vault", "ledger", "artifacts", "content");

        assertThat(storage.get("ledger").diskPath()).isEqualTo(root.resolve("ledger").resolve("blocks").toString());
        assertThat(storage.get("vault").diskPath()).isEqualTo(root.resolve("vault").resolve("blocks").toString());
    }

    /// A section that keeps its other settings: only the paths are derived.
    @Test
    void vaultSectionWithoutPaths_keepsItsOtherSettings() {
        var vault = Main.resolveStorage(Option.some(ConfigLoader.loadFromString("[storage.vault]\nmemory_max_bytes = 1234\n")
                                                                .fold(cause -> fail(cause.message()), config -> config)))
                        .get("vault");

        assertThat(vault.memoryMaxBytes()).isEqualTo(1234);
        assertThat(vault.diskPath()).isEqualTo(root.resolve("vault").resolve("blocks").toString());
    }

    /// The node never builds an underived map: the loaded map, not passed through `Main.resolveStorage`, is refused.
    @Test
    void loadedMapWithoutDerivation_isRefused() {
        StorageFactory.createAll(loaded("[storage.vault]\n"), NODE_ID, Option.none(), Option.none(), StorageConfig.storageConfig())
                      .onSuccess(setups -> fail("an underived map must not be built: " + setups.keySet()))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(StorageFactory.UnderivedStoragePaths.class));
    }

    /// An explicit `artifacts` section without paths keeps the default paths, as before N3.
    @Test
    void emptyArtifactsSection_keepsTheDefaultPaths() {
        var artifacts = loaded("[storage.artifacts]\n").get("artifacts");

        assertThat(artifacts.derivesPaths()).isFalse();
        assertThat(artifacts.diskPath()).isEqualTo(root.resolve("storage").toString());
    }
}
