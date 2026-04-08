# Specification: jOOQ XML Schema Export for pg-tools

**Status:** Draft — ready for implementation
**Author:** Design session 2026-04-08
**Module:** `aether/pg-tools`
**Tracking issue:** (to be created)

---

## 1. Summary

Add a build-time capability to pg-tools that emits a `jooq-schema.xml` descriptor matching jOOQ's `XMLDatabase` input format, derived from the existing in-memory `Schema` model produced by static analysis of PostgreSQL migration files.

The resulting XML becomes a static input to jOOQ's own codegen, enabling fully offline, hermetic jOOQ code generation for slices that use PostgreSQL — no Docker, no live database, no H2 round-trip, no `<forcedType>` workarounds for common PG types.

The feature is delivered as additions to two existing modules (`pg-codegen` library + `pg-maven-plugin` Maven plugin) with no new dependencies. It is off by default; users opt in via explicit Mojo goal invocation or `CodegenConfig` construction.

---

## 2. Goals

1. **Hermetic codegen.** jOOQ classes can be generated from a single tracked XML file with no live DB, no Testcontainers, no Docker, no JDBC driver, and no PostgreSQL installation at build time.
2. **Single source of truth.** The same `V*.sql` migration files that drive runtime schema provisioning drive compile-time jOOQ type generation. Schema drift between runtime and generated types is impossible by construction.
3. **Type fidelity.** Preserve native PostgreSQL types (`jsonb`, `uuid`, `timestamptz`, arrays, enums, domains) end-to-end with no intermediary translation layer.
4. **Reviewable diffs.** Schema changes show up in PRs as small, surgical XML deltas alongside the migration diff — not as thousands of lines of regenerated Java.
5. **Zero impact on non-users.** Slices that don't opt in see no new dependencies, no new plugin executions, no configuration burden.
6. **Dependency purity.** pg-tools' current clean dependency tree (only `pragmatica-lite:core` + internal modules) is preserved.

## 3. Non-goals

- **Running jOOQ codegen itself.** We only produce the XML; users wire `XMLDatabase` in their own pom exactly as they would today.
- **Supporting LiquibaseDatabase, JPADatabase, or DDLDatabase paths.**
- **Exporting routines (functions/procedures/parameters).** Deferred. Requires pg-tools to capture routine signatures, which it currently does not.
- **Multi-catalog output.** Single catalog (`DEFAULT`); multiple schemas within the catalog are supported.
- **jOOQ Pro-only XSD elements** (embedded types, visibility, synthetic identities).
- **Non-PostgreSQL dialects.** pg-tools is PG-only; this feature inherits that scope.
- **Round-trip integration testing against a live PostgreSQL via Testcontainers.** See §12 for rationale.
- **XML → pg-tools reverse direction.** One-way export.
- **Integration with the existing `@PgSql` annotation processor.** Separate concern on a separate timeline.

---

## 4. Dependency decision

**No jOOQ dependency.** The feature lives inside the existing `pg-codegen` and `pg-maven-plugin` modules. XML is emitted via hand-written Stax (`XMLStreamWriter`, JDK built-in); validation against jOOQ's XSD happens in tests using the JDK's built-in `javax.xml.validation` API against a vendored copy of the XSD.

### Rationale

jOOQ is not required to produce its own XML input format:

- **Emission.** XML is text. Stax handles escaping, encoding, and indentation with no external dependency.
- **Validation.** `jooq-meta-3.X.0.xsd` is vendored as a test resource. Tests validate emitted XML against the XSD using `SchemaFactory.newSchema(xsd).newValidator().validate(source)`. This catches structural drift without linking jOOQ classes.
- **Correctness.** A golden-file test harness across the `pg-test-corpus` fixtures pins the observable output per migration set.
- **Classpath hygiene.** `pg-codegen` is used as an annotation processor in user compilation. Adding jOOQ to its runtime classpath would pollute every consuming slice.

Using `jooq-meta`'s JAXB types (`org.jooq.meta.jaxb.InformationSchema`) was considered and rejected. If round-trip testing later reveals that hand-written emission is too brittle, the fallback is to spin out a separate `pg-jooq-plugin` Maven plugin that *does* depend on `jooq-meta`. The `JooqXmlExporter` public API (`Schema → Result<String>`) stays the same, so this is a mechanical migration.

---

## 5. Module placement

