package org.pragmatica.jbct.derive.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;

/// A locator for `[[array-of-tables]]` header line numbers in the raw sheet text.
///
/// The shared [org.pragmatica.config.toml.TomlParser] discards positions once a document is
/// parsed, so this second pass over the raw text recovers the 1-based line of each array-table
/// header in source order. Since the parser preserves array-element order, the k-th parsed row of
/// `answers.q1` corresponds to the k-th `[[answers.q1]]` header line — which lets a gate finding
/// point at the offending row.
public record SheetLines(Map<String, List<Integer>> arrayHeaders) {
    private static final Pattern ARRAY_TABLE = Pattern.compile("^\\[\\[\\s*([a-zA-Z0-9_.\\-]+)\\s*]]$");

    public SheetLines {
        arrayHeaders = Map.copyOf(arrayHeaders);
    }

    /// Scan the raw sheet text and index every array-table header by name.
    public static SheetLines of(String content) {
        Map<String, List<Integer>> headers = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            var matcher = ARRAY_TABLE.matcher(lines[i].trim());

            if (matcher.matches()) {
                headers.computeIfAbsent(matcher.group(1), _ -> new ArrayList<>()).add(i + 1);
            }
        }

        headers.replaceAll((_, positions) -> List.copyOf(positions));

        return new SheetLines(headers);
    }

    /// The 1-based line of the `index`-th `[[tableName]]` header, or 0 when out of range.
    public int lineFor(String tableName, int index) {
        return Option.option(arrayHeaders.get(tableName))
                     .filter(positions -> index >= 0 && index < positions.size())
                     .map(positions -> positions.get(index))
                     .or(0);
    }
}
