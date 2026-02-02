# JBCT CLI Project

## Governing Documents

RFCs location: `../coding-technology/proposals/rfc/`
Remote: https://github.com/siy/coding-technology/tree/master/proposals/rfc

Relevant RFCs:
- RFC-0000: Ecosystem Foundation
- RFC-0001: Core Slice Contract
- RFC-0002: Dependency Protocol
- RFC-0003: HTTP Layer

## AI Agent Policy

**MANDATORY**: Use ONLY the `jbct-coder` agent for ALL coding and fixing tasks in this project. This ensures code follows JBCT patterns and conventions.

To invoke: Use the `/jbct` skill or spawn the `jbct-coder` agent via the Task tool.

## Versioning Policy

Until release 1.0, backward compatibility is not guaranteed. Breaking changes are acceptable for JBCT compliance improvements.

## JBCT Pattern Exceptions

The following deviations from strict JBCT patterns are accepted in this codebase:

### Resource Loading Null Checks

Classpath resource loading uses direct null checks instead of Option pattern due to try-with-resources complexity:

```java
try (var in = Class.class.getResourceAsStream(path)) {
    if (in == null) {
        return Causes.cause("Resource not found").result();
    }
    // use stream
}
```

**Affected files:**
- `JarInstaller.java:226` - Copying wrapper scripts
- `ProjectInitializer.java:189` - Loading templates
- `SliceProjectInitializer.java:226` - Loading templates
- `GitHubVersionResolver.java:55` - Loading version properties
- `Version.java:18` - Loading version properties

**Rationale:** Wrapping InputStream in Option requires splitting into two methods to handle try-with-resources correctly. The added complexity outweighs the benefit for adapter-layer code.

## Project Overview
CLI tool and Maven plugin for JBCT (Java Backend Coding Technology) code formatting and linting.

## Project Structure

```
jbct-cli/
├── jbct-core/          # Core formatting, linting, config, upgrade, init, update logic
├── jbct-cli/           # Picocli CLI commands
├── jbct-maven-plugin/  # Maven plugin wrapper
├── slice-processor/    # Annotation processor for Aether slice development
└── pom.xml             # Parent POM
```

## CLI Commands

```bash
jbct format <path>      # Format Java files
jbct lint <path>        # Analyze for JBCT compliance
jbct check <path>       # Format check + lint (for CI)
jbct score <path>       # Calculate JBCT compliance score (0-100)
jbct upgrade            # Update CLI from GitHub Releases
jbct init [dir]         # Create new JBCT project + install AI tools
jbct init --slice [dir] # Create new Aether slice project
jbct update             # Update AI tools from coding-technology repo
jbct verify-slice [dir] # Validate slice project configuration
```

## Configuration

JBCT uses `jbct.toml` configuration with priority chain:
1. CLI args (highest)
2. `./jbct.toml` (project root)
3. `~/.jbct/config.toml` (user default)
4. Built-in defaults (lowest)

Example `jbct.toml`:
```toml
[format]
maxLineLength = 120
indentSize = 4
alignChainedCalls = true

[lint]
failOnWarning = false
businessPackages = ["**.usecase.**", "**.domain.**"]

[lint.rules]
JBCT-RET-01 = "error"
JBCT-VO-01 = "warning"
```

## JBCT Compliance Scoring

The `jbct score` command calculates a 0-100 compliance score based on lint violations across 6 weighted categories.

### Usage

```bash
# CLI
jbct score <path>                    # Terminal output with progress bars
jbct score --format json <path>      # JSON output
jbct score --format badge <path>     # SVG badge
jbct score --baseline 75 <path>      # Fail if score < 75

# Maven
mvn jbct:score
mvn jbct:score -Djbct.score.baseline=75
```

### Scoring Formula

**Category score:**
```
weighted_violations = Σ(count[severity] × multiplier[severity])
  ERROR:   × 2.5
  WARNING: × 1.0
  INFO:    × 0.3

category_score = 100 × (1 - weighted_violations / checkpoints)
```

**Overall score:**
```
overall_score = Σ(category_score[i] × weight[i])
```

### Scoring Categories

| Category           | Weight | Rules Included |
|--------------------|--------|----------------|
| Return Types       | 25%    | JBCT-RET-01, JBCT-RET-02, JBCT-RET-03, JBCT-NEST-02, JBCT-RES-01 |
| Null Safety        | 20%    | JBCT-NULL-01, JBCT-NULL-02 |
| Exception Hygiene  | 20%    | JBCT-EXC-01, JBCT-EXC-02, JBCT-NAME-02 |
| Pattern Purity     | 15%    | JBCT-PAT-01, JBCT-NEST-01, JBCT-CHAIN-01, JBCT-IMPORT-01, JBCT-FQN-01, JBCT-DOMAIN-01, JBCT-LOOP-01, JBCT-LOG-01, JBCT-LOG-02 |
| Factory Methods    | 10%    | JBCT-VO-01, JBCT-VO-02, JBCT-VO-03, JBCT-VO-04, JBCT-ERR-01, JBCT-NAME-01, JBCT-PARSE-01 |
| Lambda Compliance  | 10%    | JBCT-LAM-01, JBCT-LAM-02, JBCT-LAM-03, JBCT-LAM-04, JBCT-STATIC-01 |

