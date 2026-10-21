// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.docs;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The code list in `management-api.md` is one bullet per code and the file merges with `merge=union`
/// (`.gitattributes`), which keeps both sides' lines when two PRs add a code. Union cannot tell a clean
/// double-append from a corrupted merge, so this test pins the shape a corrupted one breaks: a single list,
/// nothing in it but bullets, every bullet naming a code of the catalogue, no code listed twice.
///
/// Two further shapes a hand edit can produce and union cannot: two bullets joined on one line, and a code bullet
/// outside the list (before the intro or after the end marker). The first is rejected per line; the second by
/// scanning the whole page for bullet lines whose code is in the catalogue or is kebab-shaped with a hyphen (the page
/// has other bullets, such as `method`, whose single-word names are not codes).
///
/// Completeness is NOT asserted: most codes are not documented on this page yet, and a frozen exemption list
/// would itself be a conflict magnet. The count of bullets is asserted non-zero, so a parser that stopped
/// matching cannot turn this green over an empty list.
class OperatorWarningCodeDocsTest {
    private static final String PAGE = "aether/docs/reference/management-api.md";
    private static final String LIST_INTRO = "Current codes:";
    private static final String LIST_END = "Best-effort like every per-node event";
    private static final Pattern BULLET = Pattern.compile("^- `([a-z0-9-]+)` \\(.*\\)$");
    private static final Pattern BULLET_START = Pattern.compile("- `([a-z0-9-]+)` \\(");
    private static final Pattern CODE_SHAPED = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)+");

    @Test
    void codeList_isOneListOfKnownCodes_eachListedOnce() throws IOException {
        var lines = Files.readAllLines(pageFile());
        var bullets = listBody(lines);
        var codes = bullets.stream().map(OperatorWarningCodeDocsTest::codeOf).toList();

        assertTrue(codes.size() > 0, "no code bullets found under '" + LIST_INTRO + "' in " + PAGE);
        assertEquals(List.of(), unknown(codes), "bullets naming a code that is not in OperatorWarningCode");
        assertEquals(List.of(), duplicates(codes), "codes listed more than once in " + PAGE);
    }

    @Test
    void codeList_hasOneBulletPerLine() throws IOException {
        var joined = listBody(Files.readAllLines(pageFile())).stream()
                                                              .filter(l -> BULLET_START.matcher(l).results().count() > 1)
                                                              .map(OperatorWarningCodeDocsTest::abbreviate)
                                                              .toList();

        assertEquals(List.of(), joined, "list lines holding more than one bullet");
    }

    @Test
    void page_hasNoCodeBulletOutsideTheList() throws IOException {
        var lines = Files.readAllLines(pageFile());
        var start = indexOf(lines, l -> l.contains(LIST_INTRO));
        var end = indexOf(lines, l -> l.startsWith(LIST_END));
        var stray = new ArrayList<String>();

        for (int i = 0; i < lines.size(); i++) {
            if ((i <= start || i >= end) && isCodeBullet(lines.get(i))) {
                stray.add((i + 1) + ": " + abbreviate(lines.get(i)));
            }
        }
        assertEquals(List.of(), stray, "code-shaped bullets outside the list in " + PAGE);
    }

    @Test
    void codeList_hasExactlyOneIntroAndOneEnd() throws IOException {
        var lines = Files.readAllLines(pageFile());

        assertEquals(1, lines.stream().filter(l -> l.contains(LIST_INTRO)).count(), "'" + LIST_INTRO + "' occurrences in " + PAGE);
        assertEquals(1, lines.stream().filter(l -> l.startsWith(LIST_END)).count(), "'" + LIST_END + "' paragraphs in " + PAGE);
    }

    private static List<String> listBody(List<String> lines) {
        var start = indexOf(lines, l -> l.contains(LIST_INTRO)) + 1;
        var end = indexOf(lines, l -> l.startsWith(LIST_END));

        return lines.subList(start, end).stream().filter(l -> !l.isBlank()).toList();
    }

    private static int indexOf(List<String> lines, java.util.function.Predicate<String> match) {
        for (int i = 0; i < lines.size(); i++) {
            if (match.test(lines.get(i))) {
                return i;
            }
        }
        throw new IllegalStateException("marker not found in " + PAGE);
    }

    private static String codeOf(String bullet) {
        var m = BULLET.matcher(bullet);

        assertTrue(m.matches(), "line inside the code list that is not a one-code bullet: " + abbreviate(bullet));
        return m.group(1);
    }

    private static boolean isCodeBullet(String line) {
        var m = BULLET_START.matcher(line);

        return m.find() && m.start() == 0 && (CODE_SHAPED.matcher(m.group(1)).matches() || isCatalogueCode(m.group(1)));
    }

    private static boolean isCatalogueCode(String code) {
        return Arrays.stream(OperatorWarningCode.values()).anyMatch(c -> c.code().equals(code));
    }

    private static List<String> unknown(List<String> codes) {
        var known = Arrays.stream(OperatorWarningCode.values()).map(OperatorWarningCode::code).collect(java.util.stream.Collectors.toSet());

        return codes.stream().filter(c -> !known.contains(c)).distinct().toList();
    }

    private static List<String> duplicates(List<String> codes) {
        var seen = new HashSet<String>();

        return codes.stream().filter(c -> !seen.add(c)).distinct().toList();
    }

    private static String abbreviate(String line) {
        return line.length() > 80
               ? line.substring(0, 80) + "..."
               : line;
    }

    private static Path pageFile() {
        try {
            var dir = Path.of(OperatorWarningCodeDocsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            while (dir != null && !Files.isRegularFile(dir.resolve(PAGE))) {
                dir = dir.getParent();
            }
            if (dir == null) {
                throw new IllegalStateException(PAGE + " not found above the test classes");
            }
            return dir.resolve(PAGE);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
