# JBCT CLI & Maven Plugin

Code formatting and linting tools for [Java Backend Coding Technology (JBCT)](https://github.com/siy/coding-technology).

## Installation

### CLI

Download the latest `jbct.jar` from releases or build from source:

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/siy/jbct-cli.git
cd jbct-cli

# Build (uses build script for JavaParser + jbct)
./scripts/build.sh

# JAR located at jbct-cli/target/jbct.jar
```

Create an alias for convenience:

```bash
alias jbct='java -jar /path/to/jbct.jar'
```

### Maven Plugin

Add to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>jbct-maven-plugin</artifactId>
            <version>0.2.0</version>
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
    <version>0.2.0</version>
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
    <version>0.2.0</version>
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

## Lint Rules

### Return Kinds

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-RET-01` | ERROR | Business methods must use T, Option, Result, or Promise |
| `JBCT-RET-02` | ERROR | No nested wrappers (Promise<Result<T>>, Option<Option<T>>) |
| `JBCT-RET-03` | ERROR | Never return null - use Option<T> |
| `JBCT-RET-04` | ERROR | Use Unit instead of Void |

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

### Composition

| Rule | Severity | Description |
|------|----------|-------------|
| `JBCT-LAM-01` | WARNING | No complex logic in lambdas (if, switch, try-catch) |
| `JBCT-UC-01` | WARNING | Use case factories should return lambdas, not nested records |
| `JBCT-PAT-01` | WARNING | Use functional iteration instead of raw loops |

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
