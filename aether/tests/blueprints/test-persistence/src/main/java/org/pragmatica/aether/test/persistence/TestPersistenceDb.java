// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Blueprint-private datasource qualifier (#598): `database.testpersistence` instead of the bare
/// `@PgSql` default `database`. Datasource names are CLUSTER-GLOBAL and the single-migrator gate
/// keys on them — with the default name this blueprint raced url-shortener for `database` when
/// cluster-A suites ran in parallel, and the loser's publish was refused 409. The name maps to
/// `schema/testpersistence/` migrations and the `[database.testpersistence]` resources section,
/// which points at this blueprint's OWN physical database — required, not cosmetic: the schema
/// history/owner tables are fixed-name-per-physical-database, so a shared physical DB would move
/// the same collision one layer down.
@ResourceQualifier(type = PgSqlConnector.class, config = "database.testpersistence")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE})
public @interface TestPersistenceDb {}
