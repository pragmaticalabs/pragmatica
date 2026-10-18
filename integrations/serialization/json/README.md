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
`Ticketing.BuyRequest` becomes `Ticketing_BuyRequestJson`. The levels are joined with `_`, and an `_` or `$` inside a
simple name is escaped as `_0` or `_1`, so a top-level `A_B` gets `A_0BJson` while a nested `A.B` gets `A_BJson`. Since
a simple name never starts with a digit, every generated name decodes back to exactly one type: two types of one
package never need the same generated class, whichever artifacts compile them. The same flat names are used for the
nested codec interfaces; types of different packages that meet in one generated class with the same flat name (`y.Foo`
and `z.Foo`) get numbered nested names (`FooJsonCodec`, `FooJsonCodec2`, the entry point's own type first, the others
in qualified-name order).

```java
Result<BuyRequest> request = BuyRequestJson.decode(bytes);        // byte[] holding UTF-8
Result<BuyRequest> same    = BuyRequestJson.decode("{\"customer\":\"c-1\",\"event\":\"e-9\"}");
byte[] json                = BuyRequestJson.encode(request.unwrap());

JsonTypeCodec<BuyRequest> codec = BuyRequestJson.CODEC;           // the codec itself, to pass around
JsonTypeCodec<BuyRequest> strict = BuyRequestJson.codec(options);  // the same codec under deploy-time options
```

The entry point's public members are `CODEC`, `codec(JsonOptions)` (see [Options](#options)), the `decode(byte[])`,
`decode(String)` and `encode(T)` shortcuts, and `CODECS`: a map from class to codec for the type and every record and enum it reaches. The entry point is also the
type's `ServiceLoader` provider for the lookup below, which adds a public no-argument constructor and the
`type()` and `codec()` methods of `JsonCodecProvider`. The code behind them sits in package-private nested interfaces
and is not part of the API.

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
| `UnknownField(field, position)` | a member no component declares, with `failOnUnknown` set |
| `DuplicateKey(key, position)` | a key repeated within one object, with `failOnDuplicate` set |
| `StringTooLong(limit, position)` | a string, key or number text over `maxStringLength` |
| `NumberTooLong(limit, position)` | a number with more digits than `maxNumberLength` |
| `DocumentTooLarge(limit, position)` | a document over `maxDocumentSize` bytes, refused before reading |

Every `position` is an offset into the input (for a key, its opening quote; for a document, the first byte past the
limit).

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
Result<Option<JsonTypeCodec<BuyRequest>>> codec = JsonCodecs.codecFor(BuyRequest.class);
```

Empty means the class is not a `@Json` type its class loader sees.

Each `@Json` type's entry point is its own provider: it implements `JsonCodecProvider` and the processor lists every
entry point, sorted, in `META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider`. A lookup derives
the provider's name from the class (package, flat name, `Json`, exactly as the processor names the entry point),
streams the providers of the class's own loader (`type.getClassLoader()`, the system class loader for a JDK class)
through `ServiceLoader.stream()` without instantiating them, stops at the first provider of that name, and
instantiates only that one, after checking that its class carries `@GeneratedFor` naming the class. The provider must
then serve exactly that class with a non-null codec. The answer is computed on the first lookup of that class and kept
with the class (a `ClassValue`), so later lookups never rescan the class path and a discarded class loader is not kept
alive by the cache.

The first lookup of a class costs a scan proportional to the number of `@Json` types its class loader sees.
Streaming a provider loads its class (without initialising it), so a hit loads the provider classes listed up to its
own, and a miss (any class that is not a `@Json` type, `String` included) loads every listed provider class. Each
class pays this once; every later lookup of it is a cache read. Warming `N` types costs up to `N` scans of up to `N`
providers, so with thousands of `@Json` types on one class loader, prefer the generated `<Type>Json` entry point
where the type is known statically.

There is no per-package class, so artifacts compose: two artifacts with `@Json` types in one package (`src/main` and
`src/test`, or two modules sharing a package) each list their own providers and all resolve, and the same type
compiled into two artifacts gets the same provider name in both, which the `ServiceLoader` loads once, so it resolves
to the copy the class path finds first, which is the same code.

The lookup fails with `JsonCodecs.ProviderFailed` when the provider of the class's name is broken. Its class was found but is not marked as generated for the class, cannot be instantiated (a constructor or
initialiser that throws), or its `type()` or `codec()` throws, returns `null` or names another class; or the services
files list the name but the `ServiceLoader` cannot load it (the class is missing, does not implement
`JsonCodecProvider`, or has no public no-argument constructor). A broken entry of **another** name never changes the
answer. The `ServiceLoader` reports an entry it cannot load while streaming, before its name is known, so the lookup
records it, streams on, and consults the recorded failures only when no provider of the requested name turned up and
the services files list that name; they are then the failure's origin. The isolation is per entry, not per line: the
JDK parses each services file as a whole, and one malformed line (a name containing a space or an illegal character)
makes it reject that entire file, so every provider listed in it, healthy ones included, cannot be loaded and its
type answers `ProviderFailed`. Services files of other class path entries are unaffected, with one JDK quirk: a
name on a line above the malformed one was already counted as seen, so the same name listed in a later file is
skipped too. The processor never writes such a line; a hand-edited or badly merged file can. Building the failure never throws: a cause chain is walked to a
bounded depth and stops at a throwable already seen, and an exception that cannot print itself is described by its
class name.

The lookup never throws, with one exception: a `VirtualMachineError` (an exhausted stack or heap) raised by the
lookup's own calls (while the `ServiceLoader` loads a provider class, or by a provider's `type()` or `codec()`)
propagates, because no `Cause` can carry it. One thrown by a provider's constructor or static initialiser does not
propagate: the JDK's `ServiceLoader` wraps every throwable of instantiation, errors included, into a
`ServiceConfigurationError`, so the lookup answers `ProviderFailed`, and that answer is kept for the life of the
class like any other, even when the error was transient (a momentary `OutOfMemoryError`).

One class loader's `ServiceLoader` yields at most one provider class per name, and the scan stops at the first, so
there is no second candidate to choose between. Whatever class is found under the name must still carry the marker
and serve exactly the requested class, so a foreign class there is answered as `ProviderFailed`, never used.

Incremental builds: the services file is written in the last round of a compilation that generated an entry point.
An entry a previous compilation left in the output is kept while its class still resolves (recompiling some `@Json`
types keeps the others) and dropped once its class is gone. "Resolves" means the compiler can find the class, so this
retention needs the output directory on the processor's compile class path, as Maven puts it; a plain
`javac -d out` without `out` on `-cp` resolves none of the earlier entries and writes a file listing only the types
it compiled, which leaves the others in `out` unlisted (their lookup answers empty) until a full build. A type that lost `@Json` keeps its old entry point, and
so its entry, while that class file remains in the output: until the build tool removes the outputs of the changed
source (IDE incremental builds track each entry point's originating type) or the output is cleaned (`mvn clean`).

Every generated class carries `@GeneratedFor` (from the `spi` package, runtime retention) naming what it was generated
for. A generated name that is already taken is a compile error naming both, never an overwrite: a hand-written
`OrderJson` next to `@Json Order`, or a class generated for a different source (by its marker), on the class path or
left in the output directory. A class generated for the same source is regenerated. A compiled class at the name
without the marker that looks like generated JSON codec code is reported as stale output of an older processor
version, to be removed by cleaning the build; any other unmarked class gets a message naming both ways out (clean a
stale build, or rename). Entry points end in `Json` and slice route holders in `Codecs`, so the two kinds never share
a name.

Only `@Json` types have a provider. A record or enum reachable only as a component of one has codec code inside that
type's entry point, but it is not a lookup target: `codecFor` returns an empty success for it. Annotate it to make it one.

### Options

Everything but names is configured at deploy time, through one `JsonOptions` record that the generated codecs and the
Jackson mapper both take, so the two keep writing one wire form and accepting the same documents under any
configuration, not only at the defaults:

```java
var options = JsonOptions.jsonOptions(NullInclusion.EXCLUDE,  // nullInclusion: leave out empty Option components
                                      true,                   // failOnUnknown
                                      true,                   // failOnDuplicate
                                      64,                     // maxDepth
                                      1_000_000,              // maxStringLength (UTF-16 characters)
                                      1_000,                  // maxNumberLength (digits)
                                      4_194_304)              // maxDocumentSize (bytes)
                         .unwrap();                           // a Result: limits must be positive, strings >= 64

JsonTypeCodec<BuyRequest> codec = BuyRequestJson.codec(options);            // = BuyRequestJson.CODEC.withOptions(options)
JsonTypeCodec<List<BuyRequest>> list = JsonTypeCodec.listOf(BuyRequestJson.CODEC).withOptions(options);
JsonCodecs.codecFor(BuyRequest.class).map(found -> found.map(c -> c.withOptions(options)));

