// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.clidocs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.jbct.cli.JbctCommand;

/// The source of truth: the command surface the shipped CLIs actually define.
///
/// Built by constructing the real [CommandLine] objects and walking [CommandSpec], NOT by scraping
/// `@Command` annotations out of `.java` files. The difference is not stylistic — a text scrape
/// cannot see any of the following, all of which are present in this repository's CLIs and all of
/// which appear in its documentation:
///
///   - `@Mixin` contributions (`AetherCli` mixes in `OutputOptions`, so `--format`/`--output` exist on
///     the root command without any `@Option` annotation in `AetherCli.java`);
///   - `mixinStandardHelpOptions = true`, which synthesises `-h`, `--help`, `-V`, `--version`;
///   - picocli's own `AutoComplete.GenerateCompletion`, registered by class reference, whose command
///     name `generate-completion` appears nowhere in this repository's source at all;
///   - the `subcommands = {...}` graph, which a per-file scrape would have to re-implement.
///
/// Constructing a [CommandLine] parses annotations and mixins but executes no user code and opens no
/// connections; `AetherCli`'s network setup lives in `main`, which is not called here.
final class CliSurface {
    private CliSurface() {}

    /// One command in the tree. [#path] is the canonical invocation prefix a user would type —
    /// `"aether cluster bootstrap"` — built from canonical names only, never from aliases.
    record Node(String path, CommandSpec spec, Node parent) {
        Set<String> optionNames() {
            var names = new LinkedHashSet<String>();

            spec.options().forEach(option -> names.addAll(List.of(option.names())));

            return names;
        }

        /// Option names legal at this point of the command line, including those declared on every
        /// ancestor. Picocli does not inherit options unless `scope = INHERIT`, but a USER writes
        /// `aether --format json cluster status`, putting a root option ahead of the subcommand, and
        /// documentation writes it the same way. Validating against the ancestor chain is therefore
        /// the shape that matches how the CLI is really invoked; the alternative flags correct
        /// documentation as drift.
        Set<String> optionNamesWithAncestors() {
            var names = new LinkedHashSet<String>();

            for (var node = this; node != null; node = node.parent()) {
                names.addAll(node.optionNames());
            }

            return names;
        }

        boolean hidden() {
            return spec.usageMessage().hidden();
        }

        /// The [picocli.CommandLine.Model.OptionSpec] for `name`, searched up the ancestor chain on the
        /// same argument as [#optionNamesWithAncestors]. Needed to learn an option's ARITY: an option
        /// that takes a value consumes the next token, and a resolver that does not know this reads
        /// `aether --api-key mykey123 status` as an invocation of a subcommand called `mykey123`.
        OptionSpec findOption(String name) {
            for (var node = this; node != null; node = node.parent()) {
                for (var option : node.spec().options()) {
                    if (List.of(option.names()).contains(name)) {
                        return option;
                    }
                }
            }

            return null;
        }
    }

    /// Result of walking a documented token sequence down the real command tree.
    ///
    /// [#unknownSubcommand] is the whole point of the gate: a bare word sitting in subcommand
    /// position that the tree does not define. [#unknownOptions] is the same failure one level down.
    record Resolution(Node resolved, String unknownSubcommand, List<String> unknownOptions) {
        boolean clean() {
            return unknownSubcommand == null && unknownOptions.isEmpty();
        }
    }

    static CommandLine aetherCli() {
        return new CommandLine(new AetherCli());
    }

    static CommandLine jbctCli() {
        return new CommandLine(new JbctCommand());
    }

    /// Canonical paths of every command in the tree, root included, in stable order.
    static Map<String, Node> flatten(CommandLine root) {
        var out = new TreeMap<String, Node>();

        collect(new Node(root.getCommandSpec().name(), root.getCommandSpec(), null), out);

        return out;
    }

    private static void collect(Node node, Map<String, Node> out) {
        out.put(node.path(), node);

        // subcommands() is keyed by canonical name AND by every alias; walking values() and reading
        // each child's own spec.name() is what keeps aliases out of the canonical path set. The
        // LinkedHashMap dedupes the alias entries that point at the same CommandLine.
        var canonical = new LinkedHashMap<CommandSpec, String>();

        node.spec().subcommands().forEach((key, child) -> canonical.putIfAbsent(child.getCommandSpec(),
                                                                               child.getCommandSpec().name()));
        canonical.forEach((childSpec, name) -> collect(new Node(node.path() + " " + name, childSpec, node), out));
    }

