# JBCT CLI Project

## Project Overview
CLI tool and Maven plugin for JBCT (Java Backend Coding Technology) code formatting and linting.

## Current Status: Formatter Implementation Complete (15/15 tests passing)

All golden tests pass. The formatter handles JBCT-style formatting.

## Key JBCT Formatting Rules

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

6. **Method parameters**: Wrap and align when exceeding line length
   ```java
   Result<Response> manyParams(String userId,
                               String token,
                               long expiresAt,
                               Option<String> refreshToken) {
   ```

7. **Import grouping**: Blank lines between groups (org.pragmatica, java/javax, static)

8. **Blank lines**: Between methods, between type declarations, no blank after opening brace

9. **Empty types**: Single line `{}` for empty interfaces, records, annotations

## Implementation Architecture

- **JbctPrettyPrinterVisitor**: Custom AST visitor for JBCT formatting
- **JbctFormatter**: Entry point using the custom printer
- **FormatterConfig**: Configuration (line length, indent size)

### Key Tracking Variables
- `chainAlignColumn`: Tracks chain dot alignment for lambda body alignment
- `argumentAlignStack`: Tracks when inside broken argument lists
- `currentColumn`: Tracks output column for alignment calculations

## Known Limitations

1. **Orphan comments** - Comments not attached to nodes (e.g., standalone line between class brace and first member) may be dropped
2. **Binary expression wrapping** - Long boolean/arithmetic expressions not auto-wrapped
3. **Array initializer wrapping** - Long array initializers not auto-wrapped

## Commands

```bash
# Run formatter tests
mvn test -pl jbct-core -Dtest=GoldenFormatterTest

# Run all tests
mvn test -pl jbct-core
```

## Golden Examples Location
`jbct-core/src/test/resources/format-examples/`
