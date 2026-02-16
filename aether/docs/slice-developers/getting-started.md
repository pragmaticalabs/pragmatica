# My First Aether Slice

Build, test, and deploy your first slice from scratch.

This tutorial walks you through creating an Aether slice using the JBCT toolchain.
You'll start with a generated project, understand every line of generated code,
modify it to make it your own, and deploy it to a local Forge.

## Prerequisites

| Tool     | Version  | Check            |
|----------|----------|------------------|
| Java     | 25+      | `java --version` |
| Maven    | 3.8+     | `mvn --version`  |
| JBCT CLI | 0.16.0+  | `jbct --version` |

> **Don't have the JBCT CLI?** See [Installing JBCT](../reference/installing-jbct.md).

## Step 1: Create Your Slice Project

```bash
jbct init my-first-slice --slice
cd my-first-slice
```

This runs `jbct init` with the `--slice` flag, which generates a complete Aether slice project.
By default it uses `com.example` as the group ID and derives the artifact ID from the directory name.

> **Customizing coordinates:** You can override the defaults:
> ```bash
> jbct init my-first-slice -g org.mycompany -a my-first-slice --slice
> ```

Here's the generated directory tree:

```
my-first-slice/
├── pom.xml                                        # Maven build (Java 25, JBCT plugin)
├── jbct.toml                                      # JBCT formatter/linter config
├── CLAUDE.md                                      # AI agent instructions
├── .gitignore
├── deploy-forge.sh                                # Deploy to local Forge
├── deploy-test.sh                                 # Deploy to test environment
├── deploy-prod.sh                                 # Deploy to production
├── generate-blueprint.sh                          # Generate deployment blueprint
└── src/
    ├── main/
    │   ├── java/com/example/myfirstslice/
    │   │   └── MyFirstSlice.java                  # Your slice (interface + impl)
    │   └── resources/
    │       ├── slices/
    │       │   └── MyFirstSlice.toml              # Slice runtime config
    │       └── META-INF/dependencies/
    │           └── com.example.myfirstslice.MyFirstSlice  # Dependency manifest
    └── test/
        ├── java/com/example/myfirstslice/
        │   └── MyFirstSliceTest.java              # Unit test
        └── resources/
            └── log4j2-test.xml                    # Test logging config
```

Notice something unusual? There's only **one Java source file** — `MyFirstSlice.java`.
In Aether, the slice interface, request/response types, error types, factory method,
and implementation all live together. This isn't an accident — it's a deliberate design
that keeps related code together and eliminates scattered files.

## Step 2: Build and Test

```bash
mvn clean verify
```

This runs the full build pipeline:

1. **Compile** — compiles your source with the slice annotation processor
2. **Annotation processing** — generates API interface, factory class, and manifest
3. **Test** — runs unit tests
4. **Package** — creates implementation JAR
5. **JBCT check** — verifies formatting and linting rules
6. **Slice packaging** — creates separate API and implementation JARs
7. **Blueprint generation** — creates `target/blueprint.toml`
8. **Slice verification** — validates the slice structure

You should see output ending with:

```
[INFO] BUILD SUCCESS
```

After the build, check the generated artifacts:

```bash
ls target/classes/META-INF/slice/
# MyFirstSlice.manifest

ls target/
# my-first-slice-1.0.0-SNAPSHOT.jar
# blueprint.toml
```

### Troubleshooting Build Failures

**`error: invalid source release: 25`**
Your Java version is too old. Aether requires Java 25+. Check with `java --version`.

**`jbct: command not found`**
The JBCT CLI isn't installed or isn't on your PATH. See [Installing JBCT](../reference/installing-jbct.md).

## Step 3: Understand the Generated Code

Open `src/main/java/com/example/myfirstslice/MyFirstSlice.java`. Let's walk through it
section by section.

### The Full Generated Code

```java
package com.example.myfirstslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

/// MyFirstSlice slice interface.
@Slice
public interface MyFirstSlice {

    /// Request record.
    record Request(String value) {
        public static Result<Request> request(String value) {
            if (value == null || value.isBlank()) {
                return Result.failure(ValidationError.emptyValue());
            }
            return Result.success(new Request(value));
        }
    }

    /// Response record.
    record Response(String result) {}

    /// Validation error.
    sealed interface ValidationError extends Cause {
        record EmptyValue() implements ValidationError {
            @Override
            public String message() {
                return "Value cannot be empty";
            }
        }

        static ValidationError emptyValue() {
            return new EmptyValue();
        }
    }

    Promise<Response> process(Request request);

    static MyFirstSlice myFirstSlice() {
        record myFirstSlice() implements MyFirstSlice {
            @Override
            public Promise<Response> process(Request request) {
                var response = new Response("Processed: " + request.value());
                return Promise.success(response);
            }
        }
        return new myFirstSlice();
    }
}
```

