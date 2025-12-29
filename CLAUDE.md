# JBCT CLI Project

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

## Key Dependencies

- **pragmatica-lite:core** (0.9.0) - Result, Option, Promise types
- **pragmatica-lite:http-client** (0.9.0) - HTTP operations for upgrade/update
- **pragmatica-lite:toml** (0.9.0) - TOML configuration parsing
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
- `rules/` - 36 CST-based lint rule implementations

### HTTP Client Pattern
Uses pragmatica-lite http-client:
```java
http.sendString(request)
    .await()
    .flatMap(HttpResult::toResult)
    .flatMap(body -> ...);
```

## Build Commands

```bash
# Compile
mvn compile

# Run tests
mvn test

# Build distribution (creates tar.gz/zip)
mvn package -DskipTests

# Full verify
mvn verify
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
│   ├── ApiInterfaceGenerator.java    # Generates API interface in .api package
│   ├── ProxyClassGenerator.java      # Generates proxy for dependencies
│   ├── FactoryClassGenerator.java    # Generates factory with proxy wiring
│   ├── ManifestGenerator.java        # Generates META-INF/slice-api.properties
│   └── DependencyVersionResolver.java # Resolves versions from slice-deps.properties
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
├── pom.xml                      # With slice-parent and jbct plugin
├── jbct.toml                    # JBCT configuration
└── src/
    ├── main/java/org/example/myslice/
    │   ├── MySlice.java         # @Slice interface with factory method
    │   ├── MySliceImpl.java     # Implementation
    │   ├── SampleRequest.java   # Request record
    │   └── SampleResponse.java  # Response record
    └── test/java/org/example/myslice/
        └── MySliceTest.java     # Unit test
```

### Generated Artifacts

From `@Slice` annotation processor:

1. **API Interface** (`api/MySlice.java`) - Public interface for consumers
2. **Proxy Classes** (`api/*Proxy.java`) - Delegates to SliceInvokerFacade
3. **Factory Class** (`MySliceFactory.java`) - Creates instance with proxied dependencies
4. **Manifest** (`META-INF/slice-api.properties`) - Maps artifact to interface
