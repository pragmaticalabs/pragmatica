# Serialization Module

Binary serialization for Pragmatica Lite.

## Overview

Provides compile-time generated binary codecs via `SliceCodec` and the `@Codec` annotation processor. Types annotated with `@Codec` get efficient, zero-reflection serialization at compile time.

## Modules

- **api** - Core interfaces (`Serializer`, `Deserializer`, `SliceCodec`, `FrameworkCodecs`) and the `@Codec` annotation
- **codec-processor** - Annotation processor that generates `TypeCodec` implementations for `@Codec`-annotated records

## Usage

```java
// Annotate your types
@Codec
public record User(String name, int age) {}

// Generated codecs are collected into per-package registries (e.g. UserCodec, MyPackageCodecs)
// Build a SliceCodec with framework codecs + your generated codecs
var codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), MyPackageCodecs.CODECS);

// Serialize/deserialize via ByteBuf (Netty)
codec.write(buffer, myObject);
MyObject result = codec.read(buffer);
```

## Dependencies

- `pragmatica-lite-core`
- Netty (for ByteBuf)