Two entry points, matching pg-tools' existing dual model (library + Maven plugin).

### 5.1 `pg-codegen` — library / annotation-processor path

New package: `org.pragmatica.aether.pg.codegen.jooq`

New classes:
- `JooqXmlExporter` — pure function `Schema → Result<String>`.
- `JooqXmlConfig` — record with emission options (§7).
- `JooqTypeMapper` — maps `PgType → (data_type, udt_schema, udt_name, ...)` tuple.

`CodegenConfig` gains one optional field:
```java
Option<JooqXmlConfig> jooqXmlExport  // None by default — OFF
```

`CodegenPipeline.generate(migrations)` checks the option and, if present, appends a `GeneratedFile` for the XML alongside the Java records. No behavior change when the option is absent.

### 5.2 `pg-maven-plugin` — Maven goals

Two new Mojos, both standalone goals:

- `ExportJooqXmlMojo` — goal name `export-jooq-xml`. Writes the XML to the configured output path (default: tracked under `src/main/resources/jooq/jooq-schema.xml`). No default lifecycle binding — invoked manually as `mvn pg:export-jooq-xml` after a migration change.
- `CheckJooqXmlMojo` — goal name `check-jooq-xml`. Emits the XML to a temp location, diffs against the tracked file, fails the build on mismatch. Typical binding: `verify` phase for CI drift detection.

The existing `generate` and `lint` goals are unchanged.

### 5.3 Unchanged modules

- `pg-parser` — not touched.
- `pg-schema` — not touched.
- `pg-test-corpus` — gains new golden `.xml` fixtures but no code changes.

---

## 6. Architecture

```
migration *.sql files
        │
        ▼
 pg-parser (PgSqlParser)
        │  CST
        ▼
 pg-schema (DdlAnalyzer → SchemaBuilder)
        │  Schema (immutable record)
        ▼
 pg-codegen.jooq.JooqXmlExporter ──┐
        │  ├── JooqTypeMapper      │ (shared)
        │  └── Stax emission       │
        ▼                          │
 jooq-schema.xml  ◀────────────────┘
        │
        ▼ (user's jOOQ codegen, independent)
 jooq-codegen-maven + XMLDatabase
        │
        ▼
 Generated jOOQ Java classes
```

Every layer left of `JooqXmlExporter` already exists. The only new code is `JooqXmlExporter` + `JooqTypeMapper` + `CodegenConfig` wiring + two Mojos.

---

## 7. Library API

### 7.1 `JooqXmlExporter`

```java
public interface JooqXmlExporter {
    /// Pure transform: Schema to marshalled XML string.
    static Result<String> toXml(Schema schema, JooqXmlConfig config);

    /// Convenience: writes XML to a file, creating parent directories as needed.
    static Result<Unit> writeXml(Schema schema, JooqXmlConfig config, Path target);
}
```

All public methods return `Result<T>`. Error types form a sealed `JooqXmlExportError` hierarchy:
- `UnsupportedType(PgType)` — only raised if `JooqTypeMapper` returns no mapping; by default never fires because all types fall through to `USER-DEFINED` (see §9).
- `MissingSchema(String)` — requested schema not found in input.
- `MarshalFailed(Throwable)` — Stax emission error (wrapped).
- `IoError(Throwable)` — only from `writeXml`.

### 7.2 `JooqXmlConfig`

```java
public record JooqXmlConfig(
    String catalogName,           // default ""
    String defaultSchemaName,     // default "public"
    Set<String> includedSchemas,  // default {"public"}
    String dialect,               // default "POSTGRES"
    String xsdVersion,            // default matches current jOOQ dependency at implementation time
    boolean emitEnums,            // default true
    boolean emitIndexes,          // default true
    boolean emitCheckConstraints, // default true
    boolean emitComments,         // default true
    boolean sortElements,         // default true — determinism for diffs
    boolean prettyPrint           // default true
) {
    public static JooqXmlConfig defaults() { ... }
    // Fluent withX(...) helpers in JBCT style.
}
```

### 7.3 Internal components

