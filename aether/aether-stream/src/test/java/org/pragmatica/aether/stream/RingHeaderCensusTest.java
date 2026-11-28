// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.testlogging.SourceWalk;

import static org.assertj.core.api.Assertions.assertThat;

/// Test 2 and the text census (M-2r-1, ruling 7) over `RingHeader.java` alone: the header words are stored where they were,
/// the tail has no word, R is one `private volatile` field assigned once and followed by the store-store fence, and nothing in
/// the file reaches memory by another route. Each rule is run against the real file (clean: the positive control) and against a
/// mutated copy (red), so a census that stopped looking cannot pass.
class RingHeaderCensusTest {
    private static final Path RING_HEADER = Path.of("src/main/java/org/pragmatica/aether/stream/RingHeader.java");
    private static final Path AETHER_ROOT = Path.of("..");
    private static final Map<String, Integer> STORES_PER_WORD = Map.of("HEADER_HEAD_OFFSET",
                                                                       2,
                                                                       "HEADER_EVENT_COUNT",
                                                                       2,
                                                                       "HEADER_DATA_WRITE_POS",
                                                                       2,
                                                                       "HEADER_DATA_TAIL_POS",
                                                                       2,
                                                                       "HEADER_DATA_SIZE",
                                                                       1,
                                                                       "HEADER_CAPACITY",
                                                                       1,
                                                                       "HEADER_TAIL_OFFSET",
                                                                       0);
    private static final Pattern HEADER_OFFSET_NAME = Pattern.compile("HEADER_(HEAD_OFFSET|TAIL_OFFSET|EVENT_COUNT|DATA_WRITE_POS|DATA_SIZE|CAPACITY|DATA_TAIL_POS)");

    @Test
    void census_overTheRealFile_isClean() throws IOException {
        assertThat(violations(Files.readString(RING_HEADER))).isEmpty();
    }

    @Test
    void census_withATailWordStore_isRed() throws IOException {
        var mutated = source().replace("private static final long HEADER_EVENT_COUNT = 16;",
                                       "private static final long HEADER_EVENT_COUNT = 16;\n    private static final long HEADER_TAIL_OFFSET = 8;")
                              .replace("        header.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, -1L);",
                                       "        header.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, -1L);\n        header.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, 0L);");

        assertThat(violations(mutated)).anyMatch(v -> v.contains("HEADER_TAIL_OFFSET"));
    }

    /// Mutant A.
    @Test
    void census_withoutVolatileOnR_isRed() throws IOException {
        assertThat(violations(source().replace("private volatile RemovalLedger", "private RemovalLedger")))
            .anyMatch(v -> v.contains("volatile"));
    }

    /// Mutant A2.
    @Test
    void census_withoutTheFenceAfterTheAssignment_isRed() throws IOException {
        assertThat(violations(source().replace("        VarHandle.storeStoreFence();\n", "")))
            .anyMatch(v -> v.contains("fence"));
    }

    /// Mutant C.
    @Test
    void census_withAThirdAssignmentToR_isRed() throws IOException {
        var mutated = source().replace("    RemovalLedger ledger() {\n        return ledger;",
                                       "    RemovalLedger ledger() {\n        ledger = RemovalLedger.EMPTY;\n        return ledger;");

        assertThat(violations(mutated)).anyMatch(v -> v.contains("assignment"));
    }

    /// Mutant 3.
    @Test
    void census_withTwoStatementsOnOneLine_isRed() throws IOException {
        var store = "        header.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, value);";
        var mutated = source().replace(store, store + " " + store.strip());

        assertThat(violations(mutated)).anyMatch(v -> v.contains("statements"));
    }

    /// Mutant B.
    @Test
    void census_withAnAddressReinterpretedOverTheHeader_isRed() throws IOException {
        var mutated = source().replace("        return new RingHeader(controlSegment.asSlice(0, RingGeometry.HEADER_SIZE));",
                                       "        return new RingHeader(MemorySegment.ofAddress(controlSegment.address()).reinterpret(RingGeometry.HEADER_SIZE));");

        assertThat(violations(mutated)).anyMatch(v -> v.contains("ofAddress") || v.contains("reinterpret"));
    }

