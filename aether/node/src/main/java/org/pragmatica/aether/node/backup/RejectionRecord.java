// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;


/// Why a restore candidate was refused (#3303): the candidate's header `(lineageId, incarnation, revision)`, its
/// identity `incarnationId`, and the `commit` it was read from (for the warning only). Kept in
/// [GitBackupRepository#REJECTION_FILE] beside the backup file and never inside its blob.
///
/// Text form (format is a guess): a version tag line, then exactly one `key=value` line per field, every line ending in a line feed. The factory
/// refuses a value that would not survive that form, so [#render] is total and [#parse] reads back what the factory built; [#parse] refuses
/// every other text.
public record RejectionRecord(String lineageId, long incarnation, long revision, String incarnationId, String commit) {
    private static final String VERSION = "rejection-record/1";

    private static final Set<String> KEYS = Set.of("lineageId", "incarnation", "revision", "incarnationId", "commit");

    /// One non-empty line: no CR, no LF, no leading or trailing space.
    private static final Pattern SINGLE_LINE = Pattern.compile("[^\\r\\n ](?:[^\\r\\n]*[^\\r\\n ])?");

    /// The refusal of a value the text form cannot carry.
    public record InvalidValue(String field, String message) implements Cause {
        static final Fn1<InvalidValue, String> FACTORY = Causes.forOneValue("rejection record %s is out of form",
                                                                            InvalidValue::new);
    }

    public static Result<RejectionRecord> rejectionRecord(String lineageId,
                                                          long incarnation,
                                                          long revision,
                                                          String incarnationId,
                                                          String commit) {
        return Result.all(singleLine("lineageId", lineageId),
                          unsigned("incarnation", incarnation),
                          unsigned("revision", revision),
                          singleLine("incarnationId", incarnationId),
                          singleLine("commit", commit)).map((lineage, inc, rev, identity, from) -> new RejectionRecord(lineage,
                                                                                                                       inc,
                                                                                                                       rev,
                                                                                                                       identity,
                                                                                                                       from));
    }

    private static Result<String> singleLine(String field, String value) {
        return Verify.ensure(value, Is::matches, SINGLE_LINE, InvalidValue.FACTORY.apply(field));
    }

    private static Result<Long> unsigned(String field, long value) {
        return Verify.ensure(value, number -> number >= 0, InvalidValue.FACTORY.apply(field));
    }

    /// The file text: the version tag, then `key=value` per field, each line ending in a line feed.
    public String render() {
        return String.join("\n",
                           VERSION,
                           "lineageId=" + lineageId,
                           "incarnation=" + incarnation,
                           "revision=" + revision,
                           "incarnationId=" + incarnationId,
                           "commit=" + commit)
             + "\n";
    }

    /// The record `text` holds. [Option#none] for every text [#render] cannot have produced: empty, no or unknown version tag, no trailing line feed,
    /// a carriage return, a line without a key, an unknown, missing or duplicate key, a number that is not strict ASCII decimal, a value the
    /// factory refuses. A present file that does not parse is a state of its own, not an absent record.
    public static Option<RejectionRecord> parse(String text) {
        return text.endsWith("\n") && text.indexOf('\r') < 0
               ? parseLines(text.substring(0, text.length() - 1).split("\n", -1))
               : Option.none();
    }

    private static Option<RejectionRecord> parseLines(String[] lines) {
        return VERSION.equals(lines[0])
               ? fieldsOf(List.of(lines).subList(1, lines.length)).flatMap(RejectionRecord::fromFields)
               : Option.none();
    }

    /// The fields of exactly the five keys, each once: five keyed lines whose keys are the key set leave no duplicate, no unknown and no missing key.
    private static Option<Map<String, String>> fieldsOf(List<String> lines) {
        return Option.some(lines)
                     .filter(all -> all.stream()
                                       .allMatch(line -> line.indexOf('=') > 0))
                     .filter(all -> all.stream()
                                       .map(line -> line.substring(0,
                                                                   line.indexOf('=')))
                                       .collect(Collectors.toSet())
                                       .equals(KEYS))
                     .filter(all -> all.size() == KEYS.size())
                     .map(RejectionRecord::byKey);
    }

    private static Map<String, String> byKey(List<String> lines) {
        return lines.stream()
                    .collect(Collectors.toMap(line -> line.substring(0,
                                                                     line.indexOf('=')),
                                              line -> line.substring(line.indexOf('=') + 1)));
    }

    private static Option<RejectionRecord> fromFields(Map<String, String> fields) {
        return Option.all(text(fields, "lineageId"),
                          number(fields, "incarnation"),
                          number(fields, "revision"),
                          text(fields, "incarnationId"),
                          text(fields, "commit")).flatMap((lineage, inc, rev, identity, from) -> rejectionRecord(lineage,
                                                                                                                 inc,
                                                                                                                 rev,
                                                                                                                 identity,
                                                                                                                 from).option());
    }

    private static Option<String> text(Map<String, String> fields, String key) {
        return Option.option(fields.get(key));
    }

    private static Option<Long> number(Map<String, String> fields, String key) {
        return text(fields, key).flatMap(value -> Number.parseUnsignedDecimalLong(value).option());
    }
}
