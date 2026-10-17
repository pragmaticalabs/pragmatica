# Changelog

> From 1.0.0-alpha the project is versioned as one monorepo. Release history is in the root [CHANGELOG.md](../../CHANGELOG.md); the entry below is the module's initial development entry, not a shipped version.

## Initial development entry - 2026-03-30

### Added
- Full PostgreSQL PEG grammar (~500 rules) covering DDL, DML, expressions, all data types
- PostgreSQL lexer/parser/visitor generated from the grammar at build time by peglib (`peglib-maven-plugin`); not committed, and `pg-parser` depends on `peglib-runtime` at compile scope
- CST navigator and extractor for typed AST extraction (Identifier, QualifiedName, DataTypeName)
- Event-sourced schema model with 25 event types (Table, Column, Constraint, Index, Sequence, Type)
- Schema builder: pure fold over events producing immutable schema snapshots
- DDL analyzer: SQL → parse → schema events for CREATE/ALTER/DROP TABLE, INDEX, SEQUENCE, TYPE, SCHEMA, EXTENSION
- Migration processor: end-to-end pipeline from SQL migration files to schema snapshot
- Query validator: table/column/alias resolution against known schema
- Migration linter with 41 rules across 4 categories:
  - Lock Hazards (PG001-PG013): NOT NULL without DEFAULT, INDEX without CONCURRENTLY, unsafe type changes, SET NOT NULL, constraint locks, RENAME, DROP COLUMN
  - Type Design (PG101-PG112): char(n), timestamp without tz, money, serial, json, integer PK, float, timetz, varchar limits, enum warnings
  - Schema Design (PG201-PG208): missing PK, FK without index, unnamed constraints, duplicate indexes, wide indexes, missing updated_at, uppercase names, reserved words
  - Migration Practice (PG301-PG308): DROP without IF EXISTS, referenced column drops, FK index hints, volatile defaults, enum transaction restrictions, schema cascade
- Java record and enum code generation from schema with configurable output directory
- Type mapper: 40+ PostgreSQL → Java type mappings
- Test corpus: Flyway-compatible migration examples (simple, multi-table, alterations, pg-specific)
- Modules at the time of the current tree: `pg-parser`, `pg-schema`, `pg-codegen`, `pg-maven-plugin`, `pg-test-corpus`, `sql-splitter`
