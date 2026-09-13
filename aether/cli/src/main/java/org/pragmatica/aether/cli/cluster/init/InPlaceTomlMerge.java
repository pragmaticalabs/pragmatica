// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// #311 — merges the config `init` generates INTO an existing, hand-edited file by rewriting lines
/// in place, never by re-emitting a parsed model: a round-trip through the writer drops every
/// comment, re-spells every value and re-orders every section, which is more destructive than the
/// abort it replaced. Here the parsers are used only to LOCATE and COMPARE — the text that is
/// written is the operator's text, with exactly these edits:
///
/// - an init-owned key (one the generated config carries) whose parsed value differs is a
///   [Change], rewritten in place — value only, key spelling and trailing comment kept — and only
///   when the caller applies changes (batch `--merge`, or the operator's yes);
/// - an init-owned key the file lacks is appended into its section, and a section the file lacks
///   is inserted after the nearest preceding generated block the file has (else at the end);
/// - a generated `[[…]]` element the file lacks is appended after the file's last element of that
///   array; an element is "present" when an existing one matches it on every key but
///   `description`, so an operator's re-described rule is not duplicated;
/// - everything else — comments, blank lines, operator keys, operator sections and elements,
///   section order, value spelling — is left byte-for-byte, and listed as kept when init does not
///   generate it, because a merge cannot tell a hand-added key from one init used to generate.
///
/// Nothing is ever removed. A changed answer that used to generate an element (a different
/// `--admin-cidr`) therefore ADDS the new rule and keeps the old one listed; removing it is the
/// operator's edit or `--force`.
public sealed interface InPlaceTomlMerge {
    /// An init-owned key whose value in the existing file differs from the new answer.
    record Change(String path, String oldValue, String newValue) {
        @Override
        public String toString() {
            return path + ": " + oldValue + " → " + newValue;
        }
    }

    /// The merge, computed but not applied. `changes` need consent; `added` and `kept` do not.
    record Plan(List<Change> changes, List<String> added, List<String> kept, List<String> lines, List<Edit> edits) {
        /// The merged text. Without `applyChanges`, the in-place rewrites are skipped and the
        /// existing values stay; additions are applied either way.
        public String render(boolean applyChanges) {
            var result = new ArrayList<>(lines);

            edits.stream()
                 .filter(edit -> applyChanges || !edit.rewrite())
                 .sorted(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).reversed())
                 .forEach(edit -> {
                     result.subList(edit.start(), edit.end()).clear();
                     result.addAll(edit.start(), edit.replacement());
                 });