| Class | Responsibility |
|---|---|
| `JooqXmlExporterImpl` | Orchestrates the walk over `Schema`, delegates to sub-emitters. |
| `TableEmitter` | `Table` → `<table>`, `Column[]` → `<column>` with ordinal position assignment. |
| `ConstraintEmitter` | `Constraint[]` → `<table_constraints>`, `<key_column_usages>`, `<referential_constraints>`, `<check_constraints>`. |
| `IndexEmitter` | `Index[]` → `<indexes>`, `<index_column_usages>`. |
| `SequenceEmitter` | `Sequence[]` → `<sequences>`. |
| `EnumEmitter` | `EnumType[]` → `<enums>` (or equivalent XSD element). |
| `DomainEmitter` | `DomainType[]` → `<domains>`. |
| `JooqTypeMapper` | Pure function returning the type tuple (§9). |

All emitters are stateless helpers operating on a shared `XMLStreamWriter`.

### 7.4 Determinism

When `sortElements=true` (default):
- Tables sorted by `(schema, name)`.
- Columns sorted by declared ordinal position.
- Constraints sorted by name.
- Indexes sorted by name.
- Enum values in declaration order.
- No timestamps, user names, or environment-derived content in output.

---

## 8. Maven plugin goals

### 8.1 `export-jooq-xml`

`@Mojo(name = "export-jooq-xml", threadSafe = true)`

No default lifecycle phase binding. Invoked manually after a migration change.

**Parameters:**

| Parameter | Property | Default |
|---|---|---|
| `schemaDir` | `pg.schemaDir` | `${project.basedir}/src/main/resources/schema` |
| `outputFile` | `pg.jooq.outputFile` | `${project.basedir}/src/main/resources/jooq/jooq-schema.xml` |
| `defaultSchemaName` | `pg.jooq.defaultSchema` | `public` |
| `includedSchemas` | `pg.jooq.includedSchemas` | `public` (CSV) |
| `catalogName` | `pg.jooq.catalog` | `""` |
| `dialect` | `pg.jooq.dialect` | `POSTGRES` |
| `xsdVersion` | `pg.jooq.xsdVersion` | current at implementation time (§14) |
| `emitEnums` | `pg.jooq.emitEnums` | `true` |
| `emitIndexes` | `pg.jooq.emitIndexes` | `true` |
| `emitCheckConstraints` | `pg.jooq.emitCheckConstraints` | `true` |
| `emitComments` | `pg.jooq.emitComments` | `true` |
| `prettyPrint` | `pg.jooq.prettyPrint` | `true` |
| `skip` | `pg.jooq.skip` | `false` |

**Execute flow:**
1. Short-circuit if `skip`.
2. Enumerate `V*.sql` from `schemaDir` (Flyway-style ordering).
3. `MigrationProcessor.processAll(...)` → `Schema`.
4. Build `JooqXmlConfig` from parameters.
5. `JooqXmlExporter.writeXml(schema, config, outputFile)`.
6. On `Result.failure`, fail build with error message.
7. Log tables/columns/constraints/indexes emitted.

### 8.2 `check-jooq-xml`

`@Mojo(name = "check-jooq-xml", threadSafe = true)`

Parameters identical to `export-jooq-xml` (shared parameter set via a common base class or interface).

**Execute flow:**
1. Short-circuit if `skip`.
2. Parse migrations and build `Schema` (same as export).
3. `JooqXmlExporter.toXml(schema, config)` → in-memory String.
4. Read existing file at `outputFile`.
5. If missing: fail with message instructing `mvn pg:export-jooq-xml`.
6. If different: print first 20 lines of a unified diff and fail the build.
7. If matching: log "OK — N tables up to date" and succeed.

Typical CI binding:
```xml
<execution>
  <id>check-jooq-schema</id>
  <phase>verify</phase>
  <goals><goal>check-jooq-xml</goal></goals>
</execution>
```

### 8.3 Example slice pom.xml fragment