That's a lot to take in. Let's break it down.

### `@Slice` Annotation

```java
@Slice
public interface MyFirstSlice {
```

`@Slice` marks this interface as an Aether slice — a deployable unit of business logic.
The annotation processor reads this at compile time and generates:

- An **API interface** (what consumers see)
- A **factory class** (how the runtime wires dependencies)
- A **manifest** (metadata for packaging and deployment)

Everything that defines this slice — its contract, types, errors, and implementation — lives
inside this single interface. This is called the **"single-file slice"** pattern.

### Nested `Request` Record

```java
record Request(String value) {
    public static Result<Request> request(String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(ValidationError.emptyValue());
        }
        return Result.success(new Request(value));
    }
}
```

The `Request` record is the input type for your slice. Notice two important things:

1. **The constructor is not used directly.** Instead, callers use the factory method
   `Request.request(value)` which returns `Result<Request>` — not `Request`.

2. **Validation happens at construction time.** If the input is invalid, you get a
   `Result.failure(...)` — the `Request` object is never created. This is called
   **"parse, don't validate"**: instead of creating an object and checking it later,
   you ensure it's valid *before* it exists.

**Why not just use the constructor?** Because constructors can't return errors.
A constructor either succeeds or throws an exception. In JBCT, we don't use exceptions
for business errors — we use `Result<T>` to make the possibility of failure explicit
in the type system.

### Nested `Response` Record

```java
record Response(String result) {}
```

The response is simpler — it's always valid by construction (the slice produces it,
so it controls the data). No factory method needed.

### Sealed `ValidationError`

```java
sealed interface ValidationError extends Cause {
    record EmptyValue() implements ValidationError {
        @Override
        public String message() {
            return "Value cannot be empty";
        }
    }

    static ValidationError emptyValue() {
        return new EmptyValue();
    }
}
```

This is how errors are modeled in JBCT — as **sealed interfaces extending `Cause`**, not
as exceptions.

- **`sealed`** means the compiler knows *every possible error variant*. You can't
  accidentally forget to handle one.
- **`extends Cause`** integrates with the `Result` type — any `Cause` can be carried
  inside a `Result.failure(...)`.
- **Each variant is a record** with a `message()` method that describes what went wrong.
- **Factory methods** like `emptyValue()` provide clean construction.

**Why not exceptions?** Exceptions are invisible in the type system. A method that throws
`IllegalArgumentException` looks identical to one that doesn't. With sealed `Cause` types,
the errors are explicit — you can see exactly what can go wrong by looking at the type.

### The `process()` Method

```java
Promise<Response> process(Request request);
```

This is the slice's entry point — the method that consumers call. Every slice method must:

1. **Return `Promise<T>`** — all operations are async-first. Even if your logic is synchronous
   today, wrapping it in `Promise` means it composes with async operations (database queries,
   other slices) without refactoring.
2. **Take exactly one parameter** — this enables uniform serialization, logging, and metrics.
   If you need multiple inputs, put them in the request record.

### Factory Method and Inline Implementation

```java
static MyFirstSlice myFirstSlice() {
    record myFirstSlice() implements MyFirstSlice {
        @Override
        public Promise<Response> process(Request request) {
            var response = new Response("Processed: " + request.value());
            return Promise.success(response);
        }
    }
    return new myFirstSlice();
}
```

This is where the implementation lives. Let's unpack the pattern:

- **`static MyFirstSlice myFirstSlice()`** — the factory method follows the JBCT naming
  convention: `TypeName.typeName()` (type name, lowercased first letter). The runtime
  discovers and calls this to create instances.

- **`record myFirstSlice() implements MyFirstSlice`** — the implementation is an *inline record*
  declared inside the factory method. It's not a separate class in a separate file — it lives
  right next to the interface it implements. The lowercase name is intentional: it matches
  the factory method name, making it clear this is the factory's implementation detail.

- **`Promise.success(response)`** — wraps the result in a successfully resolved `Promise`.

**Why an inline record?** It keeps the implementation private to the factory method.
No one can instantiate it directly — they *must* go through the factory. When your slice
needs dependencies (like a database or another slice), they become parameters of the factory
method and fields of the record:

```java
static MySlice mySlice(SomeDependency dep) {
    record mySlice(SomeDependency dep) implements MySlice {
        // dep is available as this.dep() or just dep
    }
    return new mySlice(dep);
}
```

## Step 4: Understand the Test

Open `src/test/java/com/example/myfirstslice/MyFirstSliceTest.java`:

