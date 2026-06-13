# Interactive Project Scaffolding — Design Spec

| Field   | Value                                       |
|---------|---------------------------------------------|
| Status  | Approved — ready for implementation         |
| Date    | 2026-04-18                                  |
| Modules | `jbct/jbct-init`, `jbct/jbct-cli`           |

---

## 1. Overview

A template-based project scaffolding system for the JBCT CLI that supports both interactive step-by-step mode and batch mode (CLI flags). Replaces the current hardcoded initializers with a composable template engine where each feature (persistence, routing, HTTP client, etc.) contributes fragments to named slots. The same templates serve both `jbct init` (fresh project) and `jbct add` (augment existing project).

**Design principles:**
- Templates are data, not code — adding a feature means adding template files, not Java classes
- Fragment merging via named slots — templates contribute to shared files without knowing about each other
- Same templates, two modes — `init` renders fresh, `add` merges into existing
- Interactive and batch — step-by-step with back navigation, or `--with-*` flags for CI/scripting
- Any `--with-*` flag implies Aether slice mode

---

## 2. User Experience

### 2.1 Batch Mode (CLI Flags)

```bash
# Minimal slice project
jbct init my-service

# With specific features
jbct init my-service --group-id com.acme --with-web --with-persistence --with-http-client

# Add feature to existing project
jbct add persistence
jbct add http-client --service payments
```

### 2.2 Interactive Mode

Activates when no component flags are provided:

```
$ jbct init my-order-service

Step 1/N: Project basics
  Group ID [com.example]: com.acme
  Artifact ID [my-order-service]: ↵
  Slice name [HelloWorld]: OrderProcessor

Step 2/N: Select features
  1. Web (HTTP routing + k6 load tests)
  2. PostgreSQL persistence
  3. HTTP client (outbound calls)
  4. Pub/Sub messaging
  5. Event streaming
  6. Scheduled tasks
  Select (comma-separated, 'b' for back): 1,2,3

Step 3/N: PostgreSQL persistence
  Connector type: [1] Generic @Sql / [2] PostgreSQL @PgSql
  > 2
  (or 'b' for back)

Step 4/N: HTTP client
  Service names (comma-separated): payments, inventory
  (or 'b' for back)

Step 5/N: Review
  Project: com.acme:my-order-service
  Slice: OrderProcessor
  Features: web, persistence (@PgSql), http-client (payments, inventory)

  Confirm? [Y/n/b]:

Creating project...
  pom.xml
  src/main/java/com/acme/order/OrderProcessor.java
  src/main/java/com/acme/order/annotation/Sql.java
  src/main/java/com/acme/order/annotation/Payments.java
  src/main/java/com/acme/order/annotation/Inventory.java
  src/main/java/com/acme/order/PaymentApi.java
  src/main/java/com/acme/order/InventoryApi.java
  src/main/resources/com/acme/order/routes.toml
  src/main/resources/resources.toml
  src/test/java/com/acme/order/OrderProcessorTest.java
  schema/V001__create_tables.sql
  k6/load-test.js
  run-forge.sh
  start-postgres.sh
  jbct.toml
  .claude/CLAUDE.md

Done! Next: cd my-order-service && ./run-forge.sh
```

---

## 3. Template System

### 3.1 Template Structure

Each template is a named collection of slot contributions. Templates are classpath resources inside the `jbct-init` JAR.

```
templates/
  base/
    template.toml              # descriptor
    files/
      .gitignore
      jbct.toml
  slice/
    template.toml
    files/
      ${SliceName}.java.tpl
  aether/
    template.toml
    files/
      run-forge.sh
      forge.toml
  web/
    template.toml
    files/
      routes.toml.tpl
      k6/load-test.js.tpl
  persistence/
    template.toml
    files/
      schema/V001__create_tables.sql.tpl
      start-postgres.sh
  http-client/
    template.toml
    files/
      ${ServiceName}Api.java.tpl
  ...
```

### 3.2 Template Descriptor

