// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/// #3390 F4: `aether/docs/reference/management-error-statuses.md` is checked against the code. Each section header's count is
/// its rows, the summary total is the sum of the rows, and the summary's new count is the rows marked `n/a (new)`. The doc
/// lists only the variants whose status changed, and "everything not listed is unchanged" means 500 (the status every
/// `StreamError` answered before they were classified), so the stream section is pinned against the code: its rows are
/// EXACTLY the `StreamError` variants and `General` constants `StreamErrorStatus.statusOf` maps to something other than 500,
/// each row's After column is that status, and each Before column is `Internal Server Error` or `n/a (new)`. The variant
/// catalogue is `StreamErrorStatusTest.expectations()`, which `everyVariant_hasARow` holds to the sealed hierarchy. Mutants:
/// a header or summary count off by one, a row deleted, a row for a variant mapped 500, a mapped status changed in the code.
class ManagementErrorStatusesDocTest {
    private static final Path DOC = Path.of("..", "docs", "reference", "management-error-statuses.md");
    private static final Pattern HEADER = Pattern.compile("^### .*\\((\\d+)\\).*$");
    private static final Pattern SUMMARY = Pattern.compile("^(\\d+) causes are listed: (\\d+) answered 500 .*, and (\\d+) are new.*$");
    private static final Pattern STREAM_ROW = Pattern.compile("^\\| `org\\.pragmatica\\.aether\\.stream\\.StreamError\\$([A-Za-z]+)(#[A-Z_]+)?` \\| ([^|]+) \\| ([^|]+) \\|$");
    private static final String STREAM_SECTION = "### Raw stream engine errors";

    @Test
    void docCounts_matchTheRows() throws IOException {
        var lines = Files.readAllLines(DOC);
        var declared = new ArrayList<Integer>();
        var counted = new ArrayList<Integer>();
        var newRows = 0;

        for (var line : lines) {
            var header = HEADER.matcher(line);

            if (header.matches()) {
                declared.add(Integer.parseInt(header.group(1)));
                counted.add(0);
            } else if (!counted.isEmpty() && line.startsWith("| `")) {
                counted.set(counted.size() - 1, counted.get(counted.size() - 1) + 1);
                newRows += line.contains("| n/a (new) |") ? 1 : 0;
            }
        }

        assertThat(counted).as("rows per section, against each header's count").isEqualTo(declared);

        var total = counted.stream().mapToInt(Integer::intValue).sum();
        var summary = lines.stream().map(SUMMARY::matcher).filter(Matcher::matches).findFirst();

        assertThat(summary).as("the summary line").isPresent();
        assertThat(Integer.parseInt(summary.get().group(1))).as("summary total").isEqualTo(total);
        assertThat(Integer.parseInt(summary.get().group(3))).as("summary new").isEqualTo(newRows);
        assertThat(Integer.parseInt(summary.get().group(2))).as("summary changed").isEqualTo(total - newRows);
    }

    /// The doc's baseline sentence against the code: a variant has a row iff the code maps it to something other than 500, and
    /// the row says the status the code maps. A variant mapped 500 has no row (it is "unchanged").
    @Test
    void streamRows_areExactlyTheVariantsMappedAbove500_withTheStatusTheCodeMaps() throws IOException {
        var mapped = new LinkedHashMap<String, String>();

        StreamErrorStatusTest.expectations()
                             .keySet()
                             .stream()
                             .map(StreamError.class::cast)
                             .filter(error -> StreamErrorStatus.statusOf(error) != HttpStatus.INTERNAL_SERVER_ERROR)
                             .forEach(error -> mapped.put(label(error), StreamErrorStatus.statusOf(error).reasonPhrase()));

        var rows = streamRows(Files.readAllLines(DOC));

        assertThat(rows.keySet()).as("stream rows against the variants the code maps off 500 (a missing row is a status change the doc"
                                     + " does not state, an extra row names a variant mapped 500 or one that no longer exists)")
                                 .containsExactlyInAnyOrderElementsOf(mapped.keySet());
        mapped.forEach((variant, after) -> assertThat(rows.get(variant)
                                                          .after()).as("the After column of %s", variant)
                                                                   .isEqualTo(after));
        rows.forEach((variant, row) -> assertThat(row.before()).as("the Before column of %s", variant)
                                                               .isIn("Internal Server Error", "n/a (new)"));
    }

    private static String label(StreamError error) {
        return error instanceof StreamError.General general
               ? "General#" + general.name()
               : error.getClass().getSimpleName();
    }

    private record Row(String before, String after) {}

    private static Map<String, Row> streamRows(List<String> lines) {
        var rows = new LinkedHashMap<String, Row>();
        var inStream = false;

        for (var line : lines) {
            inStream = line.startsWith("### ")
                       ? line.startsWith(STREAM_SECTION)
                       : inStream;

            var row = STREAM_ROW.matcher(line);

            if (inStream && row.matches()) {
                rows.put(row.group(1) + (row.group(2) == null ? "" : row.group(2)), new Row(row.group(3), row.group(4)));
            }
        }
        return rows;
    }
}
