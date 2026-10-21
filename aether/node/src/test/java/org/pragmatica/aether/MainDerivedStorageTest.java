// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #249 N1: `Main` hands the node a storage map whose shared-only instances are already derived, and every reader in
/// the node reads that map. A shared-only `[storage.artifacts.shared]` used to leave `artifacts` with a blank disk path
/// in the map the node was handed, so the stream segment and WAL directories, which hang off it, resolved under the
/// process's working directory. Nothing here creates a directory.
///
/// Mutation that reddens every test: `Main#resolveStorage` hands the node the loaded map without
/// `StorageFactory.derivedStorage`.
class MainDerivedStorageTest {
    private static final NodeId SELF = new NodeId("derived-storage-node");

    private static Map<String, StorageConfig> storageOf(String toml) {
        return Main.resolveStorage(Option.some(ConfigLoader.loadFromString(toml)
                                                           .fold(cause -> fail(cause.message()), config -> config)));
    }

    private static Map<String, StorageConfig> noStorageSection() {
        return Main.resolveStorage(Option.<AetherConfig> none());
    }

    @Test
    void sharedOnlyArtifacts_streamSegmentsAndWal_sitUnderTheNoSectionDefault() {
        var sharedOnly = storageOf("[storage.artifacts.shared]\nkind = \"dht\"\n");

        var segments = AetherNode.streamDataDir(sharedOnly, SELF);
        var wal = AetherNode.streamWalBaseDir(sharedOnly, SELF);

        assertThat(segments).isEqualTo(AetherNode.streamDataDir(noStorageSection(), SELF));
        assertThat(segments.isAbsolute()).as("stream segments at %s", segments).isTrue();
        assertThat(wal).isEqualTo(AetherNode.streamWalBaseDir(noStorageSection(), SELF));
        assertThat(wal.isAbsolute()).as("stream WAL at %s", wal).isTrue();
    }

    /// R10: the derived WAL is `wal` under the stream segment directory, spelled out here rather than taken from the
    /// node, so a change to the node's subdirectory shows. Mutation that reddens it: another subdirectory name in
    /// `AetherNode#streamWalBaseDir(Map, NodeId)`.
    @Test
    void sharedOnlyArtifacts_walIsTheWalSubdirectoryOfTheDefaultStreamSegments() {
        var expected = Path.of(StorageConfig.storageConfig().diskPath())
                           .resolveSibling("stream-segments")
                           .resolve(SELF.id())
                           .resolve("wal");

        assertThat(AetherNode.streamWalBaseDir(storageOf("[storage.artifacts.shared]\nkind = \"dht\"\n"), SELF)).isEqualTo(expected);
    }

    /// R07/R14: with `[storage.encryption]`, a shared-only `artifacts` or `content` is encrypted, as its synthesized
    /// default would be; without it, it is not. The flag is the one `Main` computes and hands the one derivation
    /// site. Mutations that redden it: `Main#resolveStorage` passes `false`; `StorageFactory#derivedStorage` ignores
    /// its `encrypted`.
    @Test
    void sharedOnlyArtifactsAndContent_areEncrypted_exactlyWhenTheKeyringSectionIsPresent() {
        var shared = "[storage.artifacts.shared]\nkind = \"dht\"\n[storage.content.shared]\nkind = \"dht\"\n";
        var withKeyring = storageOf("[storage.encryption]\nactive_key_id = \"k1\"\n[storage.encryption.keys]\nk1 = \"${secrets:k1}\"\n"
                                    + shared);
        var withoutKeyring = storageOf(shared);

        assertThat(withKeyring.get("artifacts").encrypted()).isTrue();
        assertThat(withKeyring.get("content").encrypted()).isTrue();
        assertThat(withoutKeyring.get("artifacts").encrypted()).isFalse();
        assertThat(withoutKeyring.get("content").encrypted()).isFalse();
    }

    /// B07: a shared-only `content` is placed beside the DERIVED `artifacts`, never beside its blank raw path. Mutation
    /// that also reddens it: `StorageFactory#derivedPaths` places `content` beside the raw map's `artifacts`.
    @Test
    void sharedOnlyContent_isPlacedBesideTheDerivedArtifacts() {
        var storage = storageOf("[storage.artifacts.shared]\nkind = \"dht\"\n[storage.content.shared]\nkind = \"dht\"\n");
        var artifacts = Path.of(storage.get("artifacts").diskPath());

        assertThat(artifacts).isEqualTo(Path.of(StorageConfig.storageConfig().diskPath()));
        assertThat(Path.of(storage.get("content").diskPath())).isEqualTo(artifacts.resolveSibling("content").resolve("blocks"))
                                                            .isAbsolute();
        assertThat(Path.of(storage.get("content").snapshotPath())).isEqualTo(artifacts.resolveSibling("content")
                                                                                      .resolve("snapshots"));
    }
}