Each template has a `template.toml` declaring its metadata:

```toml
name = "persistence"
description = "PostgreSQL persistence support"
flag = "--with-persistence"
min_version = "1.0.0-rc1"

# Templates that must be active for this one to work
dependencies = ["slice", "aether"]

# Variables this template needs (collected during interactive step)
[variables]
connectorType = { prompt = "Connector type", options = ["@Sql (generic)", "@PgSql (PostgreSQL)"], default = "@PgSql" }

# Sub-options
[suboptions]
connectorType.sql = { annotation = "Sql", resourceType = "SqlConnector", import = "...resource.db.SqlConnector" }
connectorType.pgsql = { annotation = "PgSql", resourceType = "PgSqlConnector", import = "...resource.db.pg.PgSqlConnector" }
```

### 3.3 Slot Catalog

Templates contribute fragments to named slots. The engine collects all contributions and renders target files.

| Slot | Target | Merge strategy |
|------|--------|----------------|
| `pom-dependencies` | pom.xml | DOM: append to `<dependencies>`, deduplicate by groupId:artifactId |
| `pom-plugins` | pom.xml | DOM: append to `<build><plugins>`, deduplicate |
| `pom-properties` | pom.xml | DOM: append to `<properties>`, deduplicate |
| `imports` | SliceName.java | Deduplicated, sorted |
| `qualifier-annotations` | annotation/*.java | One file per annotation |
| `factory-parameters` | SliceName.java | Comma-joined in factory signature |
| `factory-body-comments` | SliceName.java | `IMPLEMENTME` comments per resource |
| `slice-methods` | SliceName.java | Appended to interface body |
| `resources-toml-sections` | resources.toml | Appended sections |
| `resources-toml-comments` | resources.toml | Commented-out examples |
| `routes-toml-entries` | routes.toml | Appended entries |
| `jbct-toml-sections` | jbct.toml | Merged sections |
| `test-mock-stubs` | SliceNameTest.java | Mock implementations per resource |
| `test-imports` | SliceNameTest.java | Test-specific imports |
| `extra-files` | various | Written as-is, with executable flag |
| `ai-skills` | .claude/ | Skill files to include |

### 3.4 Slot Contribution Record

```java
record SlotContribution(String slot, String content, int order) {}

record FileContribution(String path, String content, boolean executable) {}
```

`order` controls sequencing within a slot.

### 3.5 Variable Scoping

Two-level scope:
- **Global variables** — `groupId`, `artifactId`, `sliceName`, `packageName`, `packagePath`. Available to all templates.
- **Per-template variables** — collected during that feature's interactive step. Multi-instance templates (e.g., http-client with N services) iterate over a list variable.

Variables are expanded in template content via `${variableName}`.

---

## 4. Template Catalog

### 4.1 Base (always active)

Generates: pom.xml skeleton, package directories, jbct.toml, .gitignore.

### 4.2 Slice (auto if any `--with-*`)

Depends on: base.

Generates: `@Slice` interface with factory method, `IMPLEMENTME` stub body.

### 4.3 Aether (auto for slice)

Depends on: slice.

Generates: run-forge.sh, forge.toml, resources.toml stub.

### 4.4 Web (`--with-web`)

Depends on: slice, aether.

Generates: routes.toml with simple GET/POST example derived from slice name, k6/ load test scripts, route method declarations on slice interface.

### 4.5 Persistence (`--with-persistence`)

Depends on: slice, aether.

Sub-options: `@Sql` (generic) or `@PgSql` (PostgreSQL).

Generates: qualifier annotation, persistence interface, schema/V001__create_tables.sql, start-postgres.sh, `[database]` section in resources.toml with commented-out pool config examples.

### 4.6 HTTP Client (`--with-http-client`)

Depends on: slice, aether.

Variables: service names (comma-separated list, iterates per service).

Generates per service: client interface with example `@Get`/`@Post`, qualifier annotation, `[http.<name>]` section in resources.toml.

### 4.7 Pub/Sub (`--with-pubsub`)

Depends on: slice, aether.

Generates: publisher qualifier annotation, `@Subscribe` method on slice, event record, `[<name>]` section in resources.toml (bare kebab-case topic name; namespace auto-derived from the blueprint's Maven coordinates).

### 4.8 Streaming (`--with-streaming`)

Depends on: slice, aether.

Generates: `@EventStreamPublisher`/`@EventStreamReader` annotations, event record, `[streams.<name>]` section in resources.toml.

### 4.9 Scheduled (`--with-scheduled`)

Depends on: slice, aether.

Generates: `@Scheduled` method on slice with interval config example.

### 4.10 AI Tools (auto, `--no-ai` to skip)

Depends on: base.

Generates: .claude/CLAUDE.md with project-appropriate instructions. Includes aether-coder and jbct-reviewer skills when slice template is active. Plain JBCT skill for non-slice projects.

### 4.11 Test (auto for slice)

Depends on: slice.

Generates: test class with mock dependencies and basic assertion. Each feature template contributes mock stubs to the test via `test-mock-stubs` slot.

---

## 5. Engine

### 5.1 Rendering Pipeline

1. Parse CLI flags or run interactive wizard
2. Resolve template dependencies transitively
3. Collect per-template variables (interactive or from flags)
4. Validate minimum versions against project/flag versions
5. Gather all slot contributions from active templates
6. For each target file:
   - `init` mode: render from template + filled slots
   - `add` mode: read existing file, merge contributions
7. Write files, set permissions on executables
8. Print summary

### 5.2 pom.xml Handling

DOM-based XML manipulation:
- Parse existing pom.xml (or render base template for `init`)
- `pom-dependencies` slot: find `<dependencies>` element, append each contribution as child element. Skip if groupId:artifactId already present.
- `pom-plugins` slot: find `<build><plugins>`, same logic.
- `pom-properties` slot: find `<properties>`, same logic.
- Write back with proper formatting.

### 5.3 Java File Handling

For `init`: render complete file from slots (imports + interface body + factory).

For `add`: parse existing Java file to find insertion points:
- Imports: append after last import
- Factory parameters: find factory method signature, append parameter
- Methods: append before closing brace of interface

### 5.4 TOML File Handling

For both modes: append sections. For `add`, check if section already exists and warn/skip.

### 5.5 Dry-Run Mode

`--dry-run` flag: engine collects all contributions, prints what would be generated/modified, writes nothing. Useful for verification and debugging.

---

## 6. IMPLEMENTME Convention

Scaffolded code uses `IMPLEMENTME` markers at placeholder sites:

```java
static OrderProcessor orderProcessor(@Sql SqlConnector db,
                                      @Payments PaymentApi payments) {
    // IMPLEMENTME: Process incoming order using db and payments
    return request -> Promise.success(new OrderResult("OK"));
}
```

AI tools (aether-coder, Claude Code) are configured to detect `IMPLEMENTME` and offer to generate real implementation or ask the user for requirements.

The AI tools CLAUDE.md includes:
```
When you see IMPLEMENTME comments, offer to implement the placeholder
using the available resources visible in the factory parameters.
```

---

## 7. Test Scaffolding

Each feature template contributes mock stubs to the test file:

```java
class OrderProcessorTest {
    @Test
    void processOrder_returnsSuccess() {
        // Mock persistence — contributed by persistence template
        var db = new SqlConnector() {
            // IMPLEMENTME: mock SQL operations
        };

        // Mock HTTP client — contributed by http-client template
        var payments = new PaymentApi() {
            @Override public Promise<PaymentResult> charge(ChargeRequest r) {
                return Promise.success(new PaymentResult("txn-1", "OK"));
            }
        };

        var slice = OrderProcessor.orderProcessor(db, payments);

        slice.processOrder(new OrderRequest(/* IMPLEMENTME */))
             .onSuccess(result -> assertThat(result.status()).isEqualTo("OK"))
             .onFailure(cause -> fail(cause.message()))
             .await();
    }
}
```

No mocking framework. Plain interface implementations. Demonstrates the testing pattern from day one.

---

## 8. Config Examples in resources.toml

Templates generate minimal active config plus commented-out examples:

```toml
[database]
type = "POSTGRESQL"
host = "localhost"
port = 5432
database = "forge"
username = "forge"
password = "forge"
async_url = "postgresql://localhost:5432/forge"

