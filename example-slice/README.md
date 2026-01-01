# Example Slice - String Processor

A simple example slice implementation that demonstrates the complete Aether slice lifecycle.

## Overview

This example slice provides basic string processing functionality (converting strings to lowercase) and serves as a
reference implementation for creating deployable slices in the Aether runtime environment.

## Features

- **Simple String Processing**: Converts input strings to lowercase
- **Complete Lifecycle**: Demonstrates start/stop slice lifecycle
- **ServiceLoader Integration**: Properly configured for automatic discovery
- **Type-Safe Entry Points**: Uses EntryPoint definitions with type tokens
- **Comprehensive Tests**: Unit tests and integration tests included

## Maven Coordinates

```xml
<groupId>org.pragmatica-lite.aether</groupId>
<artifactId>example-slice</artifactId>
<version>0.6.4</version>
```

## Usage

### Building

```bash
mvn package
```

This produces `target/example-slice-0.6.4.jar` which can be loaded by the Aether runtime.

### Loading with SliceStore

```java
var artifact = Artifact.artifact("org.pragmatica-lite.aether:example-slice:0.6.4");
var sliceStore = SliceStore.sliceManager();

// Load the slice
sliceStore.loadSlice(artifact)
          .onSuccess(loadedSlice -> {
              // Activate the slice
              return sliceStore.activateSlice(artifact);
          })
          .onSuccess(activeSlice -> {
              // Use the slice
              var processor = (StringProcessorEntryPoint) activeSlice;
              String result = processor.toLowerCase("HELLO WORLD");
              // result = "hello world"
          });
```

### Entry Points

The slice provides the following entry points:

#### `toLowerCase(String input)`

- **Purpose**: Converts input string to lowercase
- **Parameters**: `String input` - the string to convert
- **Returns**: `String` - the input converted to lowercase
- **Behavior**: Returns `null` if input is `null`

## Implementation Details

### Architecture

The slice follows the standard Aether slice architecture:

- **`StringProcessorSlice`**: Main slice implementation with lifecycle methods
- **`StringProcessorEntryPoint`**: Interface defining available operations
- **`ActiveStringProcessorSlice`**: Active implementation providing the actual functionality
- **ServiceLoader Configuration**: Registered in `META-INF/services/org.pragmatica.aether.slice.Slice`

### Lifecycle

1. **Loading**: Slice class is loaded via ServiceLoader
2. **Starting**: `start()` method returns an `ActiveSlice` instance
3. **Operation**: Entry point methods can be called on the active slice
4. **Stopping**: `stop()` method cleans up resources (none in this simple example)

### Testing

The module includes comprehensive tests:

- **Unit Tests**: Test slice lifecycle and string processing functionality
- **Integration Tests**: Test ServiceLoader discovery and complete workflow
- **Coverage**: All public methods and edge cases (null handling, empty strings)

## Development Notes

This example slice demonstrates:

- ✅ Proper ServiceLoader configuration
- ✅ Type-safe entry point definitions
- ✅ Complete lifecycle implementation
- ✅ Error handling patterns
- ✅ Test coverage following CLAUDE.md patterns
- ✅ Maven packaging for deployment

Use this as a template for creating more complex slices in your Aether applications.