# JBCT CLI Project

## Project Overview
CLI tool and Maven plugin for JBCT (Java Backend Coding Technology) code formatting and linting.

## Project Structure

```
jbct-cli/
├── jbct-core/          # Core formatting, linting, config, upgrade, init, update logic
├── jbct-cli/           # Picocli CLI commands
├── jbct-maven-plugin/  # Maven plugin wrapper
└── pom.xml             # Parent POM
```

## CLI Commands

```bash
jbct format <path>      # Format Java files
jbct lint <path>        # Analyze for JBCT compliance
jbct check <path>       # Format check + lint (for CI)
jbct upgrade            # Update CLI from GitHub Releases
jbct init [dir]         # Create new JBCT project + install AI tools
jbct update             # Update AI tools from coding-technology repo
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

- **pragmatica-lite:core** (0.8.4) - Result, Option, Promise types
- **pragmatica-lite:http-client** (0.8.4) - HTTP operations for upgrade/update
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

## Implementation Architecture

### Core Components
- `JbctFormatter` - Entry point for formatting (delegates to CstFormatter)
- `CstFormatter` - CST-based formatter implementation
- `CstPrinter` - Prints CST back to source with formatting rules
- `Java25Parser` - Generated PEG parser producing CST with trivia
- `JbctLinter` - Entry point for linting with 23 CST-based lint rules
- `ConfigLoader` - TOML config loading with priority chain
- `GitHubReleaseChecker` - Check GitHub Releases for updates
- `JarInstaller` - Download and install JAR updates
- `AiToolsInstaller` - Install AI tools to ~/.claude/
- `AiToolsUpdater` - Update AI tools from GitHub

### Formatter Components (jbct-core/src/main/java/org/pragmatica/jbct/format/cst/)
- `CstFormatter` - Parses source to CST, applies formatting via CstPrinter
- `CstPrinter` - Traverses CST and outputs formatted source with:
  - ✅ Measurement infrastructure (`measureWidth`, `fitsOnLine`)
  - ✅ Chain alignment (method chains with 2+ calls break, `.` aligns to first `.`)
  - ✅ Import organization (pragmatica → java/javax → other → static)
  - ✅ Member spacing (blank lines between methods, not between consecutive fields)
  - ✅ Field declarations (correct type-name spacing)
  - ✅ Control flow keyword spacing (`if (`, `while (`, etc.)
  - ✅ Binary operator spacing (including < > distinction for generics vs comparison)
  - ✅ Ternary operators (multiline formatting with `?` and `:` alignment)
  - ✅ Record bodies (empty and non-empty)
  - ✅ Enum bodies with constant formatting
  - ✅ Blank line preservation from source trivia
  - ⏳ Argument alignment (multi-line args)
  - ⏳ Lambda formatting in complex contexts

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

## Known Limitations

### Parser (java25.peg)
Grammar synced from `../java-peglib/Java25GrammarExample.java`. Supports full Java 25:
- Modules, sealed classes, records, enums
- Pattern matching (instanceof, switch patterns, guards)
- Text blocks, type-use annotations (JSR 308)
- Assignment expressions, lambdas, method references

### Formatter (CstPrinter) - In Development
- ✅ Phase 1: Measurement infrastructure
- ✅ Phase 2: Chain alignment
- ✅ Phase 3: Member spacing & imports
- ✅ Phase 4: Ternary operators & binary operator spacing
- ✅ Phase 5: Record/enum body formatting
- ✅ Phase 6: Control flow keyword spacing
- ⏳ Phase 7: Argument alignment, complex lambdas (2/12 golden tests pass)

### Migration Status (JavaParser → CST)
- ⏳ JbctFormatter still uses JavaParser (CstFormatter exists but not integrated)
- ⏳ JbctLinter still uses JavaParser (23 CST lint rules exist but not integrated)
- ⏳ JavaParser dependency removal pending after formatter cutover
- Grammar updated: Member order changed to prioritize TypeKind before MethodDecl for nested records
