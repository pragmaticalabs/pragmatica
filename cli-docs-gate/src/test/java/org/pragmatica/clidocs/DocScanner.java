// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.clidocs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/// Extracts every CLI invocation written down in a Markdown file.
///
/// Only code contexts are read — fenced blocks and inline `` `code` `` spans. Prose is never parsed,
/// because a sentence containing the word "aether" is not a claim about the command surface and
/// treating it as one buries the real findings.
final class DocScanner {
    private DocScanner() {}

    /// Where an invocation was found. Retained because the two contexts have different trust: a
    /// fenced block is something a reader copies and runs, an inline span is usually a reference.
    enum Origin { FENCED_BLOCK, INLINE_SPAN }

    record Invocation(Path file, int line, String binary, List<String> tokens, String raw, Origin origin) {
        String location() {
            return file + ":" + line;
        }
    }

    record ScanResult(List<Invocation> invocations,
                      int filesScanned,
                      int fencedBlocksRead,
                      int fencedBlocksSkippedByLanguage,
                      int inlineSpansRead) {}

    /// The shipped binary names. `aether-cli` and `jbct-cli` are NOT here: they are what the drift
    /// looks like, not what the CLI is called, and [CliDocsDriftTest] reports a fenced line starting
    /// with one of them as a wrong-binary finding.
    private static final Set<String> BINARIES = Set.of("aether", "jbct");
    private static final Set<String> WRONG_BINARIES = Set.of("aether-cli", "jbct-cli");

    /// Info strings whose fenced blocks are read as shell. An ALLOW-list, not a deny-list: an
    /// unrecognised language is skipped and counted, so adding a new one is a visible decision rather
    /// than a silent inclusion of, say, a Java block that mentions a command in a comment. The empty
    /// info string is included — unlabelled blocks in this repository are overwhelmingly shell.
    private static final Set<String> SHELL_LANGUAGES =
        Set.of("", "bash", "sh", "shell", "zsh", "console", "shell-session", "bash-session",
               "terminal", "text", "plaintext", "plain", "output");

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*(\\S*).*$");
    private static final Pattern INLINE_SPAN = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern SHELL_SEPARATOR = Pattern.compile("\\|\\||&&|;|\\||\\bthen\\b|\\bdo\\b");
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");

    /// Command prefixes that carry no meaning for the parse and are dropped before the binary name.
    private static final Set<String> LEADING_NOISE = Set.of("sudo", "time", "exec", "watch", "nohup", "env");

    /// Path prefixes a document may put in front of the binary name. All of them still name the same
    /// shipped command surface.
    private static final List<String> BINARY_PATH_PREFIXES = List.of("./bin/", "bin/", "./", "target/");

    static ScanResult scan(List<Path> files) {
        var invocations = new ArrayList<Invocation>();
        var fencedRead = 0;
        var fencedSkipped = 0;
        var inlineRead = 0;

        for (var file : files) {
            List<String> lines;

            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Unreadable documentation file " + file, e);
            }

            String openFence = null;
            var fenceIsShell = false;

            for (var index = 0; index < lines.size(); index++) {
                var line = lines.get(index);
                var fence = FENCE.matcher(line);

                if (fence.matches()) {
                    var marker = fence.group(1);

                    if (openFence == null) {
                        openFence = marker;
                        fenceIsShell = SHELL_LANGUAGES.contains(fence.group(2).toLowerCase());

                        if (fenceIsShell) {
                            fencedRead++;
                        } else {
                            fencedSkipped++;
                        }
                    } else if (marker.charAt(0) == openFence.charAt(0) && marker.length() >= openFence.length()) {
                        openFence = null;
                        fenceIsShell = false;
                    }

                    continue;
                }

                if (openFence != null) {
                    if (fenceIsShell) {
                        var joined = joinContinuations(lines, index);

                        collect(invocations, file, index + 1, joined, Origin.FENCED_BLOCK, true);
                    }

                    continue;
                }

                var span = INLINE_SPAN.matcher(line);

                while (span.find()) {
                    inlineRead++;
                    collect(invocations, file, index + 1, span.group(1), Origin.INLINE_SPAN, false);
                }
            }
        }

        return new ScanResult(List.copyOf(invocations), files.size(), fencedRead, fencedSkipped, inlineRead);
    }

    /// A shell line ending in `\` continues on the next one, and the continuation is where the options
    /// usually live. Not joining them would drop those options from validation — an under-matching
    /// scan that reports green because it never looked, which is the exact failure this gate exists to
    /// prevent in the documentation it reads.
    private static String joinContinuations(List<String> lines, int start) {
        var builder = new StringBuilder(lines.get(start).stripTrailing());

        var cursor = start;

        while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\\' && cursor + 1 < lines.size()) {
            builder.setLength(builder.length() - 1);
            cursor++;
            builder.append(' ').append(lines.get(cursor).strip());
        }

        return builder.toString();
    }

    private static void collect(List<Invocation> out,
                                Path file,
                                int line,
                                String text,
                                Origin origin,
                                boolean fenced) {
        for (var segment : SHELL_SEPARATOR.split(text)) {
            var tokens = tokenize(segment);

            if (tokens.size() < 2) {
                continue;
            }

            var binary = stripPathPrefix(tokens.get(0));

            var recognised = BINARIES.contains(binary) || (fenced && WRONG_BINARIES.contains(binary));

            if (recognised) {
                out.add(new Invocation(file, line, binary, tokens.subList(1, tokens.size()), segment.strip(), origin));
            }
        }
    }

    private static List<String> tokenize(String segment) {
        var stripped = segment.strip();

        // Shell prompt markers, and the `$(...)`/backtick command-substitution wrappers.
        stripped = stripped.replace("$(", " ").replace(")", " ");

        if (stripped.startsWith("$ ") || stripped.startsWith("> ")) {
            stripped = stripped.substring(2);
        }

        if (stripped.startsWith("#") || stripped.startsWith("//")) {
            return List.of();
        }

        var tokens = new ArrayList<String>();

        for (var raw : stripped.split("\\s+")) {
            var token = raw.strip();

            if (token.isEmpty()) {
                continue;
            }

            if (tokens.isEmpty() && (LEADING_NOISE.contains(token) || ENV_ASSIGNMENT.matcher(token).matches())) {
                continue;
            }

            tokens.add(token);
        }

        return tokens;
    }

    private static String stripPathPrefix(String token) {
        for (var prefix : BINARY_PATH_PREFIXES) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }

        return token;
    }

    static Set<String> wrongBinaries() {
        return WRONG_BINARIES;
    }

    static Set<String> shellLanguages() {
        return SHELL_LANGUAGES;
    }
}