# Pool tuning (uncomment and adjust for production):
# [database.pool_config]
# min_connections = 4
# max_connections = 20
# idle_timeout = "10m"
# connection_timeout = "5s"
```

---

## 9. Decisions Locked

| Decision | Value | Rationale |
|----------|-------|-----------|
| Template location | Classpath resources in jbct-init JAR | Simple, versioned with release |
| Composition model | Fragment merging via named slots | Scales without combinatorial explosion |
| pom.xml handling | DOM-based XML manipulation | Robust with human-edited files |
| Interactive mode | Step-by-step with back navigation | Works in any terminal, no library deps |
| `--with-*` implies slice | Any feature flag auto-enables slice + aether | Features don't make sense without Aether |
| `init` vs `add` | Same templates, different engine mode | No duplication, consistent output |
| Template dependencies | Declared in descriptor, resolved transitively | Explicit, extensible |
| Variable scope | Global + per-template, two levels | Clean separation |
| Placeholder convention | `IMPLEMENTME` keyword | AI-detectable, distinct from TODO |
| Test generation | Auto for slice projects | Demonstrates testing patterns |
| Dry-run | `--dry-run` flag | Verification before writing |
| Template versioning | `min_version` in descriptor, checked against project | Fail early on version mismatch |
| Persistence sub-options | `@Sql` vs `@PgSql` choice | Only feature with real variation today |
| Multi-module | Defer | Significantly more complex, separate design |
| User-extensible templates | Defer — file ticket for post-GA | Start with built-in only |

---

## 10. Implementation Layers

### Layer A: Template Engine

1. Template descriptor parser (reads `template.toml`)
2. Variable resolver (global + per-template scope)
3. Slot contribution collector
4. File renderer (expands `${variables}` in template content)
5. pom.xml DOM merger
6. File writer with executable permission support
7. Dry-run mode

### Layer B: Interactive Wizard

8. Step-by-step flow with back navigation
9. Feature selection (numbered list)
10. Per-feature sub-options (persistence connector type)
11. Multi-instance variable collection (HTTP client service names)
12. Review and confirmation step

### Layer C: Templates

13. Base template (pom.xml, dirs, jbct.toml, .gitignore)
14. Slice template (interface, factory)
15. Aether template (run-forge.sh, forge.toml, resources.toml)
16. Web template (routes.toml, k6/)
17. Persistence template (qualifier, schema, start-postgres.sh)
18. HTTP client template (client interface, qualifier)
19. Pub/Sub template (publisher, subscriber, event)
20. Streaming template (publisher, reader, event)
21. Scheduled template (method, config)
22. AI tools template (.claude/)
23. Test template (test class, mock stubs)

### Layer D: CLI Integration

24. Update `InitCommand` to use template engine
25. Update `AddSliceCommand`, `AddPersistenceCommand`, `AddEventCommand` to use template engine
26. Add new `add` subcommands for new features (http-client, streaming, etc.)
27. Deprecate and remove hardcoded initializers

---

## 11. Future Enhancements

- **User-extensible templates** — `~/.jbct/templates/` directory with custom templates. Discovery, validation, override mechanism. Post-GA ticket.
- **Multi-module scaffolding** — parent pom, shared types module, inter-module wiring. Separate design.
- **Web-based scaffolding** — JHipster-style browser UI. Port the template engine to serve via HTTP.
- **Template marketplace** — community-contributed templates (e.g., "Stripe integration", "Keycloak auth").