```java
package com.example.myfirstslice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyFirstSliceTest {

    private final MyFirstSlice slice = MyFirstSlice.myFirstSlice();

    @Test
    void should_process_request() {
        MyFirstSlice.Request.request("test")
            .onFailure(Assertions::fail)
            .onSuccess(request -> slice.process(request)
                .await()
                .onFailure(Assertions::fail)
                .onSuccess(r -> assertThat(r.result()).isEqualTo("Processed: test")));
    }
}
```

Let's trace the flow:

1. **`MyFirstSlice.myFirstSlice()`** — creates an instance via the factory method.
   No `new`, no dependency injection framework — just a method call.

2. **`Request.request("test")`** — creates a validated request. Returns `Result<Request>`.

3. **`.onFailure(Assertions::fail)`** — if validation failed, fail the test.

4. **`.onSuccess(request -> ...)`** — if validation succeeded, call the slice.

5. **`slice.process(request)`** — returns `Promise<Response>`.

6. **`.await()`** — blocks until the `Promise` resolves, returning `Result<Response>`.
   In tests, blocking is fine. In production, you compose `Promise` values with
   `.map()` and `.flatMap()` instead.

7. **`.onSuccess(r -> assertThat(...))`** — verify the response.

## Step 5: Make It Your Own

Let's modify the slice to do something more interesting — a greeting service that takes
a name and a language, validates both, and returns a localized greeting.

### Update the Request

Replace the `Request` record with one that has two fields:

```java
/// Request record.
record Request(String name, String language) {
    private static final java.util.Set<String> SUPPORTED_LANGUAGES =
        java.util.Set.of("en", "es", "fr", "de", "ja");

    public static Result<Request> request(String name, String language) {
        if (name == null || name.isBlank()) {
            return Result.failure(ValidationError.emptyName());
        }
        if (language == null || language.isBlank()) {
            return Result.failure(ValidationError.emptyLanguage());
        }
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            return Result.failure(ValidationError.unsupportedLanguage(language));
        }
        return Result.success(new Request(name.trim(), language));
    }
}
```

### Update the Response

```java
/// Response record.
record Response(String greeting, String language) {}
```

### Add Error Variants

Replace the `ValidationError` with more variants:

```java
/// Validation error.
sealed interface ValidationError extends Cause {
    record EmptyName() implements ValidationError {
        @Override
        public String message() {
            return "Name cannot be empty";
        }
    }

    record EmptyLanguage() implements ValidationError {
        @Override
        public String message() {
            return "Language cannot be empty";
        }
    }

    record UnsupportedLanguage(String language) implements ValidationError {
        @Override
        public String message() {
            return "Unsupported language: " + language;
        }
    }

    static ValidationError emptyName() {
        return new EmptyName();
    }

    static ValidationError emptyLanguage() {
        return new EmptyLanguage();
    }

    static ValidationError unsupportedLanguage(String language) {
        return new UnsupportedLanguage(language);
    }
}
```

### Update the Implementation

```java
static MyFirstSlice myFirstSlice() {
    record myFirstSlice() implements MyFirstSlice {
        @Override
        public Promise<Response> process(Request request) {
            var greeting = switch (request.language()) {
                case "en" -> "Hello, " + request.name() + "!";
                case "es" -> "Hola, " + request.name() + "!";
                case "fr" -> "Bonjour, " + request.name() + "!";
                case "de" -> "Hallo, " + request.name() + "!";
                case "ja" -> "Konnichiwa, " + request.name() + "!";
                default -> "Hello, " + request.name() + "!";
            };
            return Promise.success(new Response(greeting, request.language()));
        }
    }
    return new myFirstSlice();
}
```

### Update the Test

Replace the test to cover the new functionality:

```java
class MyFirstSliceTest {

    private final MyFirstSlice slice = MyFirstSlice.myFirstSlice();

    @Test
    void process_validEnglishRequest_returnsEnglishGreeting() {
        MyFirstSlice.Request.request("Alice", "en")
            .onFailure(Assertions::fail)
            .onSuccess(request -> slice.process(request)
                .await()
                .onFailure(Assertions::fail)
                .onSuccess(r -> {
                    assertThat(r.greeting()).isEqualTo("Hello, Alice!");
                    assertThat(r.language()).isEqualTo("en");
                }));
    }

    @Test
    void process_validSpanishRequest_returnsSpanishGreeting() {
        MyFirstSlice.Request.request("Carlos", "es")
            .onFailure(Assertions::fail)
            .onSuccess(request -> slice.process(request)
                .await()
                .onFailure(Assertions::fail)
                .onSuccess(r -> assertThat(r.greeting()).isEqualTo("Hola, Carlos!")));
    }

    @Test
    void request_emptyName_returnsFailure() {
        var result = MyFirstSlice.Request.request("", "en");
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void request_unsupportedLanguage_returnsFailure() {
        var result = MyFirstSlice.Request.request("Alice", "xx");
        assertThat(result.isFailure()).isTrue();
    }
}
```

