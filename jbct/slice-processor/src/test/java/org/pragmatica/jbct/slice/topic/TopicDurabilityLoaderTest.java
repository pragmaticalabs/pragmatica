// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.topic;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.slice.topic.TopicDurabilityLoader.TopicDurabilityIndex;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the compile-time half of the D5 type-level-honesty rule (#386 spec §3/D5): the
/// slice-processor's only source of a topic's declared durability is the slice's `resources.toml`,
/// because `Topic<T>` carries no durability and `TopicConfig` is bound at runtime.
class TopicDurabilityLoaderTest {

    @TempDir
    Path tempDir;

    private Path writeResources(String content) throws IOException {
        var path = tempDir.resolve(TopicDurabilityLoader.CONFIG_FILE);
        Files.writeString(path, content);
        return path;
    }

    /// Unwraps eagerly so a regression that turns every load into a failure shows up as a failed
    /// test rather than as an `onSuccess` block that silently never runs.
    private TopicDurabilityIndex loadIndex(Path path) {
        return TopicDurabilityLoader.load(path)
                                    .fold(cause -> {
                                              throw new AssertionError("expected a loaded index, got: " + cause.message());
                                          },
                                          index -> index);
    }

    private String failureMessage(Path path) {
        return TopicDurabilityLoader.load(path)
                                    .fold(Cause::message,
                                          index -> {
                                              throw new AssertionError("expected a failure, got an index: " + index);
                                          });
    }

    @Nested
    class Declaration {

        @Test
        void isDurable_true_whenSectionDeclaresDurable() throws IOException {
            var path = writeResources("""
                [order-events]
                topic_name = "order-events"
                durability = "durable"
                partitions = 4
                replicas = 2
                min_sync_replicas = 2
                """);

            assertThat(loadIndex(path).isDurable("order-events")).isTrue();
        }

        @Test
        void isDurable_false_whenSectionDeclaresEphemeral() throws IOException {
            var path = writeResources("""
                [order-events]
                topic_name = "order-events"
                durability = "ephemeral"
                """);

            assertThat(loadIndex(path).isDurable("order-events")).isFalse();
        }

        /// The ephemeral default (spec §3): a topic section with no `durability` key is ephemeral,
        /// so the 2-arg MessageContext shape on it is a lie the processor must reject.
        @Test
        void isDurable_false_whenSectionOmitsDurability() throws IOException {
            var path = writeResources("""
                [click-events]
                topic_name = "click-events"
                """);

            assertThat(loadIndex(path).isDurable("click-events")).isFalse();
        }

        @Test
        void isDurable_false_whenSectionAbsentEntirely() throws IOException {
            var path = writeResources("""
                [database]
                type = "POSTGRESQL"
                """);

            assertThat(loadIndex(path).isDurable("order-events")).isFalse();
        }

        /// The legacy dotted section form (`config = "messaging.order-events"`) addresses a nested
        /// TOML table and must resolve identically to a top-level section.
        @Test
        void isDurable_true_forDottedLegacySectionName() throws IOException {
            var path = writeResources("""
                [messaging.order-events]
                topic_name = "order-events"
                durability = "durable"
                replicas = 2
                min_sync_replicas = 2
                """);

            assertThat(loadIndex(path).isDurable("messaging.order-events")).isTrue();
        }

        /// Durability is a declared enum, not free text: an unrecognized value must not read as
        /// durable, because the runtime binder would reject it rather than select the durable tier.
        @Test
        void isDurable_false_forUnrecognizedDurabilityValue() throws IOException {
            var path = writeResources("""
                [order-events]
                durability = "DURABLE-ish"
                """);

            assertThat(loadIndex(path).isDurable("order-events")).isFalse();
        }

        /// Matched case-insensitively, as the runtime TOML binder resolves the enum constant.
        @Test
        void isDurable_true_forCaseInsensitiveDurableValue() throws IOException {
            var path = writeResources("""
                [order-events]
                durability = "DURABLE"
                """);

            assertThat(loadIndex(path).isDurable("order-events")).isTrue();
        }

        /// One slice may declare both tiers; the index must not smear one section's durability
        /// across the others.
        @Test
        void isDurable_isPerSection_whenTiersAreMixed() throws IOException {
            var path = writeResources("""
                [order-events]
                durability = "durable"
                replicas = 2
                min_sync_replicas = 2

                [click-events]
                durability = "ephemeral"

                [audit-events]
                topic_name = "audit-events"
                """);

            var index = loadIndex(path);

            assertThat(index.isDurable("order-events")).isTrue();
            assertThat(index.isDurable("click-events")).isFalse();
            assertThat(index.isDurable("audit-events")).isFalse();
        }
    }

    @Nested
    class Absence {

        /// A missing `resources.toml` is a failure, not an empty index: the caller must be able to
        /// distinguish "declared ephemeral" from "could not be determined" to apply the D5
        /// fail-closed policy at one visible place. The message must name absence specifically —
        /// reporting a missing file as a parse error sends the reader hunting for a syntax fault
        /// that isn't there.
        @Test
        void load_fails_whenFileMissing() {
            var path = tempDir.resolve("no-such-resources.toml");

            assertThat(TopicDurabilityLoader.load(path).isFailure()).isTrue();
            assertThat(failureMessage(path)).contains("not found");
        }

        @Test
        void load_fails_whenFileIsNotParseableToml() throws IOException {
            var path = writeResources("this is not = = toml [[[");

            assertThat(TopicDurabilityLoader.load(path).isFailure()).isTrue();
            assertThat(failureMessage(path)).contains("Failed to parse");
        }

        @Test
        void load_fails_whenPathIsADirectory() {
            assertThat(TopicDurabilityLoader.load(tempDir).isFailure()).isTrue();
            assertThat(failureMessage(tempDir)).contains("not found");
        }

        @Test
        void load_succeeds_whenFileIsEmpty() throws IOException {
            var path = writeResources("");

            assertThat(loadIndex(path).isDurable("order-events")).isFalse();
        }
    }
}
