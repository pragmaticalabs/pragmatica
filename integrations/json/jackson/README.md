# Jackson Integration

Result-based JSON serialization and deserialization with Jackson 3.0.

## Overview

Wraps Jackson 3.0 operations to return `Result<T>` instead of throwing exceptions. Provides a `JsonMapper` API for safe serialization and deserialization, with built-in support for `Result<T>` and `Option<T>` types.

All JSON errors are mapped to typed `JsonError` causes (`ParseFailed`, `MappingFailed`, `SerializationFailed`).

## Usage

```java
import org.pragmatica.json.JsonMapper;

// Default mapper with Result/Option support
var mapper = JsonMapper.defaultJsonMapper();

// Serialize to JSON
Result<String> json = mapper.writeAsString(object);

// Deserialize from JSON
Result<User> user = mapper.readString(json, User.class);

// Generics via TypeToken
Result<List<User>> users = mapper.readString(json, new TypeToken<List<User>>() {});
```

### Custom Mapper

```java
JsonMapper mapper = JsonMapper.jsonMapper()
    .withPragmaticaTypes()
    .configure(builder -> builder
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
    .withModule(customModule)
    .build();
```

### Pragmatica Type Serialization

`Result<T>` serializes as `{"success": true, "value": {...}}` or `{"success": false, "error": {...}}`.

`Option<T>` serializes as the value itself or `null` for `Option.none()`.

### Error Handling

```java
mapper.readString(json, User.class)
    .onFailure(error -> {
        switch (error) {
            case JsonError.ParseFailed e -> log.error("Invalid JSON: {}", e.message());
            case JsonError.MappingFailed e -> log.error("Cannot map: {}", e.message());
            case JsonError.SerializationFailed e -> log.error("Serialization error: {}", e.message());
        }
    });
```

## Dependencies

- Jackson 3.0+ (`tools.jackson.core:jackson-databind`)
- `pragmatica-lite-core`
