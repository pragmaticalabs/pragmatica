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
git clone --recurse-submodules https://github.com/siy/jbct-cli.git
cd jbct-cli
./scripts/build.sh
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
            <version>0.3.0</version>
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
- `--check` - Report files needing formatting, exit 1 if any found
- `--dry-run` - Show what would be formatted without writing
- `--verbose` - Show detailed output

### Lint

Check code for JBCT compliance:

```bash
jbct lint src/main/java
```

Options:
- `--format <text|json|sarif>` - Output format (default: text)
- `--fail-on-warning` - Treat warnings as errors
- `--verbose` - Show detailed output

### Check

Combined format check and lint (recommended for CI):

```bash
jbct check src/main/java
```

Options:
- `--fail-on-warning` - Treat warnings as errors
- `--verbose` - Show detailed output

Exit codes:
- `0` - All checks passed
- `1` - Format or lint issues found
- `2` - Internal error (parse failure, etc.)

### Upgrade

Self-update to the latest version:

```bash
jbct upgrade           # Check and install latest
jbct upgrade --check   # Check only, don't install
jbct upgrade --force   # Force reinstall
```

### Init

Create a new JBCT-compliant project:

```bash
jbct init my-project                           # Create in new directory
jbct init .                                    # Initialize current directory
jbct init --group-id com.example my-project    # Custom group ID
```

Creates Maven project with:
- JBCT-compliant `pom.xml`
- `jbct.toml` configuration
- AI tools installed to `~/.claude/`

### Update

Update AI tools from coding-technology repository:

```bash
jbct update           # Update if new version available
jbct update --force   # Force update
jbct update --check   # Check only
```

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
    <version>0.3.0</version>
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

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.3.0</version>
    <configuration>
        <!-- Source directories to process (default: src/main/java) -->
        <sourceDirectories>
            <sourceDirectory>src/main/java</sourceDirectory>
        </sourceDirectories>

        <!-- Business package patterns for lint rules -->
        <businessPackages>
            <package>**.usecase.**</package>
            <package>**.domain.**</package>
        </businessPackages>

        <!-- Fail build on warnings -->
        <failOnWarning>false</failOnWarning>
    </configuration>
</plugin>
```

## Lint Rules (23 total)

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
| `JBCT-SEQ-01` | WARNING | Chain length limit (2-5 steps) |

### Style

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-STY-01` | WARNING | Prefer fluent failure: `cause.result()` not `Result.failure(cause)` |
| `JBCT-STY-02` | WARNING | Prefer constructor references: `X::new` not `v -> new X(v)` |
| `JBCT-STY-03` | WARNING | No fully qualified class names in code |

### Logging

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-LOG-01` | WARNING | No conditional logging - let log level handle filtering |
| `JBCT-LOG-02` | WARNING | No logger as method parameter - component owns its logger |

### Architecture

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-MIX-01` | ERROR | No I/O operations in domain packages |

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

## License

Apache 2.0
