# Aether Project - Testing Patterns and Conventions

## Testing Framework and Patterns

This project uses JUnit 5 and AssertJ for testing. Follow these established patterns when writing tests:

### Test Class Structure
- Test classes should be package-private (no visibility modifier)
- Use descriptive test method names with underscores: `method_scenario_expectation()`
- Group related test methods logically

### Import Patterns
```java
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
```

### Result<T> Testing Patterns

#### Success Cases
For successful Result operations, use `.onSuccess()` with lambda assertions:
```java
@Test
void parsing_with_valid_input() {
    SomeType.parse("valid-input")
           .onSuccess(result -> {
               assertThat(result.field1()).isEqualTo("expected");
               assertThat(result.field2()).isEqualTo(42);
           })
           .onFailureRun(Assertions::fail);
}
```

#### Failure Cases
For expected failures, use `.onSuccessRun(Assertions::fail)` followed by failure assertions:
```java
@Test
void parsing_rejects_invalid_format() {
    SomeType.parse("invalid-format")
           .onSuccessRun(Assertions::fail)
           .onFailure(cause -> assertThat(cause.message()).contains("Invalid format"));
}
```

#### Simple Success Validation
For cases where you only need to verify success without checking details:
```java
@Test
void parsing_handles_simple_cases() {
    SomeType.parse("valid-input")
           .onFailureRun(Assertions::fail);
}
```

### Complex Object Testing

#### Multiple Component Validation
Use `Result.all()` for complex object construction in tests:
```java
@Test
void complex_object_creation() {
    Result.all(Component1.create("value1"),
               Component2.create("value2"),
               Component3.create("value3"))
          .map(ComplexType::create)
          .map(ComplexType::serialize)
          .onSuccess(result -> assertThat(result).isEqualTo("expected:serialized:format"))
          .onFailureRun(Assertions::fail);
}
```

#### Roundtrip Testing
Always include roundtrip tests for parseable types:
```java
@Test
void parsing_roundtrip_consistency() {
    var originalString = "original:input:format";
    
    SomeType.parse(originalString)
           .map(SomeType::asString)
           .onSuccess(result -> assertThat(result).isEqualTo(originalString))
           .onFailureRun(Assertions::fail);
}
```

### Test Coverage Guidelines

#### Success Path Testing
- Valid input with all components
- Valid input with optional components (qualifiers, etc.)
- Edge cases (zeros, empty optional fields)
- Complex but valid scenarios

#### Failure Path Testing  
- Invalid format strings
- Invalid individual components
- Edge case failures (negative numbers, etc.)
- Empty/null inputs

#### Utility Method Testing
- String representation methods (`toString()`, `asString()`, `bareVersion()`)
- Conversion methods between formats
- Optional field handling

### Error Assertion Patterns

#### Detailed Error Messages
When error messages are important, check them specifically:
```java
.onFailure(cause -> assertThat(cause.message()).contains("Invalid version format"))
```

#### Generic Error Validation
When the specific error doesn't matter, just validate failure occurred:
```java
.onFailure(cause -> assertThat(cause).isNotNull())
```

### Test Organization
- Group related tests (valid cases, invalid cases, edge cases)
- Use consistent naming patterns within test classes
- Test both public API methods and core functionality
- Include integration-style tests for complex interactions

### Method Reference Usage
- Use `Assertions::fail` for method references in failure cases
- Use `SomeType::methodName` for method references in mapping operations
- Prefer method references over lambdas when the lambda only calls a single method

## Project-Specific Testing Notes

- All parsing methods return `Result<T>` and should be tested with both success and failure paths
- String serialization methods should have roundtrip tests
- Complex objects built from multiple components should use `Result.all()` patterns
- Error messages should be descriptive and tested when they provide user value

## Build and Test Commands

- Run tests: `./mvnw test`
- Run specific test class: `./mvnw test -Dtest=ClassName`
- Skip tests: `./mvnw install -DskipTests`

Follow these patterns consistently to maintain code quality and test reliability across the Aether project.