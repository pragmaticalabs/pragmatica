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

## Enums on the wire, and the rolling-upgrade contract (#964)

An enum is encoded as its **`ordinal()`**. The ordinal *is* the wire value, so the order of the
constants is a wire contract, not a source-file detail.

**Every `@Codec` enum must declare `UNKNOWN` as its LAST constant.** The processor refuses to
generate a codec otherwise, and the message names the enum. An ordinal a node does not have decodes
to `UNKNOWN` instead of throwing, which is what keeps the rest of the message usable:

```java
@Codec
public enum Tier {
    BASIC,
    PREMIUM,
    UNKNOWN   // last, always
}
```

**Why last.** It keeps both natural edits safe. A constant appended *after* `UNKNOWN` takes an
ordinal an older node reads as out of range; a constant inserted *before* it takes the ordinal
`UNKNOWN` itself used to hold. Either way an older node decodes the new value as its own `UNKNOWN`.
A sentinel in the middle has neither property — constants after it are silently remapped onto other
legitimate values, which is corruption rather than a clean unknown.

**What you owe at every consumer.** `UNKNOWN` is a value your handlers will see. On an
**authorization or condemnation path it must refuse** — an unrecognised value quietly becoming a
valid-looking one is a fail-open introduced by a robustness feature. Adding the constant breaks
exhaustive `switch`es on purpose: that compile error is where the decision gets made.

**Slice (application) enums are opt-in.** The slice processor uses the sentinel when the enum
declares it and otherwise emits a bounds-checked read that fails with `UnknownEnumOrdinalException`
naming the enum and the ordinal. The message is dropped either way — the difference is that it is
attributable. A compiler warning says so at the declaration.

**A new record type is not an escape hatch.** An unknown *type tag* has the same shape: the message
is dropped, correctly (an old node is not expected to handle a new message type), but it is now
logged as version skew and counted as `quic_unknown_type_tag_drops_total` rather than logged as a
generic decode error.

**Changing any of this is visible.** `WireAssignmentTripwireTest` (in `aether/node`) pins every
type's tag and every enum's constant ordering against a checked-in baseline and fails on any drift.
Pre-GA that is a tripwire — re-record the baseline and the change shows up in review. Post-GA it
becomes a gate; see the `MODE` line in that test.

## Dependencies

- `pragmatica-lite-core`
- Netty (for ByteBuf)
- SLF4J (version-skew disclosure from `UnknownEnumOrdinals`)
