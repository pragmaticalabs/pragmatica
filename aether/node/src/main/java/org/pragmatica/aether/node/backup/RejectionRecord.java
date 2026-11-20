// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;


/// Why a restore candidate was refused (#3303): the candidate's header `(lineageId, incarnation, revision)`, its
/// identity `incarnationId`, and the `commit` it was read from (for the warning only). Kept in
/// [GitBackupRepository#REJECTION_FILE] beside the backup file and never inside its blob.
///
/// Text form (format is a guess): a version tag line, then one `key=value` line per field. Values are single-line
/// identifiers; a value holding a line break does not survive the round trip.
public record RejectionRecord(String lineageId, long incarnation, long revision, String incarnationId, String commit) {
    private static final String VERSION = "rejection-record/1";

    public static RejectionRecord rejectionRecord(String lineageId,
                                                  long incarnation,
                                                  long revision,
                                                  String incarnationId,
                                                  String commit) {
        return new RejectionRecord(lineageId, incarnation, revision, incarnationId, commit);
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

    /// The record `text` holds. [Option#none] when `text` does not parse: empty, no or unknown version tag, a missing field, a
    /// number that is not a number. A present file that does not parse is a state of its own, not an absent record.
    public static Option<RejectionRecord> parse(String text) {
        var lines = text.lines().toList();

        return lines.isEmpty() || !VERSION.equals(lines.getFirst())
               ? Option.none()
               : fromFields(fieldsOf(lines.subList(1, lines.size())));
    }

    private static Map<String, String> fieldsOf(List<String> lines) {
        return lines.stream()
                    .filter(line -> line.indexOf('=') > 0)
                    .collect(Collectors.toMap(line -> line.substring(0,
                                                                     line.indexOf('=')),
                                              line -> line.substring(line.indexOf('=') + 1),
                                              (_, last) -> last));
    }

    private static Option<RejectionRecord> fromFields(Map<String, String> fields) {
        return Option.all(text(fields, "lineageId"),
                          number(fields, "incarnation"),
                          number(fields, "revision"),
                          text(fields, "incarnationId"),
                          text(fields, "commit")).map(RejectionRecord::rejectionRecord);
    }

    private static Option<String> text(Map<String, String> fields, String key) {
        return Option.option(fields.get(key));
    }

    private static Option<Long> number(Map<String, String> fields, String key) {
        return text(fields, key).flatMap(value -> Number.parseLong(value).option());
    }
}
