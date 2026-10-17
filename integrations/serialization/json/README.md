# Pragmatica Lite Serialization JSON

Reflection-free JSON for records and enums. An annotation processor generates a codec per `@Json` type at compile
time: plain Java that reads and writes UTF-8 bytes directly, with no reflection, no runtime type introspection and
no Jackson. Decoding returns `Result<T>`; malformed or unacceptable input is a typed `JsonError`, never an exception.

The module depends on `core` only and works outside Aether. Inside Aether, the slice processor generates the same
codecs for HTTP route request and response types automatically; those need no annotation.

## Maven setup

Two pieces: the runtime as a normal dependency, and the processor on the compiler's processor path.

```xml
<dependencies>
    <dependency>
        <groupId>org.pragmatica-lite</groupId>
        <artifactId>serialization-json</artifactId>
        <version>${pragmatica.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.pragmatica-lite</groupId>
                        <artifactId>serialization-codec-processor</artifactId>
                        <version>${pragmatica.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Put the processor on `annotationProcessorPaths` **only**. Do not add `serialization-codec-processor` as a normal or
`provided` dependency: it also hosts the binary `@Codec` processor, whose dependencies (`serialization-api`, which
depends on `netty-all`) would then land on your compile or runtime class path. On the processor path they stay
inside the compiler.

Setting `annotationProcessorPaths` turns off processor discovery from the class path, so list every other processor
your build uses there as well.

## Usage

Annotate a record or enum:

```java
package com.example.booking;

@Json
public record BuyRequest(String customer, EventId event, Option<String> note) {}
```

The processor writes `BuyRequestJson` next to it. A nested type gets its enclosing types as a prefix:
`Ticketing.BuyRequest` becomes `Ticketing_BuyRequestJson`.

```java
Result<BuyRequest> request = BuyRequestJson.decode(bytes);        // byte[] holding UTF-8
Result<BuyRequest> same    = BuyRequestJson.decode("{\"customer\":\"c-1\",\"event\":\"e-9\"}");
byte[] json                = BuyRequestJson.encode(request.unwrap());

JsonTypeCodec<BuyRequest> codec = BuyRequestJson.CODEC;           // the codec itself, to pass around
```

The entry point's public members are `CODEC`, the `decode(byte[])`, `decode(String)` and `encode(T)` shortcuts, and
`CODECS`: a map from class to codec for the type and every record and enum it reaches. The code behind them sits in
package-private nested interfaces and is not part of the API.

### Failures

A refused document is a failed `Result` carrying a `JsonError`:

| Case | Meaning |
|---|---|
| `Malformed(position, expected)` | not JSON, or not the expected kind of value at that offset |
| `MissingField(field)` | a required field is absent |
| `UnexpectedNull(field, position)` | `null` where the type does not allow it |
| `InvalidField(origin, field)` | a value object's factory, or a record's own factory, refused the value; `origin` is the factory's cause |
| `OutOfRange(position, type)` | a number that does not fit its target type |
| `NestingTooDeep(limit, position)` | objects and arrays nested deeper than the limit |
| `InvalidRange(offset, length, size)` | `decode(bytes, offset, length)` named a region outside the array |

Factory refusals do not stop the decode: every refused field is collected, and the failure is one composite cause
holding an `InvalidField` per field, as `Result.all` would report them.

```java
BuyRequestJson.decode(bytes)
              .onFailure(cause -> log.warn("Rejected request: {}", cause.message()))
              .onSuccess(this::process);
```

### Lookup by class

For code that holds a `Class` rather than the entry point (frameworks, generic adapters):

```java
Option<JsonTypeCodec<BuyRequest>> codec = JsonCodecs.codecFor(BuyRequest.class);
```

The processor generates one registry class per package containing `@Json` types (`<Segment>JsonCodecs`, for package
`com.example.booking` that is `BookingJsonCodecs`; the `codec.registry.suffix` processor option appends a suffix, as
it does for the binary `@Codec` registry) and lists it in
`META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider`. `JsonCodecs` loads every provider with
`ServiceLoader` (the calling thread's context class loader) on the first lookup and keeps the result.

Only `@Json` types are listed. A record or enum reachable only as a component of one has codec code inside that
type's entry point, but it is not a lookup target: `codecFor` returns empty for it. Annotate it to make it one.

### Containers

`JsonTypeCodec` wraps any codec into one for a container of its type:

```java
JsonTypeCodec<List<BuyRequest>> list          = JsonTypeCodec.listOf(BuyRequestJson.CODEC);
JsonTypeCodec<Set<Level>> levels              = JsonTypeCodec.setOf(LevelJson.CODEC);
JsonTypeCodec<Option<BuyRequest>> maybe       = JsonTypeCodec.optionOf(BuyRequestJson.CODEC);   // null <-> empty
JsonTypeCodec<Map<String, BuyRequest>> byName = JsonTypeCodec.mapOf(BuyRequestJson.CODEC);      // String keys

