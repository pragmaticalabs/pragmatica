// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.generatedgate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// What a generated project's build log must show for its format-check and lint to count as having RUN (#1998).
///
/// The execution header (`--- jbct:format-check ...`) is printed even when the mojo then skips, so it proves nothing. The only
/// evidence is the count the goal itself reports (`Checking format of N Java file(s)`, `Linting N Java file(s)`), and a count of
/// zero, a missing count, or a skip line is a gate that examined nothing. The payload is matched, never the `[INFO]`/`[WARNING]`
/// prefix, which differs by goal and by Maven version.
final class GateLog {
    private static final Pattern FORMAT_COUNT = Pattern.compile("Checking format of (\\d+) Java file\\(s\\)");
    private static final Pattern LINT_COUNT = Pattern.compile("Linting (\\d+) Java file\\(s\\)");

    private GateLog() {}

    /// Every reason `log` does not show both gates running over at least `promisedFiles` Java files; empty when it does.
    /// `promisedFiles` of zero means the variant generates no Java file: the gates must then not claim a non-empty count,
    /// but a skip is still a failure.
    static List<String> problems(String log, int promisedFiles) {
        var found = new ArrayList<String>();

        log.lines()
           .filter(line -> line.contains("Skipping JBCT") || line.contains("examined NOTHING"))
           .forEach(line -> found.add("a gate did not run: " + line.strip()));
        check("format-check", FORMAT_COUNT, log, promisedFiles, found);
        check("lint", LINT_COUNT, log, promisedFiles, found);

        return found;
    }

    private static void check(String goal, Pattern pattern, String log, int promisedFiles, List<String> found) {
        var counts = pattern.matcher(log).results().map(result -> Integer.parseInt(result.group(1))).toList();

        if (promisedFiles == 0) {
            if (counts.stream().anyMatch(count -> count > 0)) {
                found.add(goal + " reported " + counts + " Java file(s) for a variant that generates none");
            }

            return;
        }

        if (counts.isEmpty()) {
            found.add(goal + " reported no `N Java file(s)` count: it did not run, or ran over nothing");
        } else if (counts.stream().anyMatch(count -> count < promisedFiles)) {
            found.add(goal + " examined " + counts + " Java file(s), fewer than the " + promisedFiles + " the variant promises");
        }
    }
}
