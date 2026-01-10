# JBCT CLI & Maven Plugin

Code formatting and linting tools for [Java Backend Coding Technology (JBCT)](https://github.com/siy/coding-technology).

## Installation

### Quick Install (Linux/macOS)

```bash
curl -fsSL https://raw.githubusercontent.com/siy/jbct-cli/main/install.sh | sh
```

The installer:
- Verifies Java is installed (JDK 25+ required)
- Downloads latest release from GitHub
- Installs to `~/.jbct/lib/jbct.jar`
- Creates wrapper script at `~/.jbct/bin/jbct`
- Adds `~/.jbct/bin` to PATH in your shell RC file

Custom install location: `JBCT_HOME=/custom/path sh install.sh`

### Manual Installation

Download `jbct.jar` from [releases](https://github.com/siy/jbct-cli/releases) and run:

```bash
java -jar jbct.jar --help
```

### Build from Source

```bash
git clone https://github.com/siy/jbct-cli.git
cd jbct-cli
mvn package -DskipTests
# JAR: jbct-cli/target/jbct.jar
```

### Maven Plugin

Add to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>jbct-maven-plugin</artifactId>
            <version>0.4.7</version>
        </plugin>
    </plugins>
</build>
```

## CLI Usage

### Format

Format Java source files in-place:

```bash
jbct format src/main/java
```

Check formatting without modifying files (for CI):

```bash
jbct format --check src/main/java
```

Options:
- `--check` / `-c` - Report files needing formatting, exit 1 if any found
- `--dry-run` / `-n` - Show what would be formatted without writing
- `--verbose` / `-v` - Show detailed output
- `--config <path>` - Path to configuration file

### Lint

Check code for JBCT compliance:

```bash
jbct lint src/main/java
```

Options:
- `--format` / `-f` `<text|json|sarif>` - Output format (default: text)
- `--fail-on-warning` / `-w` - Treat warnings as errors
- `--verbose` / `-v` - Show detailed output
- `--config <path>` - Path to configuration file

### Check

Combined format check and lint (recommended for CI):

```bash
jbct check src/main/java
```

Options:
- `--fail-on-warning` / `-w` - Treat warnings as errors
- `--verbose` / `-v` - Show detailed output
- `--config <path>` - Path to configuration file

Exit codes:
- `0` - All checks passed
- `1` - Format or lint issues found
- `2` - Internal error (parse failure, etc.)

### Upgrade

Self-update to the latest version:

```bash
jbct upgrade                    # Check and install latest
jbct upgrade --check            # Check only, don't install
jbct upgrade --force            # Force reinstall
jbct upgrade --version 0.4.5    # Install specific version
jbct upgrade --install          # First-time installation to ~/.jbct/
```

### Init

Create a new JBCT-compliant project:

```bash
jbct init my-project                           # Create in new directory
jbct init .                                    # Initialize current directory
jbct init --group-id com.example my-project    # Custom group ID
jbct init --artifact-id myapp my-project       # Custom artifact ID
jbct init --slice my-slice                     # Create Aether slice project
jbct init --ai-only                            # Only install AI tools
jbct init --no-ai my-project                   # Skip AI tools installation
jbct init --force my-project                   # Overwrite existing files
```

Creates Maven project with:
- JBCT-compliant `pom.xml`
- `jbct.toml` configuration
- AI tools installed to `~/.claude/`

For slice projects (`--slice`), also creates:
- `@Slice` annotated interface with factory method
- Implementation class
- Sample request/response records
- Unit test

### Update

Update AI tools from coding-technology repository:

```bash
jbct update           # Update if new version available
jbct update --force   # Force update
jbct update --check   # Check only
```

### Verify Slice

Validate Aether slice project configuration:

```bash
jbct verify-slice              # Validate current directory
jbct verify-slice my-slice     # Validate specific directory
jbct verify-slice --strict     # Fail on warnings
```

Checks for:
- Missing `pom.xml` or `slice.class` property
- Missing `slice-api.properties` (annotation processor not run)
- Missing manifest entries

## Configuration

JBCT uses `jbct.toml` for configuration:

```toml
[format]
maxLineLength = 120
indentSize = 4
alignChainedCalls = true

[lint]
failOnWarning = false
businessPackages = ["**.usecase.**", "**.domain.**"]
slicePackages = ["**.usecase.**"]  # Required for JBCT-SLICE-01

[lint.rules]
JBCT-RET-01 = "error"
JBCT-STY-01 = "warning"
JBCT-LOG-01 = "off"
```

Priority chain:
1. CLI arguments (highest)
2. `./jbct.toml` (project)
3. `~/.jbct/config.toml` (user)
4. Built-in defaults (lowest)

## Maven Plugin Usage

### Goals

| Goal | Description | Default Phase |
|------|-------------|---------------|
| `jbct:format` | Format source files in-place | process-sources |
| `jbct:format-check` | Check formatting (fail if issues) | verify |
| `jbct:lint` | Run lint rules | verify |
| `jbct:check` | Combined format-check + lint | verify |
| `jbct:collect-slice-deps` | Collect slice API dependencies | generate-sources |
| `jbct:verify-slice` | Validate slice configuration | verify |

### Examples

Format code:

```bash
mvn jbct:format
```

Check formatting in CI:

```bash
mvn jbct:format-check
```

Run linter:

```bash
mvn jbct:lint
```

Full check (format + lint):

```bash
mvn jbct:check
```

### Binding to Build Lifecycle

Add executions to run automatically:

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.4.7</version>
    <executions>
        <execution>
            <id>check</id>
            <goals>
                <goal>check</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Configuration

The Maven plugin reads configuration from `jbct.toml` in your project root.
All formatting and linting settings are shared between CLI and Maven plugin.

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.4.7</version>
    <configuration>
        <!-- Skip JBCT processing -->
        <skip>false</skip>

        <!-- Include test sources -->
        <includeTests>false</includeTests>
    </configuration>
</plugin>
```

Create `jbct.toml` for formatting/linting configuration:

```toml
[format]
maxLineLength = 120
indentSize = 4
alignChainedCalls = true

[lint]
failOnWarning = false
businessPackages = ["**.usecase.**", "**.domain.**"]
slicePackages = ["**.usecase.**"]  # Required for JBCT-SLICE-01

[lint.rules]
JBCT-RET-01 = "error"
JBCT-STY-01 = "warning"
JBCT-LOG-01 = "off"
```

## Lint Rules (37 total)

### Return Kinds

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-RET-01` | ERROR | Business methods must use T, Option, Result, or Promise |
| `JBCT-RET-02` | ERROR | No nested wrappers (Promise<Result<T>>, Option<Option<T>>) |
| `JBCT-RET-03` | ERROR | Never return null - use Option<T> |
| `JBCT-RET-04` | ERROR | Use Unit instead of Void |
| `JBCT-RET-05` | WARNING | Avoid always-succeeding Result (return T directly) |

### Value Objects

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-VO-01` | WARNING | Value objects should have factory returning Result<T> |
| `JBCT-VO-02` | ERROR | Don't bypass factory with direct constructor calls |

### Exceptions

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-EX-01` | ERROR | No business exceptions (throw, throws, exception classes) |
| `JBCT-EX-02` | ERROR | Don't use orElseThrow() - use Result/Option |

### Naming

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-NAM-01` | WARNING | Factory methods: TypeName.typeName() |
| `JBCT-NAM-02` | WARNING | Use Valid prefix, not Validated |

### Lambda/Composition

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-LAM-01` | WARNING | No complex logic in lambdas (if, switch, try-catch) |
| `JBCT-LAM-02` | WARNING | No braces in lambdas - extract to methods |
| `JBCT-LAM-03` | WARNING | No ternary in lambdas - use filter() or extract |
| `JBCT-UC-01` | WARNING | Use case factories should return lambdas, not nested records |

### Patterns

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-PAT-01` | WARNING | Use functional iteration instead of raw loops |
| `JBCT-PAT-02` | WARNING | No Fork-Join inside Sequencer (Result.all inside flatMap) |
| `JBCT-SEQ-01` | WARNING | Chain length limit (2-5 steps) |

### Style

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-STY-01` | WARNING | Prefer fluent failure: `cause.result()` not `Result.failure(cause)` |
| `JBCT-STY-02` | WARNING | Prefer constructor references: `X::new` not `v -> new X(v)` |
| `JBCT-STY-03` | WARNING | No fully qualified class names in code |
| `JBCT-STY-04` | WARNING | Utility class pattern: use sealed interface with `unused` record |
| `JBCT-STY-05` | WARNING | Prefer method references over equivalent lambdas |
| `JBCT-STY-06` | WARNING | Import ordering: java → javax → pragmatica → third-party → project |

### Logging

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-LOG-01` | WARNING | No conditional logging - let log level handle filtering |
| `JBCT-LOG-02` | WARNING | No logger as method parameter - component owns its logger |

### Architecture

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-MIX-01` | ERROR | No I/O operations in domain packages |

### Static Imports

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-STATIC-01` | WARNING | Prefer static imports for Pragmatica factories |

### Utilities

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-UTIL-01` | WARNING | Use Pragmatica parsing utilities (Number.parseInt, etc.) |
| `JBCT-UTIL-02` | WARNING | Use Verify.Is predicates for validation |

### Nesting

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-NEST-01` | WARNING | No nested monadic operations in lambdas |

### Zones

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-ZONE-01` | WARNING | Step interfaces should use Zone 2 verbs (validate, process, handle) |
| `JBCT-ZONE-02` | WARNING | Leaf functions should use Zone 3 verbs (get, fetch, parse) |
| `JBCT-ZONE-03` | WARNING | No zone mixing in sequencer chains |

### Naming Conventions

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-ACR-01` | WARNING | Acronyms should use PascalCase (HttpClient, not HTTPClient) |

### Sealed Types

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-SEAL-01` | WARNING | Error interfaces extending Cause should be sealed |

### Slice Architecture

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-SLICE-01` | ERROR | External slice dependencies must use API interface (requires `slicePackages` config) |

**Note:** JBCT-SLICE-01 requires `slicePackages` configuration:
```toml
[lint]
slicePackages = ["**.usecase.**"]  # Configure your slice package patterns
```

## Formatter Style

The JBCT formatter enforces:

- **Method chain alignment** - Continuation aligned to receiver
- **Argument alignment** - Multi-line arguments aligned to opening paren
- **Parameter alignment** - Multi-line parameters aligned to opening paren
- **Import grouping** - org.pragmatica, java/javax, static (with blank lines between)
- **Blank lines** - One between methods, none after opening brace
- **Indentation** - 4 spaces, no tabs
- **Line length** - 120 characters max

Example:

```java
return ValidRequest.validRequest(request)
                   .async()
                   .flatMap(checkEmail::apply)
                   .flatMap(saveUser::apply);

return Result.all(Email.email(raw.email()),
                  Password.password(raw.password()),
                  ReferralCode.referralCode(raw.referral()))
             .map(ValidRequest::new);
```

## CI Integration

### GitHub Actions

```yaml
- uses: actions/setup-java@v4
  with:
    java-version: '25'
    distribution: 'temurin'
    cache: maven

- run: mvn verify -B
```

With plugin bound to verify phase, `mvn verify` runs all checks.

### Standalone CLI

```yaml
- run: java -jar jbct.jar check src/main/java
```

## Requirements

- Java 25+
- Maven 3.9+ (for plugin)

## Support

If you find this useful, consider [sponsoring](https://github.com/sponsors/siy).

## License

Apache 2.0
