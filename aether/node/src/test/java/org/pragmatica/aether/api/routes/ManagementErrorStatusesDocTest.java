// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamError;

import static org.assertj.core.api.Assertions.assertThat;

/// #3390 F4: `aether/docs/reference/management-error-statuses.md` is checked against the code. Each section header's count is
/// its rows, the summary total is the sum of the rows, every `StreamError` record variant has a row in the stream section
/// (each answered 500 before classification, or is new), and every row names a variant that exists. Mutants: a header or
/// summary count off by one, a variant added without a row, a row left for a removed variant.
class ManagementErrorStatusesDocTest {
    private static final Path DOC = Path.of("..", "docs", "reference", "management-error-statuses.md");
    private static final Pattern HEADER = Pattern.compile("^### .*\\((\\d+)\\).*$");
    private static final Pattern SUMMARY = Pattern.compile("^(\\d+) causes are listed: (\\d+) answered 500 .*, and (\\d+) are new.*$");
    private static final Pattern STREAM_ROW = Pattern.compile("^\\| `org\\.pragmatica\\.aether\\.stream\\.StreamError\\$([A-Za-z]+)(#[A-Z_]+)?` \\|.*$");
    private static final String STREAM_SECTION = "### Raw stream engine errors";

    @Test
    void docCounts_matchTheRows_andEveryStreamErrorVariantHasARow() throws IOException {
        var lines = Files.readAllLines(DOC);
        var declared = new ArrayList<Integer>();
        var counted = new ArrayList<Integer>();
        var streamVariants = new ArrayList<String>();
        var newRows = 0;
        var inStream = false;

        for (var line : lines) {
            var header = HEADER.matcher(line);

            if (header.matches()) {
                declared.add(Integer.parseInt(header.group(1)));
                counted.add(0);
                inStream = line.startsWith(STREAM_SECTION);
            } else if (!counted.isEmpty() && line.startsWith("| `")) {
                counted.set(counted.size() - 1, counted.get(counted.size() - 1) + 1);
                newRows += line.contains("| n/a (new) |") ? 1 : 0;
                recordStreamRow(line, inStream, streamVariants);
            }
        }

        assertThat(counted).as("rows per section, against each header's count").isEqualTo(declared);

        var total = counted.stream().mapToInt(Integer::intValue).sum();
        var summary = lines.stream().map(SUMMARY::matcher).filter(Matcher::matches).findFirst();

        assertThat(summary).as("the summary line").isPresent();
        assertThat(Integer.parseInt(summary.get().group(1))).as("summary total").isEqualTo(total);
        assertThat(Integer.parseInt(summary.get().group(3))).as("summary new").isEqualTo(newRows);
        assertThat(Integer.parseInt(summary.get().group(2))).as("summary changed").isEqualTo(total - newRows);
        assertThat(streamVariants).as("StreamError record variants with a row")
                                  .containsAll(recordVariants())
                                  .allMatch(name -> name.equals("General") || recordVariants().contains(name));
    }

    private static void recordStreamRow(String line, boolean inStream, List<String> streamVariants) {
        var row = STREAM_ROW.matcher(line);

        if (inStream && row.matches()) {
            streamVariants.add(row.group(1));
        }
    }

    private static List<String> recordVariants() {
        return Arrays.stream(StreamError.class.getPermittedSubclasses())
                     .filter(type -> type != StreamError.General.class)
                     .map(Class::getSimpleName)
                     .toList();
    }
}