### Rebuild

```bash
mvn clean verify
```

All tests should pass, and JBCT formatting/linting should be clean.

## Step 6: Add a Second Method

Slices can have multiple methods. Let's add a `status()` method that returns
information about supported languages.

Add a second request/response pair and method to `MyFirstSlice.java`:

```java
/// Status request (no parameters needed, but every method needs a request record).
record StatusRequest() {
    public static Result<StatusRequest> statusRequest() {
        return Result.success(new StatusRequest());
    }
}

/// Status response.
record StatusResponse(java.util.List<String> supportedLanguages, int totalLanguages) {}
```

Add the method declaration alongside `process()`:

```java
Promise<StatusResponse> status(StatusRequest request);
```

Update the factory method to implement both:

```java
static MyFirstSlice myFirstSlice() {
    record myFirstSlice() implements MyFirstSlice {
        private static final java.util.List<String> LANGUAGES =
            java.util.List.of("en", "es", "fr", "de", "ja");

        @Override
        public Promise<Response> process(Request request) {
            var greeting = switch (request.language()) {
                case "en" -> "Hello, " + request.name() + "!";
                case "es" -> "Hola, " + request.name() + "!";
                case "fr" -> "Bonjour, " + request.name() + "!";
                case "de" -> "Hallo, " + request.name() + "!";
                case "ja" -> "Konnichiwa, " + request.name() + "!";
                default -> "Hello, " + request.name() + "!";
            };
            return Promise.success(new Response(greeting, request.language()));
        }

        @Override
        public Promise<StatusResponse> status(StatusRequest request) {
            return Promise.success(new StatusResponse(LANGUAGES, LANGUAGES.size()));
        }
    }
    return new myFirstSlice();
}
```

Rebuild:

```bash
mvn clean verify
```

After the build, check the manifest:

```bash
cat target/classes/META-INF/slice/MyFirstSlice.manifest
```

The manifest now lists both `process` and `status` methods — the annotation processor
automatically discovered the new method.

## Step 7: Deploy to Forge

Forge is the Aether development server. It reads slices from your local Maven repository
and runs them.

### Install to Local Repository

```bash
./deploy-forge.sh
```

This script runs `mvn clean install -DskipTests`, which installs your slice JARs
to `~/.m2/repository`.

### Generate Blueprint

```bash
./generate-blueprint.sh
```

This generates `target/blueprint.toml` — the deployment descriptor:

```toml
id = "com.example:my-first-slice:1.0.0-SNAPSHOT"

[[slices]]
artifact = "com.example:my-first-slice:1.0.0-SNAPSHOT"
instances = 3
```

The `instances = 3` comes from `src/main/resources/slices/MyFirstSlice.toml`.

### Start Forge

If Forge is running with `repositories=["local"]`, it automatically picks up the slice.
Your slice is now live and can be called through the Forge API.

## Step 8: What's Next?

You've built, tested, modified, and deployed an Aether slice. Here's where to go next:

- **[Development Guide](development-guide.md)** — adding dependencies on other slices,
  creating multiple slices in one module, request/response design
- **[Slice Patterns](slice-patterns.md)** — advanced patterns for real-world slices
- **[Testing Slices](testing-slices.md)** — unit testing, integration testing, mocking dependencies
- **[Forge Guide](forge-guide.md)** — running Forge, dashboard, load testing
- **[Troubleshooting](troubleshooting.md)** — common errors and their solutions

## Quick Reference

### JBCT Patterns Used in This Tutorial

| Pattern | Where | Why |
|---------|-------|-----|
| Parse, don't validate | `Request.request()` returns `Result<Request>` | Invalid objects can never exist |
| Sealed error types | `ValidationError extends Cause` | Compiler-checked exhaustive error handling |
| Factory method naming | `MyFirstSlice.myFirstSlice()` | Convention: `TypeName.typeName()` |
| Inline implementation record | `record myFirstSlice() implements MyFirstSlice` | Encapsulation — only the factory can create instances |
| Single-parameter methods | `process(Request request)` | Uniform serialization, logging, metrics |
| Promise return types | `Promise<Response>` | Async-first, composable |
| Nested types | Request, Response, ValidationError inside interface | Cohesion — everything about this slice lives together |

### Common Commands

```bash
mvn clean verify              # Build + test + lint
mvn compile                   # Compile only (fast iteration)
./deploy-forge.sh             # Install to local Maven repo
./generate-blueprint.sh       # Generate deployment blueprint
jbct format .                 # Auto-format source code
jbct lint .                   # Check for lint violations
```
