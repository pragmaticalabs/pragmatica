# Example Slice: String Processor

A simple example slice demonstrating the complete Aether slice lifecycle.

## Overview

Provides basic string processing functionality (converting strings to lowercase) and serves as a reference implementation for creating deployable slices. Demonstrates ServiceLoader integration, type-safe entry points, complete start/stop lifecycle, and proper test coverage.

## Usage

### Building

```bash
mvn package
```

### Loading with SliceStore

```java
var artifact = Artifact.artifact("org.pragmatica-lite.aether:example-slice:0.15.1");
var sliceStore = SliceStore.sliceManager();

sliceStore.loadSlice(artifact)
    .onSuccess(loadedSlice -> sliceStore.activateSlice(artifact))
    .onSuccess(activeSlice -> {
        var processor = (StringProcessorEntryPoint) activeSlice;
        String result = processor.toLowerCase("HELLO WORLD"); // "hello world"
    });
```

### Architecture

- `StringProcessorSlice` - Main slice implementation with lifecycle methods
- `StringProcessorEntryPoint` - Interface defining available operations
- `ActiveStringProcessorSlice` - Active implementation providing functionality
- ServiceLoader configuration in `META-INF/services/org.pragmatica.aether.slice.Slice`

## Dependencies

- `pragmatica-lite-slice-api`
- `pragmatica-lite-core`