    /// Mutant pG.
    @Test
    void census_withAVarHandleOnRsField_isRed() throws IOException {
        var mutated = source().replace("    private final MemorySegment header;",
                                       "    private static final VarHandle LEDGER = null;\n    private final MemorySegment header;");

        assertThat(violations(mutated)).anyMatch(v -> v.contains("VarHandle"));
    }

    /// A header word is named by RingHeader and by no other main source of any aether module. Positive control: the scan finds
    /// the names in RingHeader itself.
    @Test
    void headerOffsetNames_appearOnlyInRingHeader() throws IOException {
        var elsewhere = new ArrayList<String>();
        var inRingHeader = 0L;

        for (var file : SourceWalk.files(AETHER_ROOT).stream().filter(RingHeaderCensusTest::isAetherMainJava).toList()) {
            var hits = HEADER_OFFSET_NAME.matcher(Files.readString(file)).results().count();

            if (file.getFileName().toString().equals("RingHeader.java")) {
                inRingHeader += hits;
            } else if (hits > 0) {
                elsewhere.add(file.toString());
            }
        }

        assertThat(inRingHeader).as("positive control: the names are found in RingHeader").isGreaterThan(10L);
        assertThat(elsewhere).as("other files naming a header offset").isEmpty();
    }

    static boolean isAetherMainJava(Path path) {
        var text = path.toString().replace('\\', '/');

        return text.endsWith(".java") && text.contains("/src/main/") && !text.contains("/target/") && !text.contains("/.m2-local/");
    }

    private static String source() throws IOException {
        return Files.readString(RING_HEADER);
    }

    /// The census, over the source text of one file.
    static List<String> violations(String source) {
        var found = new ArrayList<String>();
        var code = codeLines(source);

        STORES_PER_WORD.forEach((word, expected) -> {
            var count = code.stream()
                            .filter(line -> line.contains("header.set(ValueLayout.JAVA_LONG, " + word + ","))
                            .count();

            if (count != expected) {
                found.add("word " + word + " is stored " + count + " times, expected " + expected);
            }
        });

        if (code.stream().filter(line -> line.contains("HEADER_TAIL_OFFSET")).count() > 0) {
            found.add("HEADER_TAIL_OFFSET is named: the tail has no word");
        }

        if (code.stream().filter(line -> line.contains("private volatile RemovalLedger ledger")).count() != 1) {
            found.add("R's field must be 'private volatile' exactly once");
        }

        var assignments = code.stream().filter(line -> line.strip().matches("ledger\\s*=.*")).toList();

        if (assignments.size() != 1) {
            found.add("R has " + assignments.size() + " assignments, expected 1");
        } else if (!lineAfter(code, assignments.get(0)).strip().equals("VarHandle.storeStoreFence();")) {
            found.add("the store-store fence must be the statement right after the one assignment of R");
        }

        var varHandleUses = code.stream().filter(line -> line.contains("VarHandle") && !line.startsWith("import ")).toList();

        if (!varHandleUses.equals(List.of("        VarHandle.storeStoreFence();"))) {
            found.add("VarHandle may appear only as the fence: " + varHandleUses);
        }

        if (code.stream().anyMatch(line -> line.contains("MethodHandles"))) {
            found.add("MethodHandles may not appear");
        }

        for (var escape : List.of("reinterpret", "ofAddress")) {
            if (code.stream().anyMatch(line -> line.contains(escape))) {
                found.add("the escape " + escape + " may not appear");
            }
        }

        code.stream()
            .filter(line -> line.chars().filter(c -> c == ';').count() > 1)
            .forEach(line -> found.add("two statements on one line: " + line.strip()));

        return found;
    }

    private static String lineAfter(List<String> code, String line) {
        var index = code.indexOf(line);
        var next = index + 1;

        while (next < code.size() && code.get(next).isBlank()) {
            next++;
        }

        return next < code.size() ? code.get(next) : "";
    }

    /// The source without comment lines (`//`, `///`). Blank lines stay out, so "the statement right after" is the next code line.
    private static List<String> codeLines(String source) {
        return source.lines()
                     .filter(line -> !line.isBlank())
                     .filter(line -> !line.strip().startsWith("//"))
                     .toList();
    }
}
