// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// I-O1 (q4 7.1.2): a lifecycle record is never deleted; `FREED` replaces the delete of today's release. The mutation library has no delete,
/// and this scan keeps it that way for every other writer: no production source that names `NodeIdentityKey` may build a [org.pragmatica.cluster.state.kvstore.KVCommand.Mutation]
/// whose replacement is absent, or a `Remove`.
///
/// The scan is a TRIPWIRE on the text, not a proof: a delete assembled across files or through a key variable defeats it. The behavioural
/// half is `LifecycleMutationsTest` (every builder writes a replacement; the applier drops a bare Remove of a `LeaderAuthorized` value). What
/// keeps the scan honest: positive controls (the patterns match a delete and a Remove, and miss a legitimate write) and a coverage check
/// (the scan reaches `LifecycleMutations.java` and more than that file).
class NodeLifecycleNeverDeletedScanTest {
    private static final Pattern ABSENT_REPLACEMENT = Pattern.compile("Mutation\\s*<[^;]*?,\\s*(?:Option\\.)?none\\(\\)\\s*\\)");
    private static final Pattern REMOVE = Pattern.compile("\\bnew\\s+(?:KVCommand\\.)?Remove\\b|\\bKVCommand\\.Remove\\b");

    private static Path root() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    private static List<Path> mainSources() {
        try (var walk = Stream.of("aether", "integrations").map(root()::resolve).filter(Files::isDirectory).flatMap(NodeLifecycleNeverDeletedScanTest::walk)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                       .filter(path -> path.toString().contains("/src/main/java/"))
                       .filter(path -> !path.toString().contains("/target/") && !path.toString().contains("/.m2-local/"))
                       .toList();
        }
    }

    private static Stream<Path> walk(Path directory) {
        try {
            return Files.walk(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void noMainSourceThatNamesTheLifecycleKey_deletesARecord() {
        var offenders = mainSources().stream()
                                     .filter(path -> read(path).contains("NodeIdentityKey"))
                                     .filter(path -> ABSENT_REPLACEMENT.matcher(read(path)).find() || REMOVE.matcher(read(path)).find())
                                     .map(path -> root().relativize(path).toString())
                                     .toList();

        assertThat(offenders).as("sources naming NodeIdentityKey that build a delete").isEmpty();
    }

    @Test
    void theScan_reachesTheLibraryAndTheKeyDeclaration() {
        var named = mainSources().stream().filter(path -> read(path).contains("NodeIdentityKey")).map(path -> path.getFileName().toString()).toList();

        assertThat(named).contains("LifecycleMutations.java", "AetherKey.java", "ReplicaFilter.java");
    }

    @Test
    void thePatterns_matchADeleteAndARemove_andMissALegitimateWrite() {
        assertThat(ABSENT_REPLACEMENT.matcher("new KVCommand.Mutation<>(key, Option.some(existing), Option.none())").find()).isTrue();
        assertThat(ABSENT_REPLACEMENT.matcher("new Mutation<AetherKey, AetherValue>(\n  key,\n  expected,\n  none())").find()).isTrue();
        assertThat(ABSENT_REPLACEMENT.matcher("new KVCommand.Mutation<>(key, Option.none(), Option.some(next))").find()).as("an expected-absent write").isFalse();
        assertThat(ABSENT_REPLACEMENT.matcher("new Mutation<>(key, current.map(AetherValue.class::cast), some(next))").find()).isFalse();
        assertThat(REMOVE.matcher("store.process(new KVCommand.Remove<>(key))").find()).isTrue();
        assertThat(REMOVE.matcher("var removal = new Remove<>(key);").find()).isTrue();
        assertThat(REMOVE.matcher("publish(new ValueRemove<>(key))").find()).as("a notification is not a command").isFalse();
    }
}
