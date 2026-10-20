package org.pragmatica.serialization.codec;

import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.json.codegen.JsonNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/// The flat name every generated name is derived from is injective: no two lists of nested simple names share one.
/// Checked exhaustively over a small alphabet that holds every character the escaping treats specially, and on random
/// names over a wider one; each flat name is also decoded back to the one list it came from.
class JsonNamesTest {
    // Simple names start with a letter, `_` or `$`, never a digit; later characters may be digits too.
    private static final String START = "a_$";
    private static final String PART = "a_$01";

    @Test
    void flatName_isInjective_overEveryNestingOfShortNames() {
        var names = names(3);
        var seen = new HashMap<String, List<String>>();

        for (var first : names) {
            check(seen, List.of(first));
            for (var second : names) {
                check(seen, List.of(first, second));
                for (var third : names) {
                    check(seen, List.of(first, second, third));
                }
            }
        }

        assertEquals(names.size() + names.size() * names.size() + names.size() * names.size() * names.size(), seen.size());
    }

    @Test
    void flatName_isInjective_onRandomNestedNames() {
        var random = new Random(20261018L);
        var seen = new HashMap<String, List<String>>();

        for (int i = 0; i < 200_000; i++) {
            var levels = new ArrayList<String>();

            for (int depth = 1 + random.nextInt(4); depth > 0; depth--) {
                levels.add(randomName(random));
            }

            check(seen, List.copyOf(levels));
        }
    }

    @Test
    void flatName_matchesTheDocumentedExamples() {
        assertEquals("BuyRequest", JsonNames.flatName(List.of("BuyRequest")));
        assertEquals("Ticketing_BuyRequest", JsonNames.flatName(List.of("Ticketing", "BuyRequest")));
        assertEquals("A_0B", JsonNames.flatName(List.of("A_B")));
        assertEquals("A_B", JsonNames.flatName(List.of("A", "B")));
        assertEquals("A_1B", JsonNames.flatName(List.of("A$B")));
        assertEquals("A_0_0_B", JsonNames.flatName(List.of("A__", "B")));
        assertEquals("A_0_B", JsonNames.flatName(List.of("A_", "B")));
        assertEquals("A__0B", JsonNames.flatName(List.of("A", "_B")));
    }

    private static void check(Map<String, List<String>> seen, List<String> levels) {
        var flat = JsonNames.flatName(levels);
        var previous = seen.putIfAbsent(flat, levels);

        if (previous != null && !previous.equals(levels)) {
            fail(levels + " and " + previous + " share the flat name " + flat);
        }

        assertFalse(flat.contains("$"), flat);
        assertEquals(levels, decode(flat), flat);
    }

    // The inverse the injectivity argument rests on: `_` then a digit is an escape, any other `_` joins two levels.
    private static List<String> decode(String flat) {
        var levels = new ArrayList<String>();
        var current = new StringBuilder();

        for (int i = 0; i < flat.length(); i++) {
            var c = flat.charAt(i);

            if (c != '_') {
                current.append(c);
            } else if (i + 1 < flat.length() && flat.charAt(i + 1) == '0') {
                current.append('_');
                i++;
            } else if (i + 1 < flat.length() && flat.charAt(i + 1) == '1') {
                current.append('$');
                i++;
            } else {
                levels.add(current.toString());
                current.setLength(0);
            }
        }

        levels.add(current.toString());

        return levels;
    }

    private static List<String> names(int maxLength) {
        var names = new ArrayList<String>();

        for (var start : START.toCharArray()) {
            extend(names, String.valueOf(start), maxLength);
        }

        return names;
    }

    private static void extend(List<String> names, String prefix, int maxLength) {
        names.add(prefix);

        if (prefix.length() < maxLength) {
            for (var part : PART.toCharArray()) {
                extend(names, prefix + part, maxLength);
            }
        }
    }

    private static String randomName(Random random) {
        var start = "Ab_$";
        var part = "Ab_$019";
        var name = new StringBuilder().append(start.charAt(random.nextInt(start.length())));

        for (int length = random.nextInt(6); length > 0; length--) {
            name.append(part.charAt(random.nextInt(part.length())));
        }

        return name.toString();
    }
}