### Output Formats

**Terminal** (default):
```
╔═══════════════════════════════════════════════════╗
║     JBCT COMPLIANCE SCORE: 85/100            ║
╠═══════════════════════════════════════════════════╣
║  RETURN TYPES       █████████████████░░░  85%    ║
║  NULL SAFETY        ████████████████░░░░  80%    ║
║  EXCEPTION HYGIENE  ██████████████████░░  90%    ║
║  PATTERN PURITY     ███████████████░░░░░  75%    ║
║  FACTORY METHODS    ████████████████████ 100%    ║
║  LAMBDA COMPLIANCE  ██████████████████░░  90%    ║
╚═══════════════════════════════════════════════════╝
```

**JSON**:
```json
{
  "score": 85,
  "breakdown": {
    "return_types": 85,
    "null_safety": 80,
    "exception_hygiene": 90,
    "pattern_purity": 75,
    "factory_methods": 100,
    "lambda_compliance": 90
  },
  "filesAnalyzed": 42
}
```

**Badge** (SVG):
- Color: green (≥75), yellow (≥60), orange (≥50), red (<50)
- Format: `JBCT | 85/100`

### CI Integration

Use `--baseline` to enforce minimum score in CI:

```bash
jbct score --baseline 75 src/main/java || exit 1
```

Or with Maven:

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.6.1</version>
    <executions>
        <execution>
            <goals>
                <goal>score</goal>
            </goals>
            <configuration>
                <baseline>75</baseline>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Key Dependencies

- **pragmatica-lite:core** (0.11.2) - Result, Option, Promise types
- **pragmatica-lite:http-client** (0.11.2) - HTTP operations for upgrade/update
- **pragmatica-lite:toml** (0.11.2) - TOML configuration parsing
- **java-peglib** - PEG parser generator for CST-based parsing
- **picocli** - CLI framework

## Parser Architecture

JBCT uses a custom CST (Concrete Syntax Tree) parser generated from a PEG grammar:

- **Grammar**: `jbct-core/src/main/resources/grammars/java25.peg`
- **Generated parser**: `jbct-core/src/main/java/org/pragmatica/jbct/parser/Java25Parser.java`
- **Generator**: `./scripts/generate-parser.sh` (uses java-peglib from `../java-peglib`)

### Regenerating the Parser

```bash
./scripts/generate-parser.sh
```

This reads `java25.peg` and generates `Java25Parser.java` with full trivia (whitespace/comments) preservation.

### CST vs AST

The CST preserves all source information including whitespace and comments, enabling:
- Idempotent formatting (format already-formatted code without changes)
- Comment preservation
- Precise error locations

## JBCT Formatting Rules

1. **Chain alignment**: `.` aligns to the receiver end
   ```java
   return input.map(String::trim)
               .map(String::toUpperCase);
   ```

2. **Fork-join pattern**: `.all()` in chains wraps arguments when complex
   ```java
   return Result.all(Email.email(raw.email()),
                     Password.password(raw.password()))
                .flatMap(ValidRequest::validRequest);
   ```

3. **Ternary**: `?` and `:` align to opening `(` of expression
   ```java
   return condition
          ? "yes"
          : "no";
   ```

4. **Lambda in broken args**: Body aligns to parameter + 4
   ```java
   return input.fold(cause -> {
                         logError(cause);
                         return defaultValue;
                     },
                     value -> value.toUpperCase());
   ```

5. **Import grouping**: Blank lines between groups (org.pragmatica, java/javax, static)

6. **String concatenation**: Long strings wrap with `+` aligned to start of expression
   ```java
   private static final String LONG = "First part"
                                      + " second part"
                                      + " third part";
   ```

## Implementation Architecture

### Core Components
- `JbctFormatter` - Entry point for formatting (delegates to CstFormatter)
- `CstFormatter` - CST-based formatter implementation
- `CstPrinter` - Prints CST back to source with formatting rules
- `Java25Parser` - Generated PEG parser producing CST with trivia
- `JbctLinter` - Entry point for linting (delegates to CstLinter)
- `CstLinter` - CST-based linter with 37 lint rules
- `ConfigLoader` - TOML config loading with priority chain
- `GitHubReleaseChecker` - Check GitHub Releases for updates
- `JarInstaller` - Download and install JAR updates
- `AiToolsInstaller` - Install AI tools to ~/.claude/
- `AiToolsUpdater` - Update AI tools from GitHub