```xml
<build>
  <plugins>
    <!-- 1. Enforce XML freshness in CI -->
    <plugin>
      <groupId>org.pragmatica-lite.aether</groupId>
      <artifactId>pg-maven-plugin</artifactId>
      <executions>
        <execution>
          <id>check-jooq-schema</id>
          <phase>verify</phase>
          <goals><goal>check-jooq-xml</goal></goals>
        </execution>
      </executions>
      <configuration>
        <defaultSchemaName>orders</defaultSchemaName>
      </configuration>
    </plugin>

    <!-- 2. Feed the tracked XML to jOOQ's own codegen -->
    <plugin>
      <groupId>org.jooq</groupId>
      <artifactId>jooq-codegen-maven</artifactId>
      <executions>
        <execution>
          <phase>generate-sources</phase>
          <goals><goal>generate</goal></goals>
        </execution>
      </executions>
      <configuration>
        <generator>
          <database>
            <name>org.jooq.meta.xml.XMLDatabase</name>
            <properties>
              <property>
                <key>xmlFile</key>
                <value>${project.basedir}/src/main/resources/jooq/jooq-schema.xml</value>
              </property>
              <property>
                <key>dialect</key>
                <value>POSTGRES</value>
              </property>
            </properties>
            <inputSchema>orders</inputSchema>
          </database>
          <target>
            <packageName>com.example.orders.jooq</packageName>
            <directory>target/generated-sources/jooq</directory>
          </target>
        </generator>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Developer workflow after a migration change:
```
$ vim src/main/resources/schema/V005__add_column.sql
$ mvn pg:export-jooq-xml
$ git add src/main/resources/schema/V005__add_column.sql \
          src/main/resources/jooq/jooq-schema.xml
