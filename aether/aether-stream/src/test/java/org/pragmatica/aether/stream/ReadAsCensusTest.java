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
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The [ReadAs] census (M-2r-1, ruling 7): the constructor is called exactly twice in the main sources of every aether module
/// (the `COPY` constant and the mint), and the type appears in no field, no record header and no type argument. The one
/// exception to the last pin is the mint's own signature: the loan takes an `Fn1<Result<T>, ReadAs>`, the shape the ruling fixed,
/// so a type argument is allowed in `ReadAs.java` and nowhere else, and so is the `COPY` constant (a constant, not a held value).
///
/// The census reads TOKENS, not lines: comments, string and char literals are dropped and whitespace (a line break inside a
/// generic included) is not a token, so a wildcard (`? extends ReadAs>`, `? super ReadAs>`) or a generic split over lines is seen
/// as the plain type argument is. A `ReadAs` token is a type argument when a `>` follows it, or when it sits between generic
/// separators (`<`/`,`/`extends`/`super` before, `,`/`<` after); a field when a modifier precedes it and a name and `;`/`=`
/// follow; a record component when it lies inside the parentheses of a `record` header.
class ReadAsCensusTest {
    private static final Path AETHER_ROOT = Path.of("..");
    private static final String READ_AS = "ReadAs";
    private static final String EXEMPT_FILE = "ReadAs.java";
    private static final Set<String> MODIFIERS = Set.of("private", "public", "protected", "static", "final", "volatile");
    private static final Set<String> BEFORE_TYPE_ARGUMENT = Set.of("<", ",", "extends", "super");
    private static final Set<String> AFTER_TYPE_ARGUMENT = Set.of(">", ",", "<");

    @Test
    void readAs_overTheMainSources_isConstructedTwiceAndHeldNowhere() throws IOException {
        var constructions = 0L;
        var offences = new ArrayList<String>();

        try (Stream<Path> files = Files.walk(AETHER_ROOT)) {
            for (var file : files.filter(RingHeaderCensusTest::isAetherMainJava).toList()) {
                var tokens = tokens(Files.readString(file));

                constructions += constructions(tokens);
                offences.addAll(heldOffences(file.getFileName().toString(), tokens));
            }
        }

        assertThat(constructions).as("'new ReadAs(' in aether/*/src/main").isEqualTo(2L);
        assertThat(offences).isEmpty();
    }

    /// Positive controls: each shape fires, whatever the spacing, and the mint's own type argument is the one allowed.
    @Test
    void heldOffences_firesOnAFieldARecordHeaderAndEveryShapeOfTypeArgument() {
        assertThat(offences("X.java", "    private ReadAs role;")).hasSize(1);
        assertThat(offences("X.java", "    private\n        final\n        ReadAs role;")).hasSize(1);
        assertThat(offences("X.java", "record Held(String name, ReadAs role) {}")).hasSize(1);
        assertThat(offences("X.java", "record Held(String name,\n        ReadAs role) {}")).hasSize(1);
        assertThat(offences("X.java", "    List<ReadAs> roles;")).isNotEmpty();
        assertThat(offences("X.java", "    List<? extends ReadAs> roles;")).as("wildcard extends").hasSize(1);
        assertThat(offences("X.java", "    List<? super ReadAs> roles;")).as("wildcard super").hasSize(1);
        assertThat(offences("X.java", "    Map<ReadAs, String> roles;")).as("first argument").hasSize(1);
        assertThat(offences("X.java", "    Function<String,\n        ReadAs> mint;")).as("multi-line generic").hasSize(1);
        assertThat(offences("X.java", "    Map<String, List<\n  ? extends\n  ReadAs\n>> roles;")).as("nested, multi-line wildcard").hasSize(1);
        assertThat(offences("X.java", "Fn1<Result<T>, ReadAs> use")).hasSize(1);
        assertThat(offences("ReadAs.java", "Fn1<Result<T>, ReadAs> use")).isEmpty();
        assertThat(offences("ReadAs.java", "    public static final ReadAs COPY = new ReadAs(\"\", -1, 0L);")).isEmpty();
        assertThat(offences("X.java", "    public static final ReadAs COPY = null;")).hasSize(1);
    }

    /// Negative controls: a parameter, a cast, a comment, a string and a block comment are not held values.
    @Test
    void heldOffences_ignoresParametersCastsCommentsAndLiterals() {
        assertThat(offences("X.java", "    Result<Long> read(long offset, ReadAs readAs) {")).isEmpty();
        assertThat(offences("X.java", "    var role = (ReadAs) other;")).isEmpty();
        assertThat(offences("X.java", "    // List<ReadAs> roles;")).isEmpty();
        assertThat(offences("X.java", "    var text = \"List<? extends ReadAs> roles\";")).isEmpty();
        assertThat(offences("X.java", "    /* private ReadAs role;\n       List<ReadAs> roles; */")).isEmpty();
        assertThat(constructions(tokens("var a = new ReadAs(1); // new ReadAs(2)\nvar b = \"new ReadAs(3)\"; var c = new\n  ReadAs(4);"))).isEqualTo(2L);
    }