### Parser Types
- `RuleId` - Sealed interface with record types for each grammar rule (e.g., `RuleId.ClassMember`, `RuleId.MethodDecl`)
- `CstNode` - Sealed interface: `Terminal`, `Token`, `NonTerminal` - each has `rule()` returning `RuleId`
- `CstNodes` - Utility methods using `Class<? extends RuleId>` for type-safe rule matching

### Formatter Components (jbct-core/src/main/java/org/pragmatica/jbct/format/cst/)
- `CstFormatter` - Parses source to CST, applies formatting via CstPrinter
- `CstPrinter` - Traverses CST and outputs formatted source (pattern matching on `RuleId` types)
- `SpacingRules` - Encapsulates Java syntax-aware spacing logic
- `AlignmentContext` - Manages chain/lambda alignment with try-with-resources scopes

### Linter Components (jbct-core/src/main/java/org/pragmatica/jbct/lint/cst/)
- `CstLinter` - CST-based linter entry point
- `CstLintRule` - Interface for CST-based lint rules
- `rules/` - 37 CST-based lint rule implementations

### HTTP Client Pattern
Uses pragmatica-lite http-client:
```java
http.sendString(request)
    .await()
    .flatMap(HttpResult::toResult)
    .flatMap(body -> ...);
```

## Build Commands

**Important**: This project uses its own jbct-maven-plugin (dogfooding), creating a dependency cycle. All reactor builds require `-Djbct.skip=true` to avoid the cycle error.

```bash
# Compile
mvn compile -Djbct.skip=true

# Run tests
mvn test -Djbct.skip=true

# Build distribution (creates tar.gz/zip)
mvn package -DskipTests -Djbct.skip=true

# Full verify
mvn verify -Djbct.skip=true

# Format all source files (run on single module)
mvn jbct:format -pl jbct-core

# Check formatting and lint (run on single module)
mvn jbct:check -pl jbct-core
```

### Installing to Local Repository

Due to the dependency cycle, install modules individually:

```bash
mvn install -DskipTests -pl jbct-core -Djbct.skip=true
mvn install -DskipTests -pl jbct-maven-plugin -Djbct.skip=true
mvn install -DskipTests -pl jbct-cli,slice-processor,slice-processor-tests -Djbct.skip=true
```

## Distribution

Built archives in `jbct-cli/target/`:
- `jbct-cli-VERSION-dist.tar.gz`
- `jbct-cli-VERSION-dist.zip`

Contents:
```
jbct-VERSION/
├── bin/jbct        # Unix wrapper
├── bin/jbct.bat    # Windows wrapper
└── lib/jbct.jar    # Fat JAR
```

## Golden Examples Location
`jbct-core/src/test/resources/format-examples/`

## Debugging Parse Errors

When a file fails to parse with an unhelpful error like "Parse error at file.java:1:1":

1. **Copy file to test resources** - `jbct-core/src/test/resources/format-examples/`
2. **Create minimal version** - Strip comments, reduce to skeleton class with one method
3. **Binary search** - Comment out half the class body, test if it parses:
   - If parses: issue is in commented-out half
   - If fails: issue is in remaining half
4. **Repeat** - Keep halving until you isolate the problematic construct
5. **Create unit test** - Add test case to `Java25ParserTest.java` for the specific construct

This technique quickly narrows down issues that would otherwise be hard to locate.

## Known Limitations

### Parser (java25.peg)
Grammar synced from `../java-peglib/Java25GrammarExample.java`. Supports full Java 25:
- Modules, sealed classes, records, enums
- Pattern matching (instanceof, switch patterns, guards)
- Text blocks, type-use annotations (JSR 308)
- Assignment expressions, lambdas, method references

### Spacing Heuristics
- `List<String>CONSTANT` - No space after `>` before uppercase identifier (could be generic type or constant name)

## Postponed Linter Rules

These rules are planned but not yet implemented:

| Rule | Description | Complexity | Notes |
|------|-------------|------------|-------|
| JBCT-DEP-01 | Correct Pragmatica Lite dependency | Easy | Requires build file analysis (pom.xml/gradle), not Java source |
| JBCT-MUT-01 | No mutation of input parameters | Hard | Requires data-flow analysis |
| JBCT-FJ-01 | Fork-Join inputs must be immutable | Hard | Requires data-flow analysis |

## Slice Processor Module

The `slice-processor` module provides annotation processing for Aether slice development.

### Components

