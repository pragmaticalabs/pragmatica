# JBCT CLI Project

## Project Overview
CLI tool and Maven plugin for JBCT (Java Backend Coding Technology) code formatting and linting.

## Current Status: Formatter Implementation (12/15 tests passing)

### Passing Tests
- ChainAlignment, Lambdas, Records, TernaryOperators, Comments
- MultilineArguments, Imports, Annotations, SwitchExpressions
- Plus 3 others

### Failing Tests (3 remaining)
1. **BlankLines** - orphan comments, nested class blank line handling
2. **LineWrapping** - method parameter wrapping, line length enforcement
3. **MultilineParameters** - method parameter declaration wrapping

## Key JBCT Formatting Rules

1. **Chain alignment**: `.` aligns to the receiver end
   ```java
   return input.map(String::trim)
               .map(String::toUpperCase);
   ```

2. **Fork-join pattern**: `.all()` in chains always wraps arguments
   ```java
   return Result.all(first(),
                     second(),
                     third())
                .flatMap(this::process);
   ```

3. **Ternary**: `?` and `:` align to opening `(` of expression
   ```java
   return condition
          ? "yes"
          : "no";
   ```

4. **Lambda in broken args**: Body aligns to parameter + 4, `}` aligns to parameter
   ```java
   return input.fold(cause -> {
                         logError(cause);
                         return defaultValue;
                     },
                     value -> {
                         return value.toUpperCase();
                     });
   ```

5. **Lambda in chain**: Body aligns to chain + 4, `}` aligns to chain position
   ```java
   return items.stream()
               .filter(s -> {
                   var trimmed = s.trim();
                   return !trimmed.isEmpty();
               })
               .toList();
   ```

6. **Import grouping**: Blank lines between groups (org.pragmatica, java/javax, static)

7. **Blank lines**: Between methods, between type declarations, no blank after opening brace

## Implementation Architecture

- **JbctPrettyPrinterVisitor**: Custom AST visitor for JBCT formatting
- **JbctFormatter**: Entry point using the custom printer
- **FormatterConfig**: Configuration (line length, indent size)

### Key Tracking Variables
- `chainAlignColumn`: Tracks chain dot alignment for lambda body alignment
- `argumentAlignStack`: Tracks when inside broken argument lists
- `currentColumn`: Tracks output column for alignment calculations

## Remaining Work

### High Priority
1. **Method parameter wrapping** - Wrap long parameter declarations like golden examples
2. **Orphan comment handling** - Comments not attached to nodes (like first comment after `{`)

### Medium Priority
3. **Nested class blank lines** - BlankLines.java expects blank line after `static class NestedClass {`
4. **Line length enforcement** - Break long lines automatically

### Low Priority
5. **Markdown doc comments (`///`)** - Requires JavaParser master branch for JEP 467

## Commands

```bash
# Run formatter tests
mvn test -pl jbct-core -Dtest=GoldenFormatterTest

# Run all tests
mvn test -pl jbct-core
```

## Golden Examples Location
`jbct-core/src/test/resources/format-examples/`