    private static List<String> offences(String fileName, String source) {
        return heldOffences(fileName, tokens(source));
    }

    private static long constructions(List<String> tokens) {
        return IntStream.range(0, tokens.size() - 2)
                        .filter(i -> tokens.get(i).equals("new") && tokens.get(i + 1).equals(READ_AS) && tokens.get(i + 2).equals("("))
                        .count();
    }

    private static List<String> heldOffences(String fileName, List<String> tokens) {
        var exempt = fileName.equals(EXEMPT_FILE);
        var offences = new ArrayList<String>();

        for (var i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).equals(READ_AS)) {
                continue;
            }

            if (inRecordHeader(tokens, i) || (!exempt && (isTypeArgument(tokens, i) || isField(tokens, i)))) {
                offences.add(fileName + ": " + context(tokens, i));
            }
        }

        return offences;
    }

    private static boolean isTypeArgument(List<String> tokens, int at) {
        return tokenAt(tokens, at + 1).equals(">")
               || (BEFORE_TYPE_ARGUMENT.contains(tokenAt(tokens, at - 1)) && AFTER_TYPE_ARGUMENT.contains(tokenAt(tokens, at + 1)));
    }

    private static boolean isField(List<String> tokens, int at) {
        return MODIFIERS.contains(tokenAt(tokens, at - 1)) && isIdentifier(tokenAt(tokens, at + 1)) && Set.of(";", "=").contains(tokenAt(tokens, at + 2));
    }

    /// The token lies between the parentheses of the nearest `record Name(` or `record Name<...>(` before it.
    private static boolean inRecordHeader(List<String> tokens, int at) {
        var depth = 0;

        for (var i = at - 1; i >= 0; i--) {
            var token = tokens.get(i);

            if (token.equals(")")) {
                depth++;
            } else if (token.equals("(") && depth > 0) {
                depth--;
            } else if (token.equals("(")) {
                return isRecordNameBefore(tokens, i);
            } else if (token.equals(";") || token.equals("{") || token.equals("}")) {
                return false;
            }
        }

        return false;
    }

    private static boolean isRecordNameBefore(List<String> tokens, int openParen) {
        var name = openParen - 1;

        if (tokenAt(tokens, name).equals(">")) {
            name = skipGenericsBackwards(tokens, name) - 1;
        }

        return tokenAt(tokens, name - 1).equals("record") && isIdentifier(tokenAt(tokens, name));
    }

    private static int skipGenericsBackwards(List<String> tokens, int close) {
        var depth = 0;

        for (var i = close; i >= 0; i--) {
            depth += tokens.get(i).equals(">") ? 1 : tokens.get(i).equals("<") ? -1 : 0;
            if (depth == 0) {
                return i;
            }
        }

        return 0;
    }

    private static String tokenAt(List<String> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : "";
    }

    private static boolean isIdentifier(String token) {
        return !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0));
    }

    private static String context(List<String> tokens, int at) {
        return String.join(" ", tokens.subList(Math.max(0, at - 4), Math.min(tokens.size(), at + 4)));
    }

    /// Java source as tokens: identifiers and numbers whole, every other non-space character alone; comments, string and char
    /// literals and text blocks are dropped, so only code remains and a line break is no different from a space.
    private static List<String> tokens(String source) {
        var tokens = new ArrayList<String>();
        var i = 0;

        while (i < source.length()) {
            var c = source.charAt(i);

            if (source.startsWith("//", i)) {
                i = skipTo(source, "\n", i + 2);
            } else if (source.startsWith("/*", i)) {
                i = skipTo(source, "*/", i + 2) + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                i = skipTo(source, "\"\"\"", i + 3) + 3;
            } else if (c == '"' || c == '\'') {
                i = skipLiteral(source, i);
            } else if (Character.isJavaIdentifierPart(c)) {
                var end = i;

                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }

                tokens.add(source.substring(i, end));
                i = end;
            } else {
                if (!Character.isWhitespace(c)) {
                    tokens.add(String.valueOf(c));
                }

                i++;
            }
        }

        return tokens;
    }

    private static int skipTo(String source, String terminator, int from) {
        var found = source.indexOf(terminator, from);

        return found < 0 ? source.length() : found;
    }

    private static int skipLiteral(String source, int open) {
        var quote = source.charAt(open);
        var i = open + 1;

        while (i < source.length() && source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }

        return i + 1;
    }
}