```
slice-processor/
├── SliceProcessor.java           # Main annotation processor (@Slice)
├── generator/
│   ├── ProxyClassGenerator.java      # Generates proxy for dependencies
│   ├── FactoryClassGenerator.java    # Generates factory with proxy wiring
│   ├── ManifestGenerator.java        # Generates META-INF/slice manifests
│   └── DependencyVersionResolver.java # Resolves versions from slice-deps.properties
├── routing/
│   ├── RouteDsl.java             # DSL parser for route definitions
│   ├── RouteConfig.java          # Loaded TOML config model
│   ├── RouteConfigLoader.java    # TOML config loader with hierarchy
│   ├── RouteSourceGenerator.java # Generates RouteSource + SliceRouterFactory
│   ├── ErrorTypeDiscovery.java   # Package scanner for Cause types
│   └── ErrorTypeMatcher.java     # Glob-like pattern matcher
└── model/
    ├── SliceModel.java           # Slice metadata from @Slice interface
    ├── MethodModel.java          # Method info (name, returnType, parameterType)
    └── DependencyModel.java      # Dependency info from factory method params
```

### Maven Plugin Goals

| Goal | Phase | Description |
|------|-------|-------------|
| `jbct:collect-slice-deps` | generate-sources | Collect provided dependencies to slice-deps.properties |
| `jbct:verify-slice` | verify | Validate slice configuration |

### Slice Project Structure

Created by `jbct init --slice`:

```
my-slice/
├── pom.xml                      # With slice-parent, jbct plugin, and deploy profiles
├── jbct.toml                    # JBCT configuration
├── deploy-forge.sh              # Deploy to local Aether Forge
├── deploy-test.sh               # Deploy to test environment
├── deploy-prod.sh               # Deploy to production (with confirmation)
└── src/
    ├── main/java/org/example/myslice/
    │   ├── MySlice.java         # @Slice interface with factory method
    │   ├── MySliceImpl.java     # Implementation
    │   ├── SampleRequest.java   # Request record
    │   └── SampleResponse.java  # Response record
    └── test/java/org/example/myslice/
        └── MySliceTest.java     # Unit test
```

### Deploy Scripts

Slice projects include Maven profiles and deploy scripts for Aether deployment:

| Script | Profile | Description |
|--------|---------|-------------|
| `deploy-forge.sh` | `deploy-forge` | Deploy to local Forge (localhost:8080) |
| `deploy-test.sh` | `deploy-test` | Deploy to test environment |
| `deploy-prod.sh` | `deploy-prod` | Deploy to production (prompts for confirmation) |

Configure URLs in `pom.xml` properties:
```xml
<aether.forge.url>http://localhost:8080</aether.forge.url>
<aether.test.url>http://test.example.com:8080</aether.test.url>
<aether.prod.url>http://prod.example.com:8080</aether.prod.url>
```

### Generated Artifacts

From `@Slice` annotation processor:

1. **Factory Class** (`MySliceFactory.java`) - Creates instance with proxied dependencies
2. **Slice Manifest** (`META-INF/slice/MySlice.manifest`) - Metadata for packaging/deployment
3. **Slice API Manifest** (`META-INF/slice-api.properties`) - Maps artifact to slice interface

Note: Proxy records for dependencies are generated as local records inside the factory's `create()` method. All dependencies go through `SliceInvokerFacade` proxy - there is no internal/external distinction.

### HTTP Routing Generation

When `routes.toml` exists in the slice package resources, generates HTTP route handling.

**Dependency requirement**: New projects created with `jbct init --slice` include `http-routing-adapter` automatically. For existing projects adding routing, add:
```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>http-routing-adapter</artifactId>
    <version>${aether.version}</version>
    <scope>provided</scope>
</dependency>
```
Compilation fails if `routes.toml` exists without this dependency.

**Config location:** `src/main/resources/{slicePackage}/routes.toml`

```toml
prefix = "/api/v1/users"

[routes]
getUser = "GET /{id:Long}"
createUser = "POST /"
searchUsers = "GET /?name&limit:Integer"

[errors]
HTTP_404 = ["*NotFound*", "*Missing*"]
HTTP_400 = ["*Invalid*", "*Validation*"]
```

**Generated artifacts:**
1. **Routes Class** (`MySliceRoutes.java`) - Implements `RouteSource` and `SliceRouterFactory<MySlice>`
2. **Service File** (`META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory`)

**Route DSL syntax:** `"METHOD /path/{param:Type}?query1&query2:Type"`
- Path params: `{id:Long}`, `{name:String}` (String is default)
- Query params: `name` or `name:Type` after `?`, separated by `&`
- Supported types: `String`, `Integer`, `Long`, `Boolean`, `LocalDate`, `LocalDateTime`

**Error mapping:** Pattern-based matching of `Cause` types to HTTP status codes
- `*NotFound*` matches `UserNotFound`, `OrderNotFound`, etc.
- Conflicts detected at compile time
