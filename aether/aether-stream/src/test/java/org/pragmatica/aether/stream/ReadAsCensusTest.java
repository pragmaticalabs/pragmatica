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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The [ReadAs] census (M-2r-1, ruling 7): the constructor is called exactly twice in the main sources of every aether module
/// (the `COPY` constant and the mint), and the type appears in no field, no record header and no type argument. The one
/// exception to the last pin is the mint's own signature: the loan takes an `Fn1<Result<T>, ReadAs>`, the shape the ruling fixed,
/// so a type argument is allowed in `ReadAs.java` and nowhere else, and so is the `COPY` constant (a constant, not a held value).
class ReadAsCensusTest {
    private static final Path AETHER_ROOT = Path.of("..");
    private static final Pattern CONSTRUCTION = Pattern.compile("new\\s+ReadAs\\s*\\(");
    private static final Pattern FIELD = Pattern.compile("^\\s*(private|public|protected|static|final|volatile)[^;(=]*\\bReadAs\\s+\\w+\\s*[;=]");
    private static final Pattern RECORD_HEADER = Pattern.compile("\\brecord\\s+\\w+\\s*\\([^)]*\\bReadAs\\b");
    private static final Pattern TYPE_ARGUMENT = Pattern.compile("[<,]\\s*ReadAs\\s*[>,<]");

    @Test
    void readAs_overTheMainSources_isConstructedTwiceAndHeldNowhere() throws IOException {
        var constructions = 0L;
        var offences = new ArrayList<String>();

        try (Stream<Path> files = Files.walk(AETHER_ROOT)) {
            for (var file : files.filter(RingHeaderCensusTest::isAetherMainJava).toList()) {
                var source = Files.readString(file);

                constructions += CONSTRUCTION.matcher(code(source)).results().count();
                offences.addAll(heldOffences(file.getFileName().toString(), source));
            }
        }

        assertThat(constructions).as("'new ReadAs(' in aether/*/src/main").isEqualTo(2L);
        assertThat(offences).isEmpty();
    }

    /// Positive controls: each pattern fires on the shape it names, and the mint's own type argument is the one allowed.
    @Test
    void heldOffences_firesOnAFieldARecordHeaderAndATypeArgument() {
        assertThat(heldOffences("X.java", "    private ReadAs role;")).hasSize(1);
        assertThat(heldOffences("X.java", "record Held(String name, ReadAs role) {}")).hasSize(1);
        assertThat(heldOffences("X.java", "    List<ReadAs> roles;")).isNotEmpty();
        assertThat(heldOffences("ReadAs.java", "Fn1<Result<T>, ReadAs> use")).isEmpty();
        assertThat(heldOffences("X.java", "Fn1<Result<T>, ReadAs> use")).hasSize(1);
        assertThat(heldOffences("ReadAs.java", "    public static final ReadAs COPY = new ReadAs(\"\", -1, 0L);")).isEmpty();
        assertThat(heldOffences("X.java", "    public static final ReadAs COPY = null;")).hasSize(1);
        assertThat(heldOffences("X.java", "    Result<Long> read(long offset, ReadAs readAs) {")).isEmpty();
    }

    private static List<String> heldOffences(String fileName, String source) {
        var offences = new ArrayList<String>();

        code(source).lines().forEach(line -> {
            if ((FIELD.matcher(line).find() && !fileName.equals("ReadAs.java")) || RECORD_HEADER.matcher(line).find()) {
                offences.add(fileName + ": " + line.strip());
            }

            if (TYPE_ARGUMENT.matcher(line).find() && !fileName.equals("ReadAs.java")) {
                offences.add(fileName + ": " + line.strip());
            }
        });

        return offences;
    }

    private static String code(String source) {
        return String.join("\n", source.lines().filter(line -> !line.strip().startsWith("//")).toList());
    }
}
