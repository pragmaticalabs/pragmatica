// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.db.migration.MigrationDialects.ExecutionDialect;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One statement of a file on the autocommit, checkpointed path (FDB-2a, #2591), with the two facts the runner acts on:
/// whether the dialect can run it in a transaction (then it commits together with its checkpoint, H3), and, for a
/// PostgreSQL `CREATE [UNIQUE] INDEX CONCURRENTLY <name> ON <table>`, the build the F8 validity check looks up.
///
/// @param text            the statement as the splitter produced it
/// @param transactional   the dialect's per-statement classification ([org.pragmatica.aether.pg.split.DialectSpec#isTransactional])
/// @param concurrentBuild the named PostgreSQL concurrent index build; none for every other statement and for an
///                        unnamed build
record AutocommitStatement(String text, boolean transactional, Option<ConcurrentBuild> concurrentBuild) {
    /// A named concurrent index build.
    ///
    /// @param index the index name as the catalog stores it (unquoted names fold to lower case)
    /// @param table the build's table exactly as written (optionally schema-qualified, quotes kept), for `to_regclass`
    record ConcurrentBuild(String index, String table) {}

    private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";

    private static final Pattern CONCURRENT_INDEX = Pattern.compile("\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\s+"
                                                                   + "(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                                                                   + "(" + IDENT
                                                                   + ")\\s+ON\\s+(?:ONLY\\s+)?"
                                                                   + "(" + IDENT
                                                                   + "(?:\\s*\\.\\s*" + IDENT
                                                                   + ")?)",
                                                                    Pattern.CASE_INSENSITIVE);

    static AutocommitStatement autocommitStatement(String text, ExecutionDialect dialect) {
        return new AutocommitStatement(text,
                                       dialect.spec().isTransactional(text),
                                       dialect == MigrationDialects.POSTGRESQL
                                       ? concurrentBuildOf(text)
                                       : none());
    }

    /// The index a named concurrent build creates, as the catalog stores it.
    Option<String> concurrentIndex() {
        return concurrentBuild.map(ConcurrentBuild::index);
    }

    private static Option<ConcurrentBuild> concurrentBuildOf(String text) {
        var matcher = CONCURRENT_INDEX.matcher(text);

        return matcher.find()
               ? some(new ConcurrentBuild(catalogName(matcher), matcher.group(2)))
               : none();
    }

    private static String catalogName(Matcher matcher) {
        var name = matcher.group(1);

        return name.startsWith("\"")
               ? name.substring(1,
                                name.length() - 1)
                     .replace("\"\"", "\"")
               : name.toLowerCase(Locale.ROOT);
    }
}
