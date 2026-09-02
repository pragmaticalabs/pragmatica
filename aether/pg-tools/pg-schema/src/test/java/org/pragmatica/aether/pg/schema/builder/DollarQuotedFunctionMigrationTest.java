// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.schema.builder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #408(a) acceptance: the compile-time codegen DDL analyzer must parse a migration whose
/// dollar-quoted `CREATE FUNCTION` body contains internal `;`. Before the fix the passthrough
/// `RestOfStatement` stopped at the first body `;`, so any statement AFTER the function was never
/// recognised. Each test asserts the table declared AFTER the function is present in the built
/// schema — proof that the function body's `;` did not truncate the migration.
class DollarQuotedFunctionMigrationTest {

    @Test
    void processAll_parsesTableAfterTaggedFunctionBody_withInternalSemicolons() {
        var schema = MigrationProcessor.create()
                                       .processAll(List.of("""
                                           CREATE TABLE users (id bigint PRIMARY KEY, name text NOT NULL);
                                           CREATE FUNCTION touch() RETURNS trigger AS $BODY$
                                           BEGIN
                                               NEW.updated_at := now();
                                               RETURN NEW;
                                           END;
                                           $BODY$ LANGUAGE plpgsql;
                                           CREATE TABLE audit (id bigint PRIMARY KEY, note text NOT NULL)"""))
                                       .onFailure(cause -> fail("migration should parse: " + cause.message()))
                                       .unwrap();

        assertThat(schema.table("users").isPresent()).as("users table").isTrue();
        assertThat(schema.table("audit").isPresent()).as("audit table declared after function body").isTrue();
    }

    @Test
    void processAll_parsesTableAfterUntaggedFunctionBody_withInternalSemicolons() {
        var schema = MigrationProcessor.create()
                                       .processAll(List.of("""
                                           CREATE TABLE users (id bigint PRIMARY KEY, name text NOT NULL);
                                           CREATE FUNCTION add(a integer, b integer) RETURNS integer AS $$
                                           BEGIN
                                               RETURN a + b;
                                           END;
                                           $$ LANGUAGE plpgsql;
                                           CREATE TABLE audit (id bigint PRIMARY KEY, note text NOT NULL)"""))
                                       .onFailure(cause -> fail("migration should parse: " + cause.message()))
                                       .unwrap();

        assertThat(schema.table("users").isPresent()).as("users table").isTrue();
        assertThat(schema.table("audit").isPresent()).as("audit table declared after function body").isTrue();
    }
}
