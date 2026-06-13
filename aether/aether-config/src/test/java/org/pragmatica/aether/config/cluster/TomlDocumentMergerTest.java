// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.toml.TomlDocument;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class TomlDocumentMergerTest {

    @Nested
    class MergeSections {

        @Test
        void merge_baseKeysPreservedOverlayWins_conflictResolved() {
            var base = new TomlDocument(Map.of("server", Map.of("host", "localhost", "port", 8080)));
            var overlay = new TomlDocument(Map.of("server", Map.of("port", 9090)));

            var merged = TomlDocumentMerger.merge(base, overlay);

            assertThat(merged.getString("server", "host").or("")).isEqualTo("localhost");
            assertThat(merged.getInt("server", "port").or(0)).isEqualTo(9090);
        }

        @Test
        void merge_sectionsOnlyInBase_preserved() {
            var base = new TomlDocument(Map.of("database", Map.of("url", "jdbc:pg://localhost")));
            var overlay = new TomlDocument(Map.of("server", Map.of("port", 8080)));

            var merged = TomlDocumentMerger.merge(base, overlay);

            assertThat(merged.hasSection("database")).isTrue();
            assertThat(merged.getString("database", "url").or("")).isEqualTo("jdbc:pg://localhost");
            assertThat(merged.hasSection("server")).isTrue();
        }

        @Test
        void merge_sectionsOnlyInOverlay_added() {
            var base = new TomlDocument(Map.of("server", Map.of("port", 8080)));
            var overlay = new TomlDocument(Map.of("logging", Map.of("level", "DEBUG")));

            var merged = TomlDocumentMerger.merge(base, overlay);

            assertThat(merged.hasSection("logging")).isTrue();
            assertThat(merged.getString("logging", "level").or("")).isEqualTo("DEBUG");
            assertThat(merged.hasSection("server")).isTrue();
        }
    }

    @Nested
    class MergeTableArrays {

        @Test
        void merge_tableArraysReplacedWholesale_overlayWins() {
            var baseArray = List.of(Map.<String, Object>of("name", "base-entry"));
            var overlayArray = List.of(Map.<String, Object>of("name", "overlay-1"),
                                       Map.<String, Object>of("name", "overlay-2"));

            var base = new TomlDocument(Map.of("", Map.of()), Map.of("products", baseArray));
            var overlay = new TomlDocument(Map.of("", Map.of()), Map.of("products", overlayArray));

            var merged = TomlDocumentMerger.merge(base, overlay);

            assertThat(merged.getTableArray("products").or(List.of())).hasSize(2);
            assertThat(merged.getTableArray("products").or(List.of()).getFirst().get("name")).isEqualTo("overlay-1");
        }

        @Test
        void merge_tableArraysOnlyInBase_preserved() {
            var baseArray = List.of(Map.<String, Object>of("name", "base-entry"));

            var base = new TomlDocument(Map.of("", Map.of()), Map.of("items", baseArray));
            var overlay = new TomlDocument(Map.of("", Map.of()), Map.of());

            var merged = TomlDocumentMerger.merge(base, overlay);

            assertThat(merged.hasTableArray("items")).isTrue();
            assertThat(merged.getTableArray("items").or(List.of())).hasSize(1);
        }
    }
}