Result<List<BuyRequest>> batch = list.decode(bytes);
```

They write the same bytes, and accept and refuse the same input, as the code the generator emits for a component of
that container type. Lists decode to `ArrayList`, sets to `LinkedHashSet` (a repeated element is kept once) and maps
to `LinkedHashMap` (a repeated key keeps its last value), all in document order. A map keyed by an enum or a value
object is available as a record component, not as a combinator.

### Reuse and regions

```java
var reader = JsonReader.jsonReader();     // org.pragmatica.serialization.json.spi; one per thread
var writer = JsonWriter.jsonWriter(256);  // likewise

Result<BuyRequest> decoded = BuyRequestJson.CODEC.decode(reader, bytes);   // reader reset first
byte[] encoded             = BuyRequestJson.CODEC.encode(writer, value);   // writer reset first; returns a copy

Result<BuyRequest> region  = BuyRequestJson.CODEC.decode(buffer, offset, length);  // read in place, never copied
```

## Supported types

| Java type | JSON |
|---|---|
| `int`, `long`, `double`, `boolean` and their boxes | number, `true` / `false` |
| `String` | string |
| `BigDecimal` | number |
| `Instant` | ISO-8601 string |
| `UUID` | string in canonical `8-4-4-4-12` form only |
| `LocalDate` | ISO-8601 date string |
| `Currency` | ISO 4217 code string |
| enum | constant name; an enum with an `UNKNOWN` constant decodes an undeclared name to it, any other enum refuses it |
| record | object with one member per component, in declaration order |
| value object | its bare component (see below) |
| `Option<T>` | the value, or `null` (and an absent member) for empty |
| `List<T>`, `Set<T>` | array |
| `Map<K, V>` | object; `K` is a `String`, an enum, or a value object over a `String` |

Types nest freely (`List<Option<Item>>`, `Map<String, List<Item>>`, recursive records).

**Value objects.** A record with exactly one component of a scalar type and a JBCT factory (`static Result<T> t(...)`
over that component, named after the type) travels as its bare component: `"a@b.c"`, not `{"value":"a@b.c"}`.
Decoding calls the factory, so a value the factory refuses fails the decode with `InvalidField`. A record with more
components and such a factory is decoded through the factory too; one without a factory through its canonical
constructor. The value-object rule is the one `JsonMapper.valueObjectJsonMapper()` applies on the Jackson side, so
both produce the same wire form.

## Strictness

- Every non-`Option` component is required; a missing one fails with `MissingField`.
- `null` is accepted only for an `Option`; anywhere else (a field, a list element, a map value) it fails with
  `UnexpectedNull` or `Malformed`.
- Unknown members are skipped (their values are still validated as JSON).
- Objects and arrays may nest at most 1000 deep (`JsonReader.MAX_DEPTH`), counted per container, so hostile input
  fails with `NestingTooDeep` instead of overflowing the stack.
- Input must be UTF-8 and is validated: malformed sequences, overlong forms and lone surrogate escapes are refused.
- Nothing but whitespace may follow the top-level value.
- The reader passes the [JSONTestSuite](https://github.com/nst/JSONTestSuite) parsing corpus: every `y_` file
  accepted, every `n_` file refused, and each implementation-defined `i_` file pinned. The corpus and the deliberate
  deviations from Jackson are described in `src/test/resources/jsontestsuite/README.md`.

## Limitations

- No sealed or polymorphic types, and no interfaces: such a type cannot be `@Json` (compile error), and inside a
  route it stays on the Jackson fallback.
- A type carrying a Jackson annotation (on itself, a member, a parameter, a supertype, or through a meta-annotation)
  is refused: the generated codec would silently ignore it.
- Generic records are refused.
- At most 64 components per record.
- Records and enums must be accessible from their own package (public, or package-private in the same package), and
  so must a value object's factory.
- No configuration yet: names are the component names, empty `Option`s are written as `null`, and there are no
  per-codec options.

## Packages

- `org.pragmatica.serialization.json`: the API (`@Json`, `JsonTypeCodec`, `JsonCodecs`, `JsonError`).
- `org.pragmatica.serialization.json.spi`: machinery for generated code (reader, writer, field-name tables, the
  provider interface). Not a stable API; only reusable `JsonReader` and `JsonWriter` instances are meant to be held
  by callers.
