// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.pragmatica.aether.pg.split.DialectSpec.CommentRules;
import org.pragmatica.aether.pg.split.DialectSpec.CopyDataRules;
import org.pragmatica.aether.pg.split.DialectSpec.DollarQuoteRules;
import org.pragmatica.aether.pg.split.DialectSpec.IdentifierRules;
import org.pragmatica.aether.pg.split.DialectSpec.StringRules;
import org.pragmatica.aether.pg.split.DialectSpec.BoundaryRules;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.none;

/// Catalog of populated [DialectSpec] descriptors. This step ships PostgreSQL only.
public sealed interface Dialects {
    record unused() implements Dialects {}

    /// Pattern matching the leading non-transactional command of a PostgreSQL statement,
    /// after leading whitespace and comments have been stripped. Case-insensitive.
    Pattern NON_TRANSACTIONAL = Pattern.compile(
        "^(?:"
        + "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b"   // CREATE INDEX CONCURRENTLY
        + "|CREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*\\bCONCURRENTLY\\b"  // CREATE … INDEX … CONCURRENTLY
        + "|DROP\\s+INDEX\\s+CONCURRENTLY\\b"
        + "|REINDEX\\b.*\\bCONCURRENTLY\\b"
        + "|VACUUM\\b"
        + "|ALTER\\s+TYPE\\b.*\\bADD\\s+VALUE\\b"
        + "|CREATE\\s+DATABASE\\b"
        + "|DROP\\s+DATABASE\\b"
        + ").*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /// Strips leading whitespace, `--` line comments, and `/*…*/` block comments so the
    /// transactional classifier sees the first real command keyword.
    Pattern LEADING_NOISE = Pattern.compile(
        "^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)+",
        Pattern.DOTALL);

    /// The fully-populated PostgreSQL dialect descriptor — the only dialect this step ships.
    DialectSpec POSTGRESQL = new DialectSpec(
        new StringRules(true, false, true, false, false),
        new IdentifierRules(true, false, false),
        new CommentRules(true, false, true, true),
        new DollarQuoteRules(true),
        new BoundaryRules(none(), none(), none()),
        new CopyDataRules(true, "\\."),
        Dialects::isPostgresTransactional);

    /// Classifies a PostgreSQL statement as transactional or not.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return `false` for the non-transactional command set, `true` otherwise
    static boolean isPostgresTransactional(String statementText) {
        return !NON_TRANSACTIONAL.matcher(stripLeadingNoise(statementText)).matches();
    }

    /// Removes leading whitespace and comments before the first command keyword.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return text starting at the first significant command keyword
    static String stripLeadingNoise(String statementText) {
        return LEADING_NOISE.matcher(statementText).replaceFirst("");
    }
}