            return String.join("\n", result);
        }
    }

    /// Replace lines `[start, end)` with `replacement`; `start == end` inserts. A rewrite carries a
    /// [Change]; an addition does not.
    record Edit(int start, int end, List<String> replacement, boolean rewrite) {}

    /// Plans the merge of `generated` (init's own output, always parseable) into `existingText`,
    /// whose parse is `existing`. The result parses again — checked here — or the plan is refused.
    static Result<Plan> plan(String existingText, TomlDocument existing, String generated) {
        return TomlParser.parse(generated)
                         .flatMap(fresh -> plan(existingText, existing, generated, fresh));
    }

    private static Result<Plan> plan(String existingText, TomlDocument existing, String generated, TomlDocument fresh) {
        var lines = List.of(existingText.split("\n", -1));
        var genLines = List.of(generated.split("\n", -1));
        var index = Index.of(lines);
        var genIndex = Index.of(genLines);

        if (!index.elementCountsMatch(existing) || !genIndex.elementCountsMatch(fresh)) {
            return new ClusterInitError.MergeError("line index and parsed document disagree on table-array elements").result();
        }

        var changes = new ArrayList<Change>();
        var added = new ArrayList<String>();
        var edits = new ArrayList<Edit>();
        var insertions = new TreeMap<Integer, List<String>>();

        for (var name : genIndex.blockOrder) {
            if (genIndex.arrays.containsKey(name)) {
                planArray(name, lines, genLines, index, genIndex, existing, fresh, added, insertions);
            } else {
                planSection(name, lines, genLines, index, genIndex, existing, fresh, changes, added, edits, insertions);
            }
        }

        insertions.forEach((at, block) -> edits.add(new Edit(at, at, block, false)));
        var plan = new Plan(List.copyOf(changes), List.copyOf(added), kept(index, existing, fresh), lines, List.copyOf(edits));

        return TomlParser.parse(plan.render(true))
                         .flatMap(_ -> TomlParser.parse(plan.render(false)))
                         .mapError(cause -> new ClusterInitError.MergeError("the merged text does not parse: " + cause.message()))
                         .map(_ -> plan);
    }

    private static void planSection(String name,
                                    List<String> lines,
                                    List<String> genLines,
                                    Index index,
                                    Index genIndex,
                                    TomlDocument existing,
                                    TomlDocument fresh,
                                    List<Change> changes,
                                    List<String> added,
                                    List<Edit> edits,
                                    TreeMap<Integer, List<String>> insertions) {
        var freshValues = fresh.sections().getOrDefault(name, Map.of());
        var existingValues = existing.sections().getOrDefault(name, Map.of());
        var missing = new ArrayList<String>();

        for (var entry : genIndex.keys.getOrDefault(name, new LinkedHashMap<>()).entrySet()) {
            var key = entry.getKey();
            var genLine = entry.getValue();
            var newValue = genLines.get(genLine.start()).substring(genLine.valueStart(), genLine.valueEnd());
            var path = dotted(name, key);
            var present = Option.option(index.keys.getOrDefault(name, new LinkedHashMap<>()).get(key));

            if (present.isEmpty()) {
                missing.add(genLines.get(genLine.start()));
                added.add(path);
                continue;
            }

            var line = present.unwrap();

            if (Objects.equals(existingValues.get(key), freshValues.get(key))) {
                continue;
            }

            var oldValue = line.start() == line.end()
                           ? lines.get(line.start()).substring(line.valueStart(), line.valueEnd())
                           : String.join("\\n", lines.subList(line.start(), line.end() + 1));
            var rewritten = line.start() == line.end()
                            ? lines.get(line.start()).substring(0, line.valueStart()) + newValue + lines.get(line.start()).substring(line.valueEnd())
                            : lines.get(line.start()).substring(0, line.valueStart()) + newValue;

            changes.add(new Change(path, oldValue, newValue));
            edits.add(new Edit(line.start(), line.end() + 1, List.of(rewritten), true));
        }

        if (missing.isEmpty()) {
            return;
        }

        var section = Option.option(index.sections.get(name));

        if (section.isPresent() && (!name.isEmpty() || section.unwrap().lastContent >= 0)) {
            insertions.computeIfAbsent(section.unwrap().lastContent + 1, _ -> new ArrayList<>()).addAll(missing);
            return;
        }

        var block = new ArrayList<String>();

        if (name.isEmpty()) {
            block.addAll(missing);
            block.add("");
            insertions.computeIfAbsent(index.rootInsertionPoint(lines), _ -> new ArrayList<>()).addAll(block);
            return;
        }

        var genBlock = genIndex.sections.get(name);

        block.add("");
        block.addAll(genLines.subList(genBlock.header, genBlock.lastContent + 1));
        insertions.computeIfAbsent(index.insertionPointAfter(name, genIndex, lines), _ -> new ArrayList<>()).addAll(block);
    }

    private static void planArray(String name,
                                  List<String> lines,
                                  List<String> genLines,
                                  Index index,
                                  Index genIndex,
                                  TomlDocument existing,
                                  TomlDocument fresh,
                                  List<String> added,
                                  TreeMap<Integer, List<String>> insertions) {
        var existingElements = existing.getTableArray(name).or(List.of());
        var freshElements = fresh.getTableArray(name).or(List.of());
        var genBlocks = genIndex.arrays.get(name);
        var block = new ArrayList<String>();

        for (int i = 0; i < freshElements.size(); i++) {
            var element = freshElements.get(i);

            if (existingElements.stream().anyMatch(candidate -> sameIdentity(candidate, element))) {
                continue;
            }

            block.add("");
            block.addAll(genLines.subList(genBlocks.get(i).header, genBlocks.get(i).lastContent + 1));
            added.add(elementPath(name, element));
        }

        if (block.isEmpty()) {
            return;
        }

        var at = Option.option(index.arrays.get(name))
                       .map(elements -> elements.getLast().lastContent + 1)
                       .or(() -> index.insertionPointAfter(name, genIndex, lines));

        insertions.computeIfAbsent(at, _ -> new ArrayList<>()).addAll(block);
    }

    private static List<String> kept(Index index, TomlDocument existing, TomlDocument fresh) {
        var kept = new ArrayList<String>();

        for (var section : index.keys.entrySet()) {
            var freshValues = fresh.sections().getOrDefault(section.getKey(), Map.of());

            section.getValue()
                   .keySet()
                   .stream()
                   .filter(key -> !freshValues.containsKey(key))
                   .forEach(key -> kept.add(dotted(section.getKey(), key)));
        }

        for (var name : index.arrays.keySet()) {
            var freshElements = fresh.getTableArray(name).or(List.of());

            existing.getTableArray(name)
                    .or(List.of())
                    .stream()
                    .filter(element -> freshElements.stream().noneMatch(candidate -> sameIdentity(candidate, element)))
                    .forEach(element -> kept.add(elementPath(name, element)));
        }

        return List.copyOf(kept);
    }

    private static boolean sameIdentity(Map<String, Object> left, Map<String, Object> right) {
        return identity(left).equals(identity(right));
    }

    private static Map<String, Object> identity(Map<String, Object> element) {
        return element.entrySet()
                      .stream()
                      .filter(entry -> !"description".equals(entry.getKey()))
                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String elementPath(String name, Map<String, Object> element) {
        return name + identity(element).entrySet()
                                       .stream()
                                       .map(entry -> entry.getKey() + "=" + (entry.getValue() instanceof String s
                                                                             ? "\"" + s + "\""
                                                                             : entry.getValue()))
                                       .sorted()
                                       .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String dotted(String section, String key) {
        return section.isEmpty()
               ? key
               : section + "." + key;
    }

    /// One `key = value` line (or a multi-line value's span, `start < end`); `valueStart`/`valueEnd`
    /// bound the value on the start line, excluding a trailing comment, and mean nothing for a span.
    record KeyLine(int start, int end, int valueStart, int valueEnd) {}

    /// A `[section]` or one `[[array]]` element: its header line and the last non-blank,
    /// non-comment line before the next header — where an appended key goes.
    final class Block {
        final int header;
        int lastContent;

        Block(int header) {
            this.header = header;
            this.lastContent = header;
        }
    }

    /// Where every section, array element and key sits in a text — the line-level twin of what
    /// [TomlParser] returns. Mirrors the parser's line rules (trim, `#`, `[[…]]`, `[…]`, the four
    /// key forms, `"""`/`'''`/`[` continuations) so that a text the parser accepted is indexed the
    /// way the parser read it; `elementCountsMatch` is the tripwire for a divergence.
    final class Index {
        private static final Pattern SECTION = Pattern.compile("^\\[([a-zA-Z0-9_.\\-]+|\"[^\"]*\"|'[^']*')]$");
        private static final Pattern ARRAY = Pattern.compile("^\\[\\[([a-zA-Z0-9_.\\-]+|\"[^\"]*\"|'[^']*')]]$");
        private static final Pattern QUOTED_KEY = Pattern.compile("^\\s*\"([^\"]*)\"\\s*=\\s*");
        private static final Pattern LITERAL_KEY = Pattern.compile("^\\s*'([^']*)'\\s*=\\s*");
        private static final Pattern DOTTED_KEY = Pattern.compile("^\\s*([a-zA-Z0-9_.\\-]+)\\s*=\\s*");

        final Map<String, Block> sections = new LinkedHashMap<>();
        final Map<String, List<Block>> arrays = new LinkedHashMap<>();
        final Map<String, Map<String, KeyLine>> keys = new LinkedHashMap<>();
        final List<String> blockOrder = new ArrayList<>();

        private Index() {
            sections.put("", new Block(-1));
            blockOrder.add("");
        }

        static Index of(List<String> lines) {
            var index = new Index();
            var current = index.sections.get("");
            var section = "";
            Option<String> arrayBase = none();

            for (int i = 0; i < lines.size(); i++) {
                var raw = lines.get(i);
                var line = raw.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                var arrayHeader = ARRAY.matcher(line);

                if (arrayHeader.matches()) {
                    var name = unquote(arrayHeader.group(1));

                    current = new Block(i);
                    index.arrays.computeIfAbsent(name, _ -> new ArrayList<>()).add(current);
                    index.rememberBlock(name);
                    arrayBase = some(name);
                    section = name;
                    continue;
                }

                var sectionHeader = SECTION.matcher(line);

                if (sectionHeader.matches()) {
                    var name = unquote(sectionHeader.group(1));
                    var base = arrayBase;

                    if (base.filter(b -> name.startsWith(b + ".")).isPresent()) {
                        section = name;
                        current.lastContent = i;
                        continue;
                    }

                    var header = new Block(i);

                    current = index.sections.computeIfAbsent(name, _ -> header);
                    index.rememberBlock(name);
                    arrayBase = none();
                    section = name;
                    continue;
                }

                var keyValue = keyValue(raw);

                if (keyValue.isEmpty()) {
                    continue;
                }

                var kv = keyValue.unwrap();
                var end = spanEnd(lines, i, raw.substring(kv.valueStart()).trim());

                current.lastContent = end;
                if (arrayBase.isEmpty()) {
                    var effectiveSection = section;
                    var effective = kv.targetSection()
                                      .map(target -> effectiveSection.isEmpty()
                                                     ? target
                                                     : effectiveSection + "." + target)
                                      .or(section);
                    var valueEnd = end == i
                                   ? kv.valueStart() + stripInlineComment(raw.substring(kv.valueStart())).length()
                                   : raw.length();

                    index.keys.computeIfAbsent(effective, _ -> new LinkedHashMap<>())
                              .put(kv.key(), new KeyLine(i, end, kv.valueStart(), valueEnd));
                }

                i = end;
            }

            return index;
        }

        private void rememberBlock(String name) {
            if (!blockOrder.contains(name)) {
                blockOrder.add(name);
            }
        }

        boolean elementCountsMatch(TomlDocument document) {
            return arrays.entrySet()
                         .stream()
                         .allMatch(entry -> document.getTableArray(entry.getKey())
                                                    .map(List::size)
                                                    .or(0) == entry.getValue().size())
                   && document.tableArrayNames().stream().allMatch(arrays::containsKey);
        }

        /// The line after the last block (section or array) that precedes `name` in the generated
        /// order and exists here — so an inserted section lands where init would have put it — or
        /// the end of the file when none does.
        int insertionPointAfter(String name, Index generated, List<String> lines) {
            var order = generated.blockOrder;

            for (int i = order.indexOf(name) - 1; i > 0; i--) {
                var candidate = order.get(i);

                if (sections.containsKey(candidate)) {
                    return sections.get(candidate).lastContent + 1;
                }

                if (arrays.containsKey(candidate)) {
                    return arrays.get(candidate).getLast().lastContent + 1;
                }
            }

            return endOfFile(lines);
        }

        /// Root keys go after the file's existing root keys, else before its first header (after
        /// any leading comment block), else at the end.
        int rootInsertionPoint(List<String> lines) {
            var root = sections.get("");

            if (root.lastContent >= 0) {
                return root.lastContent + 1;
            }

            return blockOrder.size() > 1
                   ? firstHeader()
                   : endOfFile(lines);
        }

        private int firstHeader() {
            var name = blockOrder.get(1);

            return sections.containsKey(name)
                   ? sections.get(name).header
                   : arrays.get(name).getFirst().header;
        }

        private static int endOfFile(List<String> lines) {
            return !lines.isEmpty() && lines.getLast().isEmpty()
                   ? lines.size() - 1
                   : lines.size();
        }

        private static String unquote(String name) {
            return (name.startsWith("\"") && name.endsWith("\"")) || (name.startsWith("'") && name.endsWith("'"))
                   ? name.substring(1, name.length() - 1)
                   : name;
        }

        record KeyValue(String key, Option<String> targetSection, int valueStart) {}

        private static Option<KeyValue> keyValue(String raw) {
            var quoted = QUOTED_KEY.matcher(raw);

            if (quoted.find()) {
                return some(new KeyValue(quoted.group(1), none(), quoted.end()));
            }

            var literal = LITERAL_KEY.matcher(raw);

            if (literal.find()) {
                return some(new KeyValue(literal.group(1), none(), literal.end()));
            }

            var dotted = DOTTED_KEY.matcher(raw);

            if (!dotted.find()) {
                return none();
            }

            var fullKey = dotted.group(1);
            var lastDot = fullKey.lastIndexOf('.');

            return lastDot > 0
                   ? some(new KeyValue(fullKey.substring(lastDot + 1), some(fullKey.substring(0, lastDot)), dotted.end()))
                   : some(new KeyValue(fullKey, none(), dotted.end()));
        }

        /// The last line of the value starting at `start`, mirroring the parser's continuation
        /// rules: an unclosed `"""`/`'''` runs to the line holding the closing delimiter, an
        /// unbalanced `[` to the line that balances it.
        private static int spanEnd(List<String> lines, int start, String rawValue) {
            if (rawValue.startsWith("\"\"\"") || rawValue.startsWith("'''")) {
                var delimiter = rawValue.substring(0, 3);

                if (rawValue.substring(3).contains(delimiter)) {
                    return start;
                }

                for (int j = start + 1; j < lines.size(); j++) {
                    if (lines.get(j).contains(delimiter)) {
                        return j;
                    }
                }

                return lines.size() - 1;
            }

            if (rawValue.startsWith("[") && !isArrayComplete(rawValue)) {
                var accumulated = new StringBuilder(rawValue);

                for (int j = start + 1; j < lines.size(); j++) {
                    accumulated.append('\n').append(stripInlineComment(lines.get(j)));
                    if (isArrayComplete(accumulated.toString())) {
                        return j;
                    }
                }

                return lines.size() - 1;
            }

            return start;
        }

        private static boolean isArrayComplete(String value) {
            var depth = 0;
            var inDouble = false;
            var inSingle = false;

            for (int i = 0; i < value.length(); i++) {
                var c = value.charAt(i);

                if (c == '\\' && i + 1 < value.length() && inDouble) {
                    i++;
                    continue;
                }

                if (c == '"' && !inSingle) {
                    inDouble = !inDouble;
                } else if (c == '\'' && !inDouble) {
                    inSingle = !inSingle;
                } else if (!inDouble && !inSingle) {
                    if (c == '[') depth++; else if (c == ']') depth--;
                }
            }

            return depth == 0;
        }

        /// The value with any trailing `# comment` and whitespace removed (quotes and brackets
        /// respected), as the parser strips it.
        private static String stripInlineComment(String value) {
            var inDouble = false;
            var inSingle = false;
            var depth = 0;

            for (int i = 0; i < value.length(); i++) {
                var c = value.charAt(i);

                if (inDouble && c == '\\' && i + 1 < value.length()) {
                    i++;
                    continue;
                }

                if (c == '"' && !inSingle) {
                    inDouble = !inDouble;
                } else if (c == '\'' && !inDouble) {
                    inSingle = !inSingle;
                } else if (!inDouble && !inSingle) {
                    if (c == '[' || c == '{') {
                        depth++;
                    } else if (c == ']' || c == '}') {
                        depth--;
                    } else if (c == '#' && depth == 0) {
                        return value.substring(0, i).stripTrailing();
                    }
                }
            }

            return value.stripTrailing();
        }
    }

    record unused() implements InPlaceTomlMerge {}
}
