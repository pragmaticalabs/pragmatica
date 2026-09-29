// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #927 — every permitted variant of the sealed [ClusterEvent] must have a construction site in
/// production source, or be on [#FORWARD_DECLARED] with the reason it is kept. Three variants sat in
/// the sealed set for months with no producer — pinned on the wire, handled in every exhaustive
/// switch, documented as emitted — and nothing flagged them, because nothing in the type system or the
/// tests can see an absent producer. This is the mechanism the ticket asked for instead of review.
///
/// The census is two-sided so the allow-list cannot go stale: a listed variant that GAINS a producer
/// fails too, naming the entry to remove (and the "not currently produced" notes to delete).
///
/// Scanned: every `*.java` under `aether/**/src/main/java` (test fixtures and `target/` excluded), matching both construction forms
/// (`new X(` and `new ClusterEvent.X(`) — the ticket's first census matched only the bare form and
/// reported two live variants as dead. `ClusterEvent.java` itself is excluded: its only constructions
/// are the `withDetail` copies, which need an existing instance and so produce nothing.
class ClusterEventProducerCensusTest {
    /// Variants deliberately kept without a producer, with the reason. Wire tags pin each type, and
    /// retiring a tag is a codec-table change rather than a source edit.
    private static final Map<String, String> FORWARD_DECLARED = Map.of("BackupCreated", "backup API removed (#676); wire tag 258",
                                                                       "BackupRestored", "backup API removed (#676); wire tag 259",
                                                                       "CommunityScaleRequest", "never wired (#927); wire tag 263",
                                                                       "StreamRegistered", "stream-lifecycle emission never wired (#927); wire tag 288",
                                                                       "StreamDeleted", "stream-lifecycle emission never wired (#927); wire tag 286");

    @Test
    void everyClusterEventVariant_isProducedInProductionSource_orForwardDeclared() {
        var sources = productionSources();
        var unproduced = variants().stream()
                                   .filter(variant -> !FORWARD_DECLARED.containsKey(variant))
                                   .filter(variant -> !isConstructed(variant, sources))
                                   .toList();

        assertThat(unproduced).as("ClusterEvent variants with no construction site in aether/**/src/main/java. Wire a "
                                  + "producer, remove the variant, or add it to FORWARD_DECLARED with the reason and "
                                  + "mark its doc comment 'Not currently produced'.")
                              .isEmpty();
    }

    @Test
    void forwardDeclaredVariants_stillHaveNoProducer_soTheListCannotGoStale() {
        var sources = productionSources();
        var nowProduced = FORWARD_DECLARED.keySet()
                                          .stream()
                                          .filter(variant -> isConstructed(variant, sources))
                                          .sorted()
                                          .toList();

        assertThat(nowProduced).as("FORWARD_DECLARED variants that now have a producer. Remove them from the list and "
                                   + "delete their 'Not currently produced' notes (ClusterEvent.java, management-api.md).")
                               .isEmpty();
    }

    /// Positive control: the census must reach the real corpus and see real producers, or an empty or
    /// misplaced scan would pass both tests above vacuously.
    @Test
    void census_readsTheProductionCorpus_andFindsKnownProducers() {
        var sources = productionSources();

        assertThat(sources.size()).as("production source files scanned")
                                  .isGreaterThan(500);
        assertThat(isConstructed("LeaderLost", sources)).as("LeaderLost (bare `new X(` form)")
                                                        .isTrue();
        assertThat(isConstructed("ThresholdBreached", sources)).as("ThresholdBreached (qualified `new ClusterEvent.X(` form)")
                                                               .isTrue();
        assertThat(FORWARD_DECLARED.keySet()).as("every forward-declared name is a real variant")
                                             .allMatch(variants()::contains);
    }

    private static List<String> variants() {
        return Arrays.stream(ClusterEvent.class.getPermittedSubclasses())
                     .filter(type -> type != ExtendedEvent.class)
                     .map(Class::getSimpleName)
                     .toList();
    }

    private static boolean isConstructed(String variant, List<String> sources) {
        var construction = Pattern.compile("new\\s+(ClusterEvent\\s*\\.\\s*)?" + variant + "\\s*\\(|\\b" + variant + "::new\\b");

        return sources.stream()
                      .anyMatch(source -> construction.matcher(source)
                                                      .find());
    }

    private static List<String> productionSources() {
        var aetherRoot = moduleRoot().getParent();

        assertThat(aetherRoot.resolve("node/src/main/java")).isDirectory();

        try (var paths = Files.walk(aetherRoot)) {
            return paths.filter(path -> path.toString()
                                            .endsWith(".java"))
                        .filter(path -> path.toString()
                                            .contains("/src/main/java/"))
                        .filter(path -> !path.toString()
                                             .contains("/src/test/") && !path.toString()
                                                                             .contains("/target/"))
                        .filter(path -> !path.getFileName()
                                             .toString()
                                             .equals("ClusterEvent.java"))
                        .map(ClusterEventProducerCensusTest::readFile)
                        .collect(Collectors.toList());
        } catch (IOException e) {
            throw new AssertionError("Cannot walk production sources under " + aetherRoot, e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    /// Derived from this class's location (`<module>/target/test-classes`), not the working directory,
    /// so the census behaves the same under Surefire, an IDE runner and an aggregator build.
    private static Path moduleRoot() {
        try {
            return Path.of(ClusterEventProducerCensusTest.class.getProtectionDomain()
                                                               .getCodeSource()
                                                               .getLocation()
                                                               .toURI())
                       .getParent()
                       .getParent();
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module root", e);
        }
    }
}