$ git commit -m "feat: add status column to orders"
```

CI runs `mvn verify`, which invokes `check-jooq-xml`, which fails the build if the developer forgot to regenerate.

---

## 9. Type mapping: `PgType → jOOQ XML`

jOOQ `XMLDatabase` with `dialect=POSTGRES` reads each column's `<data_type>`, `<udt_schema>`, and `<udt_name>` and interprets them as if they came from `information_schema.columns` on a live Postgres instance. The mapping below reproduces those values.

### 9.1 Built-in types

| `PgType` variant | `data_type` | `udt_name` | `udt_schema` | Extra fields |
|---|---|---|---|---|
| `BuiltinType("int2")` | `smallint` | `int2` | `pg_catalog` | |
| `BuiltinType("int4")` | `integer` | `int4` | `pg_catalog` | |
| `BuiltinType("int8")` | `bigint` | `int8` | `pg_catalog` | |
| `BuiltinType("numeric", p, s)` | `numeric` | `numeric` | `pg_catalog` | `numeric_precision`, `numeric_scale` |
| `BuiltinType("float4")` | `real` | `float4` | `pg_catalog` | |
| `BuiltinType("float8")` | `double precision` | `float8` | `pg_catalog` | |
| `BuiltinType("bool")` | `boolean` | `bool` | `pg_catalog` | |
| `BuiltinType("varchar", n)` | `character varying` | `varchar` | `pg_catalog` | `character_maximum_length` |
| `BuiltinType("char", n)` | `character` | `bpchar` | `pg_catalog` | `character_maximum_length` |
| `BuiltinType("text")` | `text` | `text` | `pg_catalog` | |
| `BuiltinType("uuid")` | `uuid` | `uuid` | `pg_catalog` | |
| `BuiltinType("jsonb")` | `jsonb` | `jsonb` | `pg_catalog` | |
| `BuiltinType("json")` | `json` | `json` | `pg_catalog` | |
| `BuiltinType("bytea")` | `bytea` | `bytea` | `pg_catalog` | |
| `BuiltinType("timestamp")` | `timestamp without time zone` | `timestamp` | `pg_catalog` | `datetime_precision` |
| `BuiltinType("timestamptz")` | `timestamp with time zone` | `timestamptz` | `pg_catalog` | `datetime_precision` |
| `BuiltinType("date")` | `date` | `date` | `pg_catalog` | |
| `BuiltinType("time")` | `time without time zone` | `time` | `pg_catalog` | |
| `BuiltinType("timetz")` | `time with time zone` | `timetz` | `pg_catalog` | |
| `BuiltinType("interval")` | `interval` | `interval` | `pg_catalog` | |
| `BuiltinType("inet")` | `inet` | `inet` | `pg_catalog` | |
| `BuiltinType("cidr")` | `cidr` | `cidr` | `pg_catalog` | |
| `BuiltinType("macaddr")` | `macaddr` | `macaddr` | `pg_catalog` | |

### 9.2 Composite types

| `PgType` variant | `data_type` | `udt_name` | `udt_schema` | Notes |
|---|---|---|---|---|
| `ArrayType(inner)` | `ARRAY` | `_` + inner.udt_name | inner.udt_schema | jOOQ recognizes `_int4`-style names |
| `EnumType(name)` | `USER-DEFINED` | `name` | declared schema | referenced by `<enums>` element |
| `DomainType(name, base)` | `USER-DEFINED` | `name` | declared schema | referenced by `<domains>` element |
| `CompositeType(name)` | `USER-DEFINED` | `name` | declared schema | opaque — see §11 |
| `CustomType(name)` | `USER-DEFINED` | `name` | declared schema | opaque — see §11 |

**Every type lands somewhere.** Types with no specific mapping fall through to `USER-DEFINED` with correct schema/name. jOOQ will generate an opaque placeholder class; users attach `<forcedType>` in their jOOQ codegen config if they need a specific Java mapping.

### 9.3 Column metadata

Common column fields:
- `is_nullable` → `"YES"` / `"NO"`.
- `column_default` → verbatim expression text from the migration.
- `ordinal_position` → 1-based index within the table.
- `is_identity` → `"YES"` / `"NO"` plus `identity_generation` (`ALWAYS` / `BY DEFAULT`) for identity columns.
- `is_generated` → `"ALWAYS"` / `"NEVER"` plus `generation_expression` for `GENERATED ALWAYS AS ... STORED` columns.

---

## 10. Schema → XML element mapping

Target XSD: `jooq-meta-3.X.0.xsd` (version pinned at implementation time, §14). Top-level element: `<information_schema>`.

| XML element | Source in `Schema` | Notes |
|---|---|---|
| `<catalogs>/<catalog>` | synthesized | single default catalog |
| `<schemata>/<schema>` | `Schema.schemas()` filtered by `includedSchemas` | |
| `<tables>/<table>` | `Schema.tables()` | `<table_schema>` from `Table.schema()` |
| `<columns>/<column>` | `Table.columns()` | ordered by `<ordinal_position>` |
| `<table_constraints>/<table_constraint>` | `Table.constraints()` | `PRIMARY KEY`, `UNIQUE`, `FOREIGN KEY`, `CHECK` |
| `<key_column_usages>/<key_column_usage>` | PK/FK/unique constraints | per-column rows |
| `<referential_constraints>/<referential_constraint>` | `Constraint.ForeignKey` | `<update_rule>`, `<delete_rule>` |
| `<check_constraints>/<check_constraint>` | `Constraint.Check` | `<check_clause>` verbatim |
| `<indexes>/<index>` | `Table.indexes()` minus those backing PK/unique constraints | skipped unless `emitIndexes` |
| `<index_column_usages>/<index_column_usage>` | `Index.elements()` | |
| `<sequences>/<sequence>` | `Schema.sequences()` | `data_type`, `start_value`, `increment`, `minimum_value`, `maximum_value`, `cycle_option` |
| `<enums>/<enum>` | `Schema.enumTypes()` | skipped unless `emitEnums` |
| `<domains>/<domain>` | `Schema.domainTypes()` (if pg-tools exposes) | XSD-version dependent |

**Not emitted in v1:** `<routines>`, `<parameters>` (pg-tools does not currently model routine signatures).

---

## 11. Known limitations

### 11.1 Composite types

`CREATE TYPE foo AS (a int, b text)` — exported as `USER-DEFINED` with `udt_name = foo`. jOOQ generates an opaque class; full field layout is not emitted.

**Reason.** Supporting composite types end-to-end requires extending the pg-parser to capture full composite field layouts and extending jOOQ-side type resolution. Significant effort for minimal benefit in typical slice use cases.

**Workaround.** Attach a `<forcedType>` in jOOQ codegen config mapping `foo` to a hand-written Java class.

### 11.2 Routines (functions / procedures / parameters)

Not exported in v1. pg-tools does not currently model function signatures. When added, routines will flow through as `<routines>` / `<parameters>` elements without a schema change.

### 11.3 PG-specific features outside jOOQ's XSD

- **Partitioned tables** — partition metadata lost; table emitted as an ordinary table.
- **Foreign data wrappers** — not modeled.
- **Row-level security policies** — not modeled.
- **`CREATE EXTENSION`** — silently skipped (doesn't affect column types).
- **Custom operator classes, collations** — not emitted; usually doesn't affect DML typing.

All are documented here and in the pg-tools CHANGELOG.

---

## 12. Testing strategy

Three layers, all runnable without Docker:

### 12.1 Unit tests — `JooqTypeMapper`

Exhaustive table-driven test. One row per `PgType` variant, asserting the emitted `(data_type, udt_name, udt_schema)` tuple. Covers §9.1 and §9.2 in full.

### 12.2 Unit tests — `JooqXmlExporter`

Tiny hand-built `Schema` fixtures exercising:
- Empty schema.
- Single table, single column.
- Primary key + foreign key with both cascade rules.
- Multi-column unique constraint.
- Check constraint.
- Enum type.
- Array column.
- Index variants (GIN, partial, covering).
- Multi-schema input.

Assertions via XPath queries on the emitted document.

### 12.3 Golden fixtures

Each migration set in `pg-test-corpus` gets a corresponding `.xml.golden` file under `pg-codegen/src/test/resources/golden/jooq-xml/`.

Test runs the exporter on each fixture and compares byte-for-byte against the golden. On intentional changes, a regeneration flag (`-Dpg.golden.regenerate=true`) rewrites all goldens; the resulting diff is reviewed in PR.

### 12.4 XSD validation

Vendored XSD at `pg-codegen/src/test/resources/xsd/jooq-meta-3.X.0.xsd` (version pinned per §14). A test validates every emitted fixture document against the XSD using `javax.xml.validation.SchemaFactory`. Catches structural drift when jOOQ bumps the XSD.

### 12.5 Why no round-trip test

A previous draft proposed a round-trip harness: emit XML → feed to jOOQ `XMLDatabase` → diff generated Java against codegen from a live Testcontainers PG. Rejected because:
- Requires Docker + Testcontainers + `jooq-codegen-maven` + PostgreSQL driver — exactly the dependency stack we're trying to avoid in pg-tools.
- Failure diagnostics are obscure (generated Java diffs are hard to interpret).
- Golden + XSD validation provides sufficient regression protection for the in-scope type set.
- If a real fidelity regression surfaces, a targeted unit test or fixture is cheaper to add than maintaining an always-on round-trip harness.

---

## 13. Example slice — `examples/jooq-xml-showcase`

Purpose: demonstrate jOOQ integration end-to-end with a non-trivial schema exercising the important patterns.

### 13.1 Module layout

```
examples/jooq-xml-showcase/
├── pom.xml
├── README.md
├── src/main/resources/
│   ├── schema/
│   │   ├── V001__catalog.sql      # products, categories, enum, domain
│   │   ├── V002__orders.sql       # orders, line items, FKs, check constraints
│   │   ├── V003__indexes.sql      # GIN on jsonb, partial, covering
│   │   └── V004__audit.sql        # audit table, generated columns
│   └── jooq/
│       └── jooq-schema.xml        # tracked, regenerated via mvn pg:export-jooq-xml
└── src/main/java/org/pragmatica/aether/example/jooqxml/
    ├── OrderProcessor.java         # @Slice using generated jOOQ records
    ├── CatalogReader.java          # reads products via jOOQ
    └── OrderEventProjection.java   # demonstrates JSONB + enum access
