// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.SharedTierConfig;
import org.pragmatica.aether.config.StorageConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The prefetch settings (`prefetch`, `prefetch_max_in_flight`) and the other per-instance settings survive every way a
/// `StorageConfig` is rebuilt -- `withShared`, `withPaths`, `derivingPaths`, the shared-only and path-derivation steps of
/// `StorageFactory`, the synthesized `artifacts`/`content` copies -- together with the shared tier. None of these was pinned
/// for the prefetch fields without a MinIO container.
class StorageConfigPrefetchSettingsKeptTest {
    @TempDir
    Path tempDir;

    private static final SharedTierConfig S3 = new SharedTierConfig.S3("http://127.0.0.1:9000", "us-east-1", "b", "p/", true,
                                                                       new SharedTierConfig.Credentials.Environment(), 4, timeSpan(5).seconds());

    private StorageConfig defaults() {
        return StorageConfig.storageConfig(8L << 20, 64L << 20, tempDir.resolve("s").toString(), tempDir.resolve("m").toString(), 1000, "60s", 5);
    }

    private StorageConfig custom() {
        return StorageConfig.storageConfig(1, 2, tempDir.resolve("d").toString(), tempDir.resolve("snap").toString(), 7, "9s", 3, "wal", true, false, false, 2);
    }

    private StorageConfig offDefaults() {
        return StorageConfig.storageConfig(8L << 20, 64L << 20, tempDir.resolve("s").toString(), tempDir.resolve("m").toString(), 1000, "60s", 5, "", false, false, false, 3);
    }

    @Test
    void loaded_prefetchOff_cap2_withS3_keepsAll_throughDerivation() {
        var toml = """
            [storage.content]
            prefetch = false
            prefetch_max_in_flight = 2
            promotion = false
            [storage.content.shared]
            kind = "s3"
            endpoint = "http://127.0.0.1:9000"
            region = "us-east-1"
            bucket = "b"
            credentials = "env"
            """;
        var loaded = ConfigLoader.loadFromString(toml).fold(c -> fail(c.message()), c -> c).storage().get("content");

        assertThat(loaded.prefetch()).isFalse();
        assertThat(loaded.prefetchMaxInFlight()).isEqualTo(2);
        assertThat(loaded.promotion()).isFalse();
        assertThat(loaded.shared()).isInstanceOf(SharedTierConfig.S3.class);

        var placed = StorageFactory.withDerivedPaths(Map.of("content", loaded), defaults(), false).get("content");

        assertThat(placed).isEqualTo(loaded);
    }

    @Test
    void pathlessSectionOfAnotherName_withS3_keepsPrefetchSettings() {
        var base = custom().withShared(S3).derivingPaths();
        var placed = StorageFactory.withDerivedPaths(Map.of("vault", base), defaults(), false).get("vault");

        assertThat(placed.shared()).isEqualTo(S3);
        assertThat(placed.prefetch()).isFalse();
        assertThat(placed.prefetchMaxInFlight()).isEqualTo(2);
        assertThat(placed.promotion()).isFalse();
        assertThat(placed.derivesPaths()).isFalse();
    }

    @Test
    void sharedOnly_artifactsAndContent_withS3_takeTheDefaultsPrefetch_andTheS3Tier() {
        var placed = StorageFactory.withDerivedPaths(Map.of("artifacts", StorageConfig.sharedOnly(S3), "content", StorageConfig.sharedOnly(S3)), defaults(), false);

        for (var name : new String[]{"artifacts", "content"}) {
            assertThat(placed.get(name).shared()).as(name).isEqualTo(S3);
            assertThat(placed.get(name).prefetch()).as(name).isTrue();
            assertThat(placed.get(name).prefetchMaxInFlight()).as(name).isEqualTo(4);
        }
    }

    @Test
    void sharedOnly_instanceOfAnotherName_keepsTheShippedPrefetchDefaults_andTheS3Tier() {
        var placed = StorageFactory.withDerivedPaths(Map.of("vault", StorageConfig.sharedOnly(S3)), defaults(), false).get("vault");

        assertThat(placed.shared()).isEqualTo(S3);
        assertThat(placed.prefetch()).as("a shared-only section does not switch prefetching off").isTrue();
        assertThat(placed.prefetchMaxInFlight()).isEqualTo(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
        assertThat(placed.derivesPaths()).isFalse();
    }

    @Test
    void withShared_thenWithPaths_thenDerivingPaths_neverDropsEitherSide() {
        var c = custom().withShared(S3).withPaths("a", "b").derivingPaths().withShared(S3);

        assertThat(c.prefetch()).isFalse();
        assertThat(c.prefetchMaxInFlight()).isEqualTo(2);
        assertThat(c.promotion()).isFalse();
        assertThat(c.shared()).isEqualTo(S3);
        assertThat(c.diskPath()).isEqualTo("a");
        assertThat(c.derivesPaths()).isTrue();
        assertThat(c.encrypted()).isTrue();
        assertThat(c.walPath()).isEqualTo("wal");
        assertThat(custom().withPaths("x", "y").derivesPaths()).isFalse();
        assertThat(custom().withPaths("x", "y").encrypted()).isTrue();
        assertThat(custom().withPaths("x", "y").walPath()).isEqualTo("wal");
        assertThat(custom().withPaths("x", "y").prefetch()).isFalse();
        assertThat(custom().withPaths("x", "y").prefetchMaxInFlight()).isEqualTo(2);
        assertThat(custom().withShared(S3).prefetch()).isFalse();
        assertThat(custom().withShared(S3).prefetchMaxInFlight()).isEqualTo(2);
        assertThat(custom().withShared(S3).walPath()).isEqualTo("wal");
        assertThat(custom().derivingPaths().prefetch()).isFalse();
        assertThat(custom().derivingPaths().prefetchMaxInFlight()).isEqualTo(2);
        assertThat(custom().withShared(S3).derivingPaths().shared()).isEqualTo(S3);
    }

    @Test
    void sharedOnly_artifactsAndContent_takeTheSuppliedDefaultsPrefetchPromotionAndCap() {
        var placed = StorageFactory.withDerivedPaths(Map.of("artifacts", StorageConfig.sharedOnly(S3), "content", StorageConfig.sharedOnly(S3)), offDefaults(), false);

        for (var name : new String[]{"artifacts", "content"}) {
            assertThat(placed.get(name).shared()).as(name).isEqualTo(S3);
            assertThat(placed.get(name).prefetch()).as(name).isFalse();
            assertThat(placed.get(name).prefetchMaxInFlight()).as(name).isEqualTo(3);
            assertThat(placed.get(name).promotion()).as(name).isFalse();
        }
    }

    @Test
    void elevenArgShape_isDhtAndNotDerived_keepingThePrefetchSwitch() {
        var c = new StorageConfig(1, 2, "d", "s", 7, "9s", 3, "w", true, false, false);

        assertThat(c.prefetch()).isFalse();
        assertThat(c.prefetchMaxInFlight()).isEqualTo(4);
        assertThat(c.shared()).isEqualTo(SharedTierConfig.dht());
        assertThat(c.derivesPaths()).isFalse();
        assertThat(c.encrypted()).isTrue();
    }
}
