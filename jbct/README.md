# JBCT CLI and Maven Plugin

Code formatting, linting, and compliance scoring for Java Backend Coding Technology (JBCT).

## Overview

Provides a CLI tool and Maven plugin for enforcing JBCT coding standards. Features include source code formatting with method chain alignment, a linter with 36 rules across 13 categories, compliance scoring (0-100), project scaffolding, slice project verification, and AI tooling integration.

## Usage

### CLI

```bash
jbct format src/main/java          # Format in-place
jbct format --check src/main/java  # Check formatting (CI)
jbct lint src/main/java            # Check JBCT compliance
jbct check src/main/java           # Combined format-check + lint
jbct score src/main/java           # Calculate compliance score (0-100)
jbct init my-project               # Create new JBCT project
jbct init --slice my-service       # Create Aether slice project
jbct upgrade                       # Self-update to latest version
jbct verify-slice                  # Validate slice configuration
```

### Maven Plugin

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>0.6.1</version>
</plugin>
```

Goals: `jbct:format`, `jbct:format-check`, `jbct:lint`, `jbct:check`, `jbct:score`, `jbct:collect-slice-deps`, `jbct:verify-slice`.

### Configuration

Uses `jbct.toml` for project configuration:

```toml
[format]
maxLineLength = 120
indentSize = 4
alignChainedCalls = true

[lint]
failOnWarning = false
# excludePackages = ["some.generated.**"]
```

### Lint Rules (36 total)

Categories: Return Kinds, Value Objects, Exceptions, Naming, Lambda/Composition, Patterns, Style, Logging, Architecture, Static Imports, Utilities, Nesting, Zones, Sealed Types, Acronyms.

## Dependencies

- Java 25+
- Maven 3.9+ (for plugin)
