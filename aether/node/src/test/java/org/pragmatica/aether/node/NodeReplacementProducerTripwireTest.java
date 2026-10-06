// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 TRIPWIRE. Part D ships the NodeReplacement pairing record and the three guards that honour it, but
/// NOTHING in production commits a pairing yet — the phase-driving reconciler is part E. Until then every guard
/// is inert and the cluster behaves exactly as before D (the guard tests pin that control).
///
/// This test asserts that CURRENT state: no production source constructs a `NodeReplacementValue`. It reddens
/// the moment part E adds the producer. When it does: DELETE THIS TEST, and make sure part E carries the
/// end-to-end pins this record exists for (Ember, multi-node): replace a core at 3 cores with the voter set at 3
/// on every sample and no quorum loss; the replacement is never reaped as surplus while paired; replace a
/// worker in its community with the surge worker kept and the original retired; a pairing that never joins ends
/// ROLLED_BACK. Those cannot be written in D — with no producer they would pass vacuously, which is worse than
/// not having them.
class NodeReplacementProducerTripwireTest {
    private static final String CONSTRUCTION = "new NodeReplacementValue(";
    private static final String DECLARATION = "record NodeReplacementValue(";

    @Test
    void nodeReplacementValue_hasNoProductionProducerYet_deleteMeWhenPartELands() {
        var sources = productionSources();

        // Positive control: the scan reaches the module that declares the record.
        assertThat(sources.stream().anyMatch(path -> contains(path, DECLARATION)))
            .as("the scan of %d production sources under aether/ never found the record declaration — it is blind",
                sources.size())
            .isTrue();
        assertThat(sources.stream().filter(path -> contains(path, CONSTRUCTION)).toList())
            .as("""
                A production producer of NodeReplacementValue exists — #1543 part E has landed. DELETE \
                NodeReplacementProducerTripwireTest and confirm part E carries the end-to-end pins named in its \
                docstring.""")
            .isEmpty();
    }

    private static List<Path> productionSources() {
        var aether = Path.of("").toAbsolutePath().getParent();

        try (Stream<Path> walk = Files.walk(aether)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                       .filter(path -> path.toString().contains("/src/main/java/"))
                       .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean contains(Path path, String text) {
        try {
            return Files.readString(path).contains(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