JsonMapper jackson = JsonMapper.jsonMapper().withPragmaticaTypes().withValueObjects().withOptions(options).build();
```

| Field | Default | Effect | Jackson mapping |
|---|---|---|---|
| `nullInclusion` | `INCLUDE` | `EXCLUDE` leaves out an empty `Option` component; list elements, map values and a top-level `Option` stay `null` | as-property inclusion override `NON_EMPTY` for `Option` |
| `failOnUnknown` | `false` | refuse an undeclared member instead of skipping it | `FAIL_ON_UNKNOWN_PROPERTIES` |
| `failOnDuplicate` | `false` | refuse a repeated key (records, maps, skipped objects) instead of keeping the last value | `STRICT_DUPLICATE_DETECTION` |
| `maxDepth` | 1000 | nesting limit, per container | `maxNestingDepth` |
| `maxStringLength` | `Integer.MAX_VALUE` | string values read, every key, the text of an integer or a skipped number | `maxStringLength` and `maxNameLength` |
| `maxNumberLength` | `Integer.MAX_VALUE` | digits of any number, read or skipped | `maxNumberLength` |
| `maxDocumentSize` | `Integer.MAX_VALUE` | bytes of the document, checked before reading | `maxDocumentLength` |

`JsonOptions.DEFAULTS` is exactly the behaviour of `CODEC` and of every codec built without options. Its limits accept
everything accepted before options existed: Jackson 3's own `StreamReadConstraints` defaults (depth 500, strings
100,000,000 characters, keys 50,000, numbers 1,000 digits) would refuse some of it, so `withOptions(DEFAULTS)` raises
Jackson to these limits rather than the other way round. A service reading untrusted input should lower them; a long
number costs quadratic time in `BigDecimal`. A `maxDepth` above 1000 gives up part of the stack-safety guarantee of
[Strictness](#strictness), since generated readers recurse once per container.

The keys follow `JsonConfig` of the Aether HTTP client (`naming`, `null_inclusion`, `fail_on_unknown`): `INCLUDE` and
`EXCLUDE` are its constant names. Its `NON_EMPTY` also drops empty strings and collections, which the generated codecs
never do, so it has no counterpart.

Options cost nothing on the fast path. `nullInclusion` is consulted only when a component is empty; `failOnUnknown`
only on an unknown key; `failOnDuplicate` only when the seen-field bit mask (or a map's `put`) reports a repeat; each
limit is at most one comparison per string, number or container, and the document size one comparison per decode.

Where Jackson's enforcement is an artefact of its buffers, the generated codecs follow the rule rather than the
artefact, and the difference is pinned by `OptionsConformanceTest`: a skipped string value is not measured (Jackson
measures one only when its text buffer grows, from 200 characters, so it refuses some long skipped strings), and the
text of a number is measured where Jackson measures it (integers, and any skipped number; not a floating-point number
that is read). Declared field names are matched without a length check, which is why `maxStringLength` must be at
least 64.

### Names

Names are fixed at build time. The processor option `json.naming` translates every component name of the
compilation's `@Json` types (enum constants and map keys are never translated):

```xml
<compilerArgs>
    <arg>-Ajson.naming=snake_case</arg>   <!-- as_declared (default), snake_case, kebab_case, pascal_case -->
</compilerArgs>
```

`@JsonName("...")` on a record component or an enum constant overrides the strategy for that one name. It is not a
Jackson annotation, so a type using it keeps its generated codec. A name must be non-empty and hold no quote,
backslash or control character, and two components (or constants) of one type may not end up with the same name;
both are compile errors.

On the Jackson side, `withNaming(NamingStrategy.SNAKE_CASE)` applies the same translation (each strategy translates
exactly as Jackson's `PropertyNamingStrategies` constant of the same meaning), and `withValueObjects()` honours
`@JsonName`. Another code generator reaches the same choice through `new JsonCodecGenerator(env, packageName, naming)`.

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

A region is read where it lies, so the `position` of a `JsonError` from a region decode is an offset into the whole
`buffer`, not into the region: a document at `offset` 5 refused at its own byte 12 reports position 17.

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
| `Option<T>` | the value, or `null` for empty (left out under `nullInclusion` `EXCLUDE`); an absent member reads as empty |
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

- Every non-`Option` component is required; a missing one fails with `MissingField`. A repeated key keeps its last
  value unless `failOnDuplicate` is set.
- `null` is accepted only for an `Option`; anywhere else (a field, a list element, a map value) it fails with
  `UnexpectedNull` or `Malformed`.
- Unknown members are skipped (their values are still validated as JSON), unless `failOnUnknown` is set.
- Objects and arrays may nest at most 1000 deep by default (`JsonReader.MAX_DEPTH`, `maxDepth`), counted per
  container, so hostile input fails with `NestingTooDeep` instead of overflowing the stack.
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
- A `@Json` type must be a top-level or member type in a named package. In the unnamed package it is a compile
  error. A local type (declared in a method, initializer or lambda), or one nested in a local or anonymous class, is
  never handed to annotation processors; under javac the processor reports it as a compile error after attribution.
  That check runs once the processor is initialised, which javac does when the compilation has any annotation outside
  method bodies.
- At most 64 components per record.
- Records and enums must be accessible from their own package (public, or package-private in the same package), and
  so must a value object's factory.
- Naming is per compilation (`-Ajson.naming`), not per type; `@JsonName` renames single components.

## Packages

- `org.pragmatica.serialization.json`: the API (`@Json`, `@JsonName`, `JsonTypeCodec`, `JsonCodecs`, `JsonError`,
  `JsonOptions`, `NamingStrategy`).
- `org.pragmatica.serialization.json.spi`: machinery for generated code (reader, writer, field-name tables, the
  provider interface). Not a stable API; only reusable `JsonReader` and `JsonWriter` instances are meant to be held
  by callers.