```

### 13.2 Schema patterns exercised

Each pattern is motivated — no kitchen-sink additions.

1. **Basic table** — `id BIGSERIAL PRIMARY KEY`, `created_at TIMESTAMPTZ DEFAULT now()`.
2. **Foreign key with cascade** — `line_item.order_id` → `order.id ON DELETE CASCADE`.
3. **Foreign key with SET NULL** — `product.category_id` → `category.id ON DELETE SET NULL`.
4. **Composite unique constraint** — `UNIQUE (tenant_id, slug)` on products.
5. **Check constraint** — `CHECK (price >= 0)` on products.
6. **Enum type** — `CREATE TYPE order_status AS ENUM ('pending', 'shipped', 'delivered', 'cancelled')`.
7. **JSONB column + GIN index** — `metadata JSONB` with `CREATE INDEX ... USING GIN (metadata)`.
8. **Text array** — `tags TEXT[]` on products.
9. **UUID with default** — `external_id UUID DEFAULT gen_random_uuid()`.
10. **Numeric with precision/scale** — `amount NUMERIC(19,4)`.
11. **Partial index** — `CREATE INDEX ... WHERE active = true`.
12. **Generated column** — `total NUMERIC GENERATED ALWAYS AS (qty * price) STORED`.
13. **Multi-column FK** — compound key reference on line items.
14. **Domain type** — `CREATE DOMAIN email AS TEXT CHECK (VALUE ~ '^.+@.+$')`.
15. **Non-public schema** — `orders` schema for order-related tables; `catalog` schema for products/categories.

### 13.3 README contents

- How the pipeline works (diagram from §6).
- How to regenerate the XML after a migration change.
- What jOOQ classes are produced (sample listing).
- What diffs to expect in a PR when you add a column.
- The composite-type limitation and a worked `<forcedType>` example.
- Link to the main spec document (this file).

### 13.4 pom.xml wiring

Binds `check-jooq-xml` to `verify` and `jooq-codegen-maven` to `generate-sources`.

---

## 14. Precursor task — jOOQ version bump

**Before starting Phase 1 of this feature**, land a separate precursor PR that:

1. Determines the current latest jOOQ version (free tier) at implementation time.
2. Bumps root `pom.xml` `<jooq.version>` to latest.
3. Bumps `integrations/db/pom.xml` `<jooq.version>` to latest — fixes existing drift (`3.20.10` vs root `3.20.11` as of this spec).
4. Addresses any regressions in `integrations/db/jooq` or `integrations/db/jooq-r2dbc` surfaced by the bump.
5. The XSD version string used in this feature (`JooqXmlConfig.xsdVersion` default, vendored XSD filename) is derived from the jOOQ version fixed by that PR.

This keeps the feature PR focused on the new capability. Reviewers don't have to think about two unrelated changes.

---

## 15. Phased implementation plan

### Phase 0 — precursor (separate PR)
- jOOQ version bump across root + integrations.
- Fix any regressions revealed by the bump.

### Phase 1 — core exporter
- `JooqTypeMapper` with PG built-ins (§9.1).
- `JooqXmlExporter` via Stax: tables, columns, PK/FK/unique/check constraints.
- Vendor jOOQ XSD as test resource.
- XSD validation test.
- First golden fixture.

### Phase 2 — full type coverage
- Arrays, enums, domains, sequences.
- Indexes (GIN, partial, covering).
- Identity columns, generated columns.
- Multi-schema support.
- Composite types emitted as `USER-DEFINED`.
- Expand golden fixtures across `pg-test-corpus`.

### Phase 3 — Mojo + library integration
- `ExportJooqXmlMojo` (goal: `export-jooq-xml`).
- `CheckJooqXmlMojo` (goal: `check-jooq-xml`) with unified-diff output.
- `CodegenConfig.jooqXmlExport` wiring into `CodegenPipeline`.
- Shared parameter base for the two Mojos.

### Phase 4 — example slice
- `examples/jooq-xml-showcase/` per §13.
- All 15 schema patterns.
- README with workflow + composite-type workaround.
- Verify `mvn clean install` produces working jOOQ classes and the slice compiles against them.
- Verify `check-jooq-xml` fails when the tracked XML is stale.

### Phase 5 — docs & release
- pg-tools CHANGELOG entry.
- Known limitations section (composites, routines, partitioning, FDW, RLS).
- Brief note in Aether feature catalog under Database integrations.

**Scope.** Phases 1-5 land as a single feature PR (after the Phase 0 precursor). Partial delivery isn't useful until at least Phases 1-3 are in.

---

## 16. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| pg-tools schema model missing fields jOOQ needs | medium | §10 mapping table is the gap analysis; golden + XSD validation catches remainder |
| jOOQ XMLDatabase has undocumented expectations | medium | Golden tests pin behavior; smoke test via example slice |
| Enum/domain handling varies across jOOQ versions | medium | Version pinned via Phase 0; `JooqXmlConfig.xsdVersion` honored |
| Hand-written XML drifts from XSD | low | XSD validation in tests; fallback to separate `pg-jooq-plugin` with JAXB |
| Users confuse `export` vs `check` goal binding | low | README + example slice show the canonical wiring |
| Tracked XML becomes stale without CI enforcement | low | `check-jooq-xml` bound to `verify` in example slice and documented as the recommended pattern |
| Composite-type users surprised by opaque mapping | low | Explicit limitation in §11.1 + README workaround |

---

## 17. Out-of-scope followups (not part of this spec)

- Routine signature modeling in pg-parser → unblocks `<routines>` / `<parameters>` export.
- `pg-jooq-plugin` as a separate Maven plugin depending on `jooq-meta` if hand-written emission proves brittle.
- OpenAPI schema export from `Schema` (reuses the walker pattern).
- ER diagram export.
- Migration diff reports.
- Compile-time SQL validation (`@PgSql`) against the live `Schema` model — already partially present via `QueryAnnotationProcessor`.

The `Schema` model + walker pattern established by this feature is the foundation for each of these.
