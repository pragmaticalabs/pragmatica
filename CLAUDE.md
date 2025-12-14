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
- **javaparser** (3.27.2-SNAPSHOT) - Java AST parsing (shaded)
- **picocli** - CLI framework

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
- `JbctFormatter` - Entry point for formatting
- `JbctLinter` - Entry point for linting with 23 lint rules
- `ConfigLoader` - TOML config loading with priority chain
- `GitHubReleaseChecker` - Check GitHub Releases for updates
- `JarInstaller` - Download and install JAR updates
- `AiToolsInstaller` - Install AI tools to ~/.claude/
- `AiToolsUpdater` - Update AI tools from GitHub

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

1. **Orphan comments** - Comments not attached to nodes may be dropped
2. **Binary expression wrapping** - Long boolean/arithmetic expressions not auto-wrapped
3. **Array initializer wrapping** - Long array initializers not auto-wrapped
