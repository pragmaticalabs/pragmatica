// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.parser;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins #3674: a DDL parse failure reports the line and column of the furthest failing token,
/// never the start of the statement. The enforcer is the single error path,
/// `PostgresParser.ParseError.message()`.
class ParseErrorPositionTest {
    private static final PostgresParser PARSER = PostgresParser.create();
    private static final String FIRST = "CREATE TABLE a (id INT);\n";

    private static final Pattern POSITION = Pattern.compile(" at (\\d+:\\d+)(?: \\(\\+\\d+ more\\))?$");

    private static String positionOf(String sql) {
        var message = PARSER.parseCst(sql)
                            .fold(cause -> cause.message(), _ -> "parsed");
        var matcher = POSITION.matcher(message);

        return matcher.find()
               ? matcher.group(1)
               : "no position in [" + message + "]";
    }

    private static void assertFailsAt(String sql, String position) {
        assertThat(positionOf(sql)).as("parse of [%s]", sql)
                                   .isEqualTo(position);
    }

    @Test
    void unknownTypeName_reportsTypeTokenPosition() {
        assertFailsAt("CREATE TABLE t (id BIGINT PRIMARY KEY, ratio 123)", "1:46");
    }

    @Test
    void unknownKeywordAfterType_reportsKeywordPosition() {
        assertFailsAt("CREATE TABLE t (id BIGINT FOO)", "1:27");
    }

    @Test
    void trailingGarbage_reportsGarbagePosition() {
        assertFailsAt("CREATE TABLE t (id BIGINT) GARBAGE", "1:28");
    }

    @Test
    void unknownTypeNameInSecondStatement_reportsLineAndColumn() {
        assertFailsAt(FIRST + "CREATE TABLE t (\n id 123)", "3:5");
    }

    @Test
    void unknownKeywordAfterTypeInSecondStatement_reportsLineAndColumn() {
        assertFailsAt(FIRST + "CREATE TABLE t (id BIGINT FOO)", "2:27");
    }

    @Test
    void trailingGarbageInSecondStatement_reportsLineAndColumn() {
        assertFailsAt(FIRST + "CREATE TABLE t (id BIGINT) GARBAGE", "2:28");
    }
}
