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

All JBCT and Aether artifacts (including the annotation processor and Maven plugin) are
published to Maven Central. The generated POM references them automatically — no additional
repository configuration needed.

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
2. **Annotation processing** — generates the runtime glue that allows the slice to run
   in Aether: a factory class (dependency wiring and resource provisioning), a deployment
   manifest, and HTTP route bindings if `routes.toml` is present
3. **Test** — runs unit tests
4. **Package** — creates implementation JAR
5. **JBCT check** — verifies formatting and linting rules
6. **Slice packaging** — creates separate API and implementation JARs
7. **Blueprint generation** — creates `target/blueprint.toml`
8. **Slice verification** — checks that manifests are well-formed and that Aether runtime
   and slice dependencies use `provided` scope (they're supplied by the runtime, not bundled)

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
import org.pragmatica.lang.Verify;

/// MyFirstSlice slice interface.
@Slice
public interface MyFirstSlice {

    /// Request record.
    record Request(String value) {
        public static Result<Request> request(String value) {
            return Verify.ensure(value, Verify.Is::present, ValidationError.emptyValue())
                         .map(Request::new);
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
        return request -> Promise.success(new Response("Processed: " + request.value()));
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
The annotation processor reads this at compile time and generates the runtime glue:

- A **factory class** — wires dependencies, provisions resources, and creates slice instances
- A **deployment manifest** — metadata at `META-INF/slice/` for packaging and cluster deployment
- **HTTP route bindings** — if a `routes.toml` is present, generates route classes that map
  HTTP endpoints to slice methods

Everything that defines this slice — its contract, types, errors, and implementation — lives
inside this single interface. This is called the **"single-file slice"** pattern.

### Nested `Request` Record

```java
record Request(String value) {
    public static Result<Request> request(String value) {
        return Verify.ensure(value, Verify.Is::present, ValidationError.emptyValue())
                     .map(Request::new);
    }
}
```

The `Request` record is the input type for your slice. Notice two important things:

1. **The constructor is not used directly.** Instead, callers use the factory method
   `Request.request(value)` which returns `Result<Request>` — not `Request`.

2. **Validation uses the `Verify` API.** `Verify.ensure()` validates the value against a
   predicate (from `Verify.Is`) and produces a failure with a specific `Cause` if the check
   fails. Here, `Verify.Is::present` is a combined check — it rejects null, empty, and
   blank strings in one call. The final `map(Request::new)` constructs the record only when
   the check passes. This is called **"parse, don't validate"**: instead of creating an
   object and checking it later, you ensure it's valid *before* it exists.

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
- **`extends Cause`** integrates with `Result` and `Promise`. Any `Cause` has convenience
  methods: `cause.result()` creates a `Result.failure(cause)`, and `cause.promise()` creates
  a `Promise.failure(cause)`. You'll see these used instead of calling `Result.failure()`
  or `Promise.failure()` directly.
- **Each variant is a record** with a `message()` method that describes what went wrong.
- **Factory methods** like `emptyValue()` provide clean construction.

**Why not exceptions?** Exceptions are invisible in the type system. A method that throws
`IllegalArgumentException` looks identical to one that doesn't. With sealed `Cause` types,
the errors are explicit — you can see exactly what can go wrong by looking at the type.

### The `process()` Method

```java
Promise<Response> process(Request request);
```

This is the slice's entry point — the method that consumers call. Every slice method must
**return `Promise<T>`** — all operations are async-first. Even if your logic is synchronous
today, wrapping it in `Promise` means it composes with async operations (database queries,
other slices) without refactoring.

### Factory Method and Lambda Implementation

```java
static MyFirstSlice myFirstSlice() {
    return request -> Promise.success(new Response("Processed: " + request.value()));
}
```

This is where the implementation lives. Let's unpack the pattern:

- **`static MyFirstSlice myFirstSlice()`** — the factory method follows the JBCT naming
  convention: `TypeName.typeName()` (type name, lowercased first letter). The runtime
  discovers and calls this to create instances.

- **Lambda implementation** — since `MyFirstSlice` has a single abstract method (`process`),
  it's a functional interface. The lambda directly implements that method. This is the
  simplest and preferred form for single-method slices.

- **`Promise.success(response)`** — wraps the result in a successfully resolved `Promise`.

**When your slice needs dependencies**, they become parameters of the factory method:

```java
static MySlice mySlice(SomeDependency dep) {
    return request -> dep.doSomething(request.value());
}
```

**When your slice has multiple methods** (we'll see this in Step 6), lambdas can't work
because they only implement one method. In that case, you switch to an inline record:

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
                                                       .onSuccess(response -> assertThat(response.result()).isEqualTo("Processed: test")));
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

7. **`.onSuccess(response -> assertThat(...))`** — verify the response.

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
        var validName = Verify.ensure(name, Verify.Is::present, ValidationError.emptyName())
                              .map(String::trim);

        var validLanguage = Verify.ensure(language, Verify.Is::present, ValidationError.emptyLanguage())
                                  .flatMap(lang -> Verify.ensure(lang, SUPPORTED_LANGUAGES::contains,
                                                                 ValidationError.unsupportedLanguage(lang)));

        return Result.all(validName, validLanguage)
                     .map(Request::new);
    }
}
```

Notice how multi-field validation works: each field is validated independently into its
own `Result` using `Verify.Is::present` (null + blank in one check), then combined at the
end with `Result.all()` which collects all errors. The language field adds an extra `flatMap`
step to verify it's in the supported set. If any check fails, the overall result is a failure.

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
private static String greetingFor(String name, String language) {
    return switch (language) {
        case "en" -> "Hello, " + name + "!";
        case "es" -> "Hola, " + name + "!";
        case "fr" -> "Bonjour, " + name + "!";
        case "de" -> "Hallo, " + name + "!";
        case "ja" -> "Konnichiwa, " + name + "!";
        default -> "Hello, " + name + "!";
    };
}

static MyFirstSlice myFirstSlice() {
    return request -> Promise.success(new Response(greetingFor(request.name(), request.language()),
                                                   request.language()));
}
```

Notice how `greetingFor` is extracted as a pure Condition function (one `switch`, one pattern),
while the factory lambda remains a Leaf (wraps the result in a `Promise`). This follows the
JBCT rule: **one structural pattern per function**.

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
                .onSuccess(response -> {
                    assertThat(response.greeting()).isEqualTo("Hello, Alice!");
                    assertThat(response.language()).isEqualTo("en");
                }));
    }

    @Test
    void process_validSpanishRequest_returnsSpanishGreeting() {
        MyFirstSlice.Request.request("Carlos", "es")
            .onFailure(Assertions::fail)
            .onSuccess(request -> slice.process(request)
                .await()
                .onFailure(Assertions::fail)
                .onSuccess(response -> assertThat(response.greeting()).isEqualTo("Hola, Carlos!")));
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

Now update the factory method. Since the slice has **two methods**, we can no longer use
a lambda — lambdas implement exactly one method. Instead, we switch to an **inline record**:

```java
static MyFirstSlice myFirstSlice() {
    record myFirstSlice() implements MyFirstSlice {
        private static final java.util.List<String> LANGUAGES =
            java.util.List.of("en", "es", "fr", "de", "ja");

        @Override
        public Promise<Response> process(Request request) {
            return Promise.success(new Response(greetingFor(request.name(), request.language()),
                                                request.language()));
        }

        @Override
        public Promise<StatusResponse> status(StatusRequest request) {
            return Promise.success(new StatusResponse(LANGUAGES, LANGUAGES.size()));
        }
    }
    return new myFirstSlice();
}
```

The inline record pattern keeps the implementation private to the factory method — no one
can instantiate it directly. The lowercase name matches the factory method name, making it
clear this is the factory's implementation detail.

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
| Parse, don't validate | `Request.request()` uses `Verify.ensure()` | Invalid objects can never exist |
| Verify API | `Verify.ensure(value, Verify.Is::present, cause)` | Declarative validation with typed errors |
| Sealed error types | `ValidationError extends Cause` | Compiler-checked exhaustive error handling |
| `cause.result()` / `cause.promise()` | Error conversion | Idiomatic `Cause` → `Result`/`Promise` conversion |
| Factory method naming | `MyFirstSlice.myFirstSlice()` | Convention: `TypeName.typeName()` |
| Lambda implementation | `return request -> Promise.success(...)` | Simplest form for single-method slices |
| Inline record | `record mySlice() implements MySlice` | Required for multi-method slices or dependency capture |
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
