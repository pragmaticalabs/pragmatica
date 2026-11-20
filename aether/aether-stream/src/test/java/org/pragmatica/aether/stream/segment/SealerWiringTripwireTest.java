// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.CompressionCodec;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Tripwire for #3515. The sealed-segment path has four silent fallbacks (compress, decompress, unknown codec
/// ordinal, decrypt) that return wrong bytes instead of a typed error. They are unreachable only while the
/// production sink is wired with `CompressionCodec.NONE` and no encryptor. This test reads the configuration of the
/// sink built by the production factory (the one `StreamAssembly` calls), never a sink the test configures itself.
class SealerWiringTripwireTest {
    private static final String MESSAGE = "a codec or encryptor was enabled: fix the four silent fallbacks of #3515 first";

    private StorageSegmentSink productionSink;

    @BeforeEach
    void setUp() {
        var storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(1024L * 1024L)));

        productionSink = StorageSegmentSink.storageSegmentSink(storage, new SegmentIndex(), RefDurability.LIVE);
    }

    @Test
    void productionSink_usesNoCompressionCodec() throws Exception {
        assertThat(field("compressionCodec")).as(MESSAGE).isSameAs(CompressionCodec.NONE);
        assertThat(field("compressionOrdinal")).as(MESSAGE).isEqualTo(0);
    }

    @Test
    void productionSink_hasNoEncryptor() throws Exception {
        assertThat(field("encryptor")).as(MESSAGE).isEqualTo(Option.none());
    }

    /// Second arm: the arm above pins the 3-arg factory `StreamAssembly` calls, so a production source that built the
    /// sink through the 5-arg overload (codec and encryptor chosen by the caller) or the constructor would bypass it.
    @Test
    void productionSources_doNotBuildTheSinkWithACodecOrEncryptor() throws IOException {
        assertThat(bypassingCalls(productionSources())).as(MESSAGE).isEmpty();
    }

    @Test
    void bypassDetector_flagsFiveArgFactoryAndConstructor() {
        var fiveArg = "x = StorageSegmentSink.storageSegmentSink(storage, index, codec, 1, some(enc));";
        var threeArg = "x = StorageSegmentSink.storageSegmentSink(storage, index, durability(a, b));";
        var constructor = "x = new StorageSegmentSink(storage, index, codec, 1, none(), d);";

        assertThat(bypassingCalls(List.of(fiveArg))).hasSize(1);
        assertThat(bypassingCalls(List.of(constructor))).hasSize(1);
        assertThat(bypassingCalls(List.of(threeArg))).isEmpty();
    }

    private static List<String> bypassingCalls(List<String> sources) {
        var found = new ArrayList<String>();

        sources.forEach(source -> collectBypasses(source, found));
        return found;
    }

    private static void collectBypasses(String source, List<String> found) {
        if (source.contains("new StorageSegmentSink(")) {
            found.add("constructor call");
        }

        var marker = "storageSegmentSink(";
        var from = source.indexOf(marker);

        while (from >= 0) {
            var open = from + marker.length();

            if (argumentCount(source, open) == 5) {
                found.add("5-arg factory call");
            }
            from = source.indexOf(marker, open);
        }
    }

    /// Top-level argument count of the call whose opening parenthesis ends just before `open`.
    private static int argumentCount(String source, int open) {
        var depth = 1;
        var commas = 0;
        var empty = true;

        for (var i = open; i < source.length() && depth > 0; i++) {
            var c = source.charAt(i);

            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 1) commas++;
            if (depth > 0 && !Character.isWhitespace(c)) empty = false;
        }
        return empty ? 0 : commas + 1;
    }

    /// Every production source in the repository except the sink's own file (it declares the overloads).
    private static List<String> productionSources() throws IOException {
        var skip = Set.of(".git", "target", ".m2-local", "node_modules");
        var sources = new ArrayList<String>();
        var root = repositoryRoot();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skip.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isProductionSource(file)) {
                    sources.add(Files.readString(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(sources).as("the scan must reach production sources, or it proves nothing").hasSizeGreaterThan(100);
        return sources;
    }

    private static boolean isProductionSource(Path file) {
        var path = file.toString().replace('\\', '/');

        return path.endsWith(".java") && path.contains("/src/main/java/") && !path.endsWith("/segment/StorageSegmentSink.java");
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (!Files.isDirectory(dir.resolve("changelog.d"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    private Object field(String name) throws Exception {
        var field = StorageSegmentSink.class.getDeclaredField(name);

        field.setAccessible(true);
        return field.get(productionSink);
    }
}