    /// Walk a documented invocation's tokens down the tree.
    ///
    /// The delicate judgement is what to do with a bare word that is NOT a subcommand. It can be a
    /// positional argument (`aether slices restart my-slice`) or it can be drift (`aether topology`).
    /// Getting this wrong in the permissive direction under-reports; getting it wrong in the strict
    /// direction floods the gate with false positives and gets it disabled, which is worse. The rule
    /// applied here reports drift ONLY when all of the following hold, and treats the token as an
    /// argument otherwise:
    ///
    ///   1. the current command declares subcommands — so a bare word here is in subcommand position;
    ///   2. the current command declares NO positional parameters — so nothing legitimate can sit
    ///      there instead;
    ///   3. the token is not placeholder-shaped (see [#looksLikeArgument]).
    ///
    /// Consequence, stated rather than hidden: a command that declares BOTH subcommands and
    /// positionals is a blind spot — an invented subcommand under it reads as a positional and is not
    /// reported. [CliDocsDriftTest#gate_blindSpots_areEnumerated] prints that set so its size is a
    /// known number rather than an unknown one.
    static Resolution resolve(Node root, List<String> tokens) {
        var node = root;
        var options = new ArrayList<String>();
        var descending = true;

        for (var index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);

            if ("--".equals(token)) {
                break;
            }

            if (isOptionToken(token)) {
                var name = normalizeOption(token);

                if (isProseShorthand(name)) {
                    continue;
                }

                var spec = node.findOption(name);

                if (spec == null) {
                    options.add(name);
                    // The parse is no longer trustworthy past an option whose arity is unknown: it may
                    // or may not swallow the next token. Stop descending so a value is never misread as
                    // an invented subcommand, and keep scanning so the remaining options are still
                    // checked. The unknown option is already a finding on this line.
                    descending = false;
                    continue;
                }

                if (token.indexOf('=') < 0 && spec.arity().max > 0 && index + 1 < tokens.size()
                    && !tokens.get(index + 1).startsWith("-")) {
                    index++;
                }

                continue;
            }

            if (!descending) {
                continue;
            }

            var child = node.spec().subcommands().get(token);

            if (child != null) {
                node = new Node(node.path() + " " + child.getCommandSpec().name(), child.getCommandSpec(), node);
                continue;
            }

            if (node.spec().subcommands().isEmpty()
                || !node.spec().positionalParameters().isEmpty()
                || looksLikeArgument(token)) {
                // A positional argument. Stop descending, but keep walking the line: options written
                // AFTER the first positional are the common shape (`aether ab-tests create -a <x>
                // --variants <y>`) and stopping here would leave them unchecked.
                descending = false;
                continue;
            }

            return new Resolution(node, token, List.of());
        }

        var legal = node.optionNamesWithAncestors();
        var unknown = options.stream().filter(option -> !legal.contains(option)).distinct().toList();

        return new Resolution(node, null, unknown);
    }

    /// Documentation sometimes names several options at once in a prose shorthand, as in
    /// `aether cluster scale --source/--role/--count`. That token is three real options written as one
    /// and is not an invocation anybody types, so it is skipped rather than reported as a missing
    /// option called `--source/--role/--count`.
    private static boolean isProseShorthand(String name) {
        return name.indexOf('/') >= 0 || name.indexOf(',') >= 0;
    }

    /// Only long options (`--name`) and single-character short options (`-c`) are validated.
    ///
    /// Deliberately NOT validated: multi-character single-dash tokens. In shell text those are
    /// ambiguous between a clustered short-option group (`-rf`) and a long option written with one
    /// dash, and neither reading can be confirmed from the document. Reporting them would produce
    /// findings the reader cannot act on. This is a stated gap, not an oversight — see
    /// [CliDocsDriftTest#gate_blindSpots_areEnumerated].
    private static boolean isOptionToken(String token) {
        if (!token.startsWith("-") || token.length() < 2 || "--".equals(token)) {
            return false;
        }

        if (token.startsWith("--")) {
            return token.length() > 2;
        }

        return token.length() == 2 && Character.isLetter(token.charAt(1));
    }

    private static String normalizeOption(String token) {
        var equals = token.indexOf('=');

        return equals < 0
               ? token
               : token.substring(0, equals);
    }

    /// Placeholder and value shapes that can never be a subcommand name. Subcommand names in both
    /// CLIs are lower-case words joined by dashes, so anything carrying a path separator, an address
    /// separator, an assignment, a shell variable, a wildcard, a bracket, an upper-case letter or a
    /// leading digit is an argument.
    private static boolean looksLikeArgument(String token) {
        if (token.isEmpty()) {
            return true;
        }

        if (Character.isDigit(token.charAt(0))) {
            return true;
        }

        for (var index = 0; index < token.length(); index++) {
            var character = token.charAt(index);

            if (Character.isUpperCase(character) || character > 127) {
                // `character > 127` catches the typographic ellipsis in `aether streams \u2026`, which is
                // an elision marker in prose, never a command name. Both CLIs name every command in
                // lower-case ASCII with dashes.
                return true;
            }

            if ("<>[]{}/:=$*?\"'\\@,()".indexOf(character) >= 0) {
                return true;
            }
        }

        return token.endsWith(".jar") || token.endsWith(".toml") || token.endsWith(".json");
    }
}
