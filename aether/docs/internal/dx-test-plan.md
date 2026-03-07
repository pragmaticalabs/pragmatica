# Slice DX Test Plan

## Container Command

```bash
podman run -it --rm \
  --name aether-dx-test \
  -p 8070:8070 \
  -p 8888:8888 \
  -p 5150:5150 \
  -v "$HOME/.m2/repository:/root/.m2/repository" \
  eclipse-temurin:25-jdk-noble \
  bash
```

> **Port mappings:**
> - `8070` — App HTTP (Forge routes slice traffic here)
> - `8888` — Forge dashboard (browser access from host)
> - `5150` — Management API / CLI
>
> **Reaching host services** (e.g., PostgreSQL in Part 7): use `host.containers.internal`
> as the hostname. Podman on macOS/Windows resolves this automatically to the host machine.
> On Linux, add `--add-host=host.containers.internal:host-gateway` to the command above,
> or use `--network=host` instead of `-p` flags.
>
> **`-v .m2/repository`** — mounts local Maven cache so locally-built deps resolve (not published to Central yet). Remove this mount for a true clean-room test once 0.19.2 is published.
>
> **`--version` flag** — when testing unreleased versions, use `jbct init --version 0.19.2` to override the dependency versions in generated pom.xml. Without this flag, `jbct init` uses the latest published release from GitHub (0.19.1).

### Inside the container — prerequisites

```bash
apt-get update && apt-get install -y curl git

# Install Maven 3.9.12
MAVEN_VERSION=3.9.12
curl -fsSL https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz | tar xz -C /opt
export PATH="/opt/apache-maven-$MAVEN_VERSION/bin:$PATH"
export JAVA_HOME=/opt/java/openjdk

java -version    # should show 25
mvn --version    # should show 3.9.12
```

---

## Part 1: Install Tools

**Goal:** Verify install.sh downloads and configures all tools.

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/release-0.19.2/install.sh | sh
source ~/.bashrc    # or ~/.zshrc
```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1.1 | jbct installed | `jbct --version` | Version printed (0.19.2-candidate) |
| 1.2 | aether installed | `aether --help` | Help text with subcommands |
| 1.3 | aether-forge installed | `aether-forge --help` | Help text |
| 1.4 | Binaries in PATH | `which jbct aether aether-forge` | All three found |

---

## Part 2: Create First Slice (Default — HelloWorld)

**Goal:** `jbct init` creates a runnable project with zero manual file creation.

```bash
cd /tmp
jbct init my-slice -g com.example -a my-slice --no-ai
# For unreleased versions: jbct init my-slice -g com.example -a my-slice --no-ai --version 0.19.2
cd my-slice
```

### Verify: Project structure

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 2.1 | Slice source | `cat src/main/java/com/example/myslice/helloworld/HelloWorld.java` | `@Slice`, `greet()`, `GreetRequest`, `GreetResponse`, `GreetError` |
| 2.2 | Test source | `cat src/test/java/com/example/myslice/helloworld/HelloWorldTest.java` | Two tests: `greet_validName_returnsGreeting`, `greet_emptyName_returnsError` |
| 2.3 | Routes | `cat src/main/resources/com/example/myslice/helloworld/routes.toml` | `greet = "GET /hello/{name}"` |
| 2.4 | Forge config | `cat forge.toml` | `nodes = 3`, `app_http_port = 8070` |
| 2.5 | Aether config | `cat aether.toml` | DB section commented out |
| 2.6 | Run script | `ls -la run-forge.sh` | Executable |
| 2.7 | Postgres scripts | `ls -la start-postgres.sh stop-postgres.sh` | Both executable |
| 2.8 | Schema | `cat schema/init.sql` | Placeholder comment |
| 2.9 | README | `head -20 README.md` | Quick start with `./run-forge.sh` |
| 2.10 | No CLAUDE.md | `ls CLAUDE.md 2>&1` | "No such file" |
| 2.11 | Deploy scripts | `ls deploy-*.sh generate-blueprint.sh` | All four present |
| 2.12a | jbct.toml basePackage | `grep basePackage jbct.toml` | `basePackage = "com.example.myslice"` |

### Verify: Build and test

```bash
mvn clean install
```

| # | Check | Expected |
|---|-------|----------|
| 2.12 | Build succeeds | `BUILD SUCCESS` |
| 2.13 | Tests pass | 2 tests run, 0 failures |
| 2.14 | JBCT format OK | No format-check errors |
| 2.15 | Blueprint generated | `cat target/blueprint.toml` shows `[[slices]]` entry |

---

## Part 3: Run Forge and Test HTTP Endpoint

**Goal:** The generated project runs end-to-end with Forge.

```bash
./run-forge.sh
```

(In a separate terminal / or background with `&`)

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 3.1 | Forge starts | Watch console output | "Starting Aether Forge..." |
| 3.2 | Greeting API | `curl -s http://localhost:8070/api/hello/World` | `{"greeting":"Hello, World!"}` |
| 3.3 | Different name | `curl -s http://localhost:8070/api/hello/Alice` | `{"greeting":"Hello, Alice!"}` |
| 3.4 | Empty name | `curl -s http://localhost:8070/api/hello/%20` | HTTP 422 with error message |
| 3.5 | Dashboard | `curl -s -o /dev/null -w "%{http_code}" http://localhost:8888` | `200` |
| 3.6 | Management API | `curl -s http://localhost:5150/api/status` | Cluster status JSON |
| 3.7 | Nodes healthy | `curl -s http://localhost:5150/api/nodes` | 3 nodes listed |
| 3.8 | Slices deployed | `curl -s http://localhost:5150/api/slices` | HelloWorld slice listed |

Stop Forge: `Ctrl+C`

---

## Part 4: Custom Slice Name

**Goal:** `--name` parameter works correctly.

```bash
cd /tmp
jbct init inventory-app -g com.acme -a inventory-app --name InventoryService --no-ai
```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 4.1 | Custom name | `ls inventory-app/src/main/java/com/acme/inventoryapp/inventoryservice/` | `InventoryService.java` |
| 4.2 | Test file | `ls inventory-app/src/test/java/com/acme/inventoryapp/inventoryservice/` | `InventoryServiceTest.java` |
| 4.3 | Factory method | `grep "static InventoryService" inventory-app/src/main/java/com/acme/inventoryapp/inventoryservice/InventoryService.java` | `inventoryService()` |
| 4.4 | Build | `cd inventory-app && mvn clean install` | `BUILD SUCCESS` |

---

## Part 5: Plain JBCT Project (--no-slice)

**Goal:** `--no-slice` creates a minimal JBCT project without Aether.

```bash
cd /tmp
jbct init my-lib -g com.example -a my-lib --no-slice --no-ai
```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 5.1 | No slice files | `ls my-lib/forge.toml 2>&1` | "No such file" |
| 5.2 | No run scripts | `ls my-lib/run-forge.sh 2>&1` | "No such file" |
| 5.3 | Has pom.xml | `head -5 my-lib/pom.xml` | Standard Maven POM |
| 5.4 | Has jbct.toml | `cat my-lib/jbct.toml` | Format config |
| 5.5 | No CLAUDE.md | `ls my-lib/CLAUDE.md 2>&1` | "No such file" |
| 5.6 | Next steps | (check console output from init) | Shows "Edit pom.xml" not "run-forge.sh" |

---

## Part 6: Add a Second Slice

**Goal:** Add an Analytics slice to the existing HelloWorld project using `jbct add-slice`.

> **Convention: One slice per package.** The annotation processor enforces that each Java package
> contains at most one `@Slice` interface. When adding a second slice, it must go in its own
> sub-package. The `jbct add-slice` command handles this automatically.

```bash
cd /tmp/my-slice
jbct add-slice Analytics
```

This creates the Analytics slice in `com.example.myslice.analytics` (a sub-package of the project's base package), generating all required files:

- `src/main/java/com/example/myslice/analytics/Analytics.java` — slice interface
- `src/test/java/com/example/myslice/analytics/AnalyticsTest.java` — unit test
- `src/main/resources/com/example/myslice/analytics/routes.toml` — HTTP routes
- `src/main/resources/slices/Analytics.toml` — runtime config
- `src/main/resources/META-INF/dependencies/com.example.myslice.analytics.Analytics` — dependency manifest

### 6a. Customize the Analytics slice

Replace the generated greeting logic in `src/main/java/com/example/myslice/analytics/Analytics.java` with analytics-specific logic:

```java
package com.example.myslice.analytics;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

/// Analytics slice.
@Slice
public interface Analytics {
    record ValidCountRequest(String name) {
        public static Result<ValidCountRequest> validCountRequest(String name) {
            return Verify.ensure(name,
                                 Verify.Is::present,
                                 CountError.invalidName())
                         .map(ValidCountRequest::new);
        }
    }

    record CountResponse(String name, int count) {}

    sealed interface CountError extends Cause {
        record InvalidName() implements CountError {
            @Override
            public String message() {
                return "Name cannot be empty";
            }
        }

        static CountError invalidName() {
            return new CountError.InvalidName();
        }
    }

    Promise<CountResponse> getCount(String name);

    static Analytics analytics() {
        return name -> ValidCountRequest.validCountRequest(name)
                                        .map(request -> new CountResponse(request.name(), 42))
                                        .async();
    }
}
```

### 6b. Update AnalyticsTest

The generated test still references the default `greet` method. Replace `src/test/java/com/example/myslice/analytics/AnalyticsTest.java` to match the new `getCount` method:

```java
package com.example.myslice.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class AnalyticsTest {

    private final Analytics slice = Analytics.analytics();

    @Test
    void getCount_validName_returnsCount() {
        slice.getCount("World")
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(r -> assertThat(r.count()).isEqualTo(42));
    }

    @Test
    void getCount_emptyName_returnsError() {
        slice.getCount("")
             .await()
             .onSuccess(r -> fail("Expected failure for empty name"))
             .onFailure(cause -> assertThat(cause.message()).isEqualTo("Name cannot be empty"));
    }
}
```

### 6c. Update Analytics routes

Edit `src/main/resources/com/example/myslice/analytics/routes.toml`:

```toml
prefix = "/api/analytics"

[routes]
getCount = "GET /count/{name}"

[errors]
default = 500
HTTP_422 = ["*Invalid*"]
```

### 6d. Format and build

```bash
mvn jbct:format     # Format edited files to JBCT style
mvn clean install
```

### Verify

| # | Check | Expected |
|---|-------|----------|
| 6.1 | Build succeeds | Both slices compile and package |
| 6.2 | Blueprint | `cat target/blueprint.toml` shows TWO `[[slices]]` entries |
| 6.3 | Run Forge | `./run-forge.sh` starts, both slices deploy |
| 6.4 | HelloWorld API | `curl -s http://localhost:8070/api/hello/World` works |
| 6.5 | Analytics API | `curl -s http://localhost:8070/api/analytics/count/World` returns count |
| 6.6 | Both in slices list | `curl -s http://localhost:5150/api/slices` shows both |

---

## Part 7: Add Database Resource

**Goal:** Wire PostgreSQL into a slice using `@Sql SqlConnector`.

### 7a. Update schema and start PostgreSQL

Update `schema/init.sql`:

```sql
CREATE TABLE IF NOT EXISTS greetings (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    greeted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

On the **host machine** (outside the container, where podman is available):

```bash
cd /tmp/my-slice
./start-postgres.sh
```

The script auto-detects podman/docker, starts PostgreSQL, and applies `schema/init.sql` automatically.

### 7b. Uncomment database config

Edit `aether.toml`:

```toml
[database]
async_url = "postgresql://host.containers.internal:5432/forge"

[database.pool_config]
min_connections = 5
max_connections = 20
```

> **Note:** Use `host.containers.internal` to reach PostgreSQL running on the host machine.
> If using `--network=host` (Linux), use `localhost` instead.

### 7c. Update HelloWorld slice to use DB

Update `src/main/java/com/example/myslice/helloworld/HelloWorld.java` to inject `SqlConnector` and record greetings.

> **Pattern: Parse, don't validate.** The `greet(String name)` method takes raw input from the HTTP
> route — all entry points exposed to the external world receive potentially unsafe data. The
> `ValidGreetRequest.validGreetRequest(name)` call transforms this unsafe input into a validated
> internal representation (`ValidGreetRequest`) that is safe to use across all business logic.
> Validation failures are captured as typed errors (`GreetError`), never as exceptions.

```java
package com.example.myslice.helloworld;

import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

/// HelloWorld slice.
@Slice
public interface HelloWorld {
    record ValidGreetRequest(String name) {
        public static Result<ValidGreetRequest> validGreetRequest(String name) {
            return Verify.ensure(name,
                                 Verify.Is::present,
                                 GreetError.invalidName())
                         .map(ValidGreetRequest::new);
        }
    }

    record GreetResponse(String greeting) {}

    sealed interface GreetError extends Cause {
        record InvalidName() implements GreetError {
            @Override
            public String message() {
                return "Name cannot be empty";
            }
        }

        static GreetError invalidName() {
            return new InvalidName();
        }
    }

    Promise<GreetResponse> greet(String name);

    static HelloWorld helloWorld(@Sql SqlConnector db) {
        return name -> ValidGreetRequest.validGreetRequest(name)
                                        .async()
                                        .flatMap(request -> db.update("INSERT INTO greetings (name) VALUES (?)",
                                                                      request.name())
                                                              .map(_ -> new GreetResponse("Hello, " + request.name() + "!")));
    }
}
```

### 7d. Update HelloWorldTest

The factory now requires a `SqlConnector`, so the unit test can no longer call `helloWorld()` directly.
Update the test to verify input validation only — the DB path is tested via Forge integration:

```java
package com.example.myslice.helloworld;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class HelloWorldTest {

    @Test
    void validGreetRequest_validName_succeeds() {
        HelloWorld.ValidGreetRequest.validGreetRequest("World")
                  .onFailure(cause -> fail(cause.message()))
                  .onSuccess(r -> assertThat(r.name()).isEqualTo("World"));
    }

    @Test
    void validGreetRequest_emptyName_returnsError() {
        HelloWorld.ValidGreetRequest.validGreetRequest("")
                  .onSuccess(r -> fail("Expected failure for empty name"))
                  .onFailure(cause -> assertThat(cause.message()).isEqualTo("Name cannot be empty"));
    }
}
```

### Verify

```bash
mvn jbct:format
mvn clean install
./run-forge.sh
```

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 7.1 | Build succeeds | `mvn clean install` | `BUILD SUCCESS` |
| 7.2 | Greeting with DB | `curl -s http://localhost:8070/api/hello/World` | `{"greeting":"Hello, World!"}` |
| 7.3 | DB has record | `podman exec my-slice-postgres psql -U postgres -d forge -c "SELECT * FROM greetings"` | Row with "World" |
| 7.4 | Multiple calls | `curl -s http://localhost:8070/api/hello/Alice && curl -s http://localhost:8070/api/hello/Bob` | Both succeed |
| 7.5 | DB count | `podman exec my-slice-postgres psql -U postgres -d forge -c "SELECT COUNT(*) FROM greetings"` | 3 rows |

---

## Part 8: Add Pub/Sub Event

**Goal:** Publisher in HelloWorld, subscriber in Analytics — events flow between slices.

### 8a. Generate event annotations

Use `jbct add-event` to generate both publisher and subscription annotations:

```bash
cd /tmp/my-slice
jbct add-event greet-event
```

This creates:
- `src/main/java/com/example/myslice/shared/resource/GreetEventPublisher.java` — publisher annotation
- `src/main/java/com/example/myslice/shared/resource/GreetEventSubscription.java` — subscription annotation

It also appends a `[messaging.greet-event]` section to `aether.toml` so the runtime can find the messaging config.

### Verify add-event

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 8.0a | Publisher created | `cat src/main/java/com/example/myslice/shared/resource/GreetEventPublisher.java` | `@ResourceQualifier(type = Publisher.class, config = "messaging.greet-event")` |
| 8.0b | Subscription created | `cat src/main/java/com/example/myslice/shared/resource/GreetEventSubscription.java` | `@ResourceQualifier(type = Subscriber.class, config = "messaging.greet-event")` |
| 8.0c | Config added | `grep -A1 'messaging.greet-event' aether.toml` | `[messaging.greet-event]` with `topic_name = "greet-event"` |

### 8b. Define the event record

Add to `src/main/java/com/example/myslice/helloworld/HelloWorld.java` (inside the interface):

```java
record GreetEvent(String name) {}
```

### 8c. Update HelloWorld to publish events

Update the factory method to accept a publisher:

```java
import com.example.myslice.shared.resource.GreetEventPublisher;
import org.pragmatica.aether.slice.Publisher;

static HelloWorld helloWorld(@Sql SqlConnector db,
                             @GreetEventPublisher Publisher<GreetEvent> greetPublisher) {
    return name -> ValidGreetRequest.validGreetRequest(name)
                                    .async()
                                    .flatMap(request -> db.update("INSERT INTO greetings (name) VALUES (?)",
                                                                  request.name())
                                                          .flatMap(_ -> greetPublisher.publish(new GreetEvent(request.name())))
                                                          .map(_ -> new GreetResponse("Hello, " + request.name() + "!")));
}
```

### 8d. Update Analytics to subscribe to events

Update `src/main/java/com/example/myslice/analytics/Analytics.java` to add a subscriber method:

```java
import com.example.myslice.helloworld.HelloWorld;
import com.example.myslice.shared.resource.GreetEventSubscription;
import org.pragmatica.lang.Unit;

// Add inside the interface:
@GreetEventSubscription
Promise<Unit> onGreetEvent(HelloWorld.GreetEvent event);

// Update the factory implementation to handle the event:
static Analytics analytics() {
    record analyticsSlice() implements Analytics {
        @Override
        public Promise<CountResponse> getCount(String name) {
            return ValidCountRequest.validCountRequest(name)
                                    .map(request -> new CountResponse(request.name(), 42))
                                    .async();
        }

        @Override
        public Promise<Unit> onGreetEvent(HelloWorld.GreetEvent event) {
            System.out.println("[Analytics] Received greet event for: " + event.name());
            return Promise.success(Unit.unit());
        }
    }
    return new analyticsSlice();
}
```

### Verify

```bash
mvn clean install
./run-forge.sh
```

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 8.1 | Build succeeds | `mvn clean install` | `BUILD SUCCESS` |
| 8.2 | Greet API works | `curl -s http://localhost:8070/api/hello/World` | Greeting returned |
| 8.3 | Event received | Check Forge console | `[Analytics] Received greet event for: World` |
| 8.4 | Multiple events | Send 5 greetings, check console | 5 event log lines |

---

## Part 9: JBCT Format and Lint

**Goal:** Verify JBCT tooling works on the project.

```bash
cd /tmp/my-slice
```

### 9a. Introduce a formatting issue

Add extra blank lines or wrong indentation to HelloWorld.java.

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 9.1 | Format check fails | `mvn jbct:format-check` | Reports unformatted file(s) |
| 9.2 | Auto-format | `mvn jbct:format` | Files formatted |
| 9.3 | Format check passes | `mvn jbct:format-check` | No errors |
| 9.4 | Lint | `mvn jbct:lint` | No warnings |
| 9.5 | Full check | `mvn jbct:check` | Both format and lint pass |

---

## Part 10: CLI Exploration

**Goal:** Verify the `aether` CLI works against a running Forge.

Start Forge first: `./run-forge.sh &`

```bash
# Connect to management port
aether -c localhost:5150 status
aether -c localhost:5150 nodes
aether -c localhost:5150 health
aether -c localhost:5150 slices
aether -c localhost:5150 events
```

> **REPL mode:** Running `aether -c localhost:5150` without a subcommand enters interactive
> REPL mode. You can then type commands without the `aether -c localhost:5150` prefix:
> ```bash
> # Enter REPL mode
> aether -c localhost:5150
> # Then type commands directly:
> > status
> > nodes
> > slices
> ```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 10.1 | Cluster status | `aether -c localhost:5150 status` | Shows leader, node count |
| 10.2 | Node list | `aether -c localhost:5150 nodes` | 3 nodes with health info |
| 10.3 | Health | `aether -c localhost:5150 health` | Health status per node |
| 10.4 | Slices | `aether -c localhost:5150 slices` | Both HelloWorld and Analytics |
| 10.5 | Events | `aether -c localhost:5150 events` | Recent cluster events |
| 10.6 | REPL mode | `aether -c localhost:5150` (no subcommand) | Enters interactive REPL |

---

## Part 11: Management API Deep Dive

**Goal:** Exercise the management API endpoints via CLI (and curl for HTTP-only probes).

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 11.1 | Cluster status | `aether -c localhost:5150 status` | JSON with leader, nodes, slices |
| 11.2 | Health probes | `curl -s localhost:5150/health/live` | `{"status":"UP"}` |
| 11.3 | Readiness | `curl -s localhost:5150/health/ready` | `{"status":"UP"}` |
| 11.4 | Node list | `aether -c localhost:5150 nodes` | Array of 3 nodes |
| 11.5 | Slice list | `aether -c localhost:5150 slices` | Both slices with instances |
| 11.6 | Routes | `curl -s localhost:5150/api/routes \| jq .` | `/api/hello/{name}`, `/api/analytics/count/{name}` |
| 11.7 | Blueprint | `aether -c localhost:5150 blueprint list` | Active blueprint |
| 11.8 | Metrics | `aether -c localhost:5150 metrics` | CPU, memory, latency |
| 11.9 | Invocation metrics | `aether -c localhost:5150 invocation-metrics` | Per-method stats |
| 11.10 | Events | `aether -c localhost:5150 events` | Recent events array |
| 11.11 | Config | `aether -c localhost:5150 config list` | Runtime configuration |
| 11.12 | Prometheus | `curl -s localhost:5150/api/prometheus` | Prometheus text format |
| 11.13 | Traces | `aether -c localhost:5150 traces list` | Recent invocation traces |

---

## Part 12: Observability

**Goal:** Verify tracing, depth logging, and metrics.

### 12a. Generate traffic

```bash
for i in $(seq 1 20); do curl -s http://localhost:8070/api/hello/User$i > /dev/null; done
```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 12.1 | Invocation metrics | `aether -c localhost:5150 invocation-metrics list` | `greet` method with 20 calls |
| 12.2 | Trace list | `aether -c localhost:5150 traces list` | Recent trace entries |
| 12.3 | Trace detail | `aether -c localhost:5150 traces get {requestId}` | Full call tree |
| 12.4 | Trace stats | `aether -c localhost:5150 traces stats` | Aggregated statistics |
| 12.5 | Slow invocations | `aether -c localhost:5150 invocation-metrics slow` | Slowest calls |
| 12.6 | Node metrics | `curl -s localhost:5150/api/node-metrics` | Per-node CPU/memory |
| 12.7 | Comprehensive | `curl -s localhost:5150/api/metrics/comprehensive` | Full metrics snapshot |

### 12b. Depth override

```bash
# Set depth threshold for HelloWorld.greet to 3
aether -c localhost:5150 observability depth-set HelloWorld.greet 3

# Verify
aether -c localhost:5150 observability depth
```

---

## Part 13: Dashboard Exploration

**Goal:** Verify the Forge dashboard is functional.

| # | Check | URL | Expected |
|---|-------|-----|----------|
| 13.1 | Dashboard loads | `http://localhost:8888` | Visual dashboard |
| 13.2 | Cluster visible | Dashboard UI | 3 nodes shown |
| 13.3 | Slices visible | Dashboard UI | Both slices listed |
| 13.4 | WebSocket status | `curl -s localhost:5150/ws/status` | WebSocket upgrade response |

---

## Part 14: Blueprint Management

**Goal:** Verify blueprint generation, validation, and management.

### 14a. Blueprint generation

```bash
mvn jbct:generate-blueprint -DskipTests
cat target/blueprint.toml
```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 14.1 | Blueprint generated | `cat target/blueprint.toml` | File exists with `[[slices]]` entries |
| 14.2 | Both slices present | `cat target/blueprint.toml` | HelloWorld and Analytics in blueprint |
| 14.3 | Validate blueprint | `aether -c localhost:5150 blueprint validate target/blueprint.toml` | Returns valid |
| 14.4 | List blueprints | `aether -c localhost:5150 blueprint list` | Shows active blueprint |
| 14.5 | Blueprint status | `aether -c localhost:5150 blueprint status {id}` | Shows deployed |
| 14.6 | Apply blueprint | `aether -c localhost:5150 blueprint apply target/blueprint.toml` | Blueprint applied |
| 14.7 | Get blueprint | `aether -c localhost:5150 blueprint get {id}` | Blueprint details |

---

## Part 15: Scaling

**Goal:** Scale slice instances up and down.

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 15.1 | Current scale | `aether -c localhost:5150 slices` | 3 instances each |
| 15.2 | Scale up | `aether -c localhost:5150 scale HelloWorld -n 5` | Success |
| 15.3 | Verify scale | `aether -c localhost:5150 slices` | HelloWorld at 5 instances |
| 15.4 | Scale down | `aether -c localhost:5150 scale HelloWorld -n 2` | Success |
| 15.5 | Scale back | `aether -c localhost:5150 scale HelloWorld -n 3` | Scaled back to 3 |

---

## Part 16: Node Lifecycle

**Goal:** Test drain, activation, and graceful shutdown.

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 16.1 | Node lifecycle | `aether -c localhost:5150 node lifecycle` | All nodes ACTIVE |
| 16.2 | Drain a node | `aether -c localhost:5150 node drain {nodeId}` | Node transitions to DRAINING |
| 16.3 | API still works | `curl -s http://localhost:8070/api/hello/World` | Still responds (traffic re-routed) |
| 16.4 | Activate node | `aether -c localhost:5150 node activate {nodeId}` | Node back to ACTIVE |

---

## Part 17: Error Handling Patterns

**Goal:** Verify error responses follow the routes.toml error mapping.

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 17.1 | Valid request | `curl -s -w "\n%{http_code}" http://localhost:8070/api/hello/World` | 200 |
| 17.2 | Empty name (422) | `curl -s -w "\n%{http_code}" http://localhost:8070/api/hello/%20` | 422 |
| 17.3 | Error body | `curl -s http://localhost:8070/api/hello/%20` | JSON with error message |
| 17.4 | Unknown route (404) | `curl -s -w "\n%{http_code}" http://localhost:8070/api/nonexistent` | 404 |

---

## Part 18: Stop and Cleanup

```bash
# Stop Forge
# Ctrl+C or kill the background process

# Stop PostgreSQL
./stop-postgres.sh

# Purge PostgreSQL (remove data volume)
./stop-postgres.sh --purge
```

### Verify

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 18.1 | Postgres stopped | `podman ps` | No my-slice-postgres container |
| 18.2 | Volume removed (purge) | `podman volume ls` | No my-slice-pgdata volume |

---

## Summary Checklist

| Part | Area | Tests |
|------|------|-------|
| 1 | Install tools | 4 |
| 2 | Create first slice | 15 |
| 3 | Run Forge + HTTP | 8 |
| 4 | Custom slice name | 4 |
| 5 | Plain JBCT project | 6 |
| 6 | Add second slice | 6 |
| 7 | Add database | 5 |
| 8 | Add pub/sub topic | 4 |
| 9 | JBCT format/lint | 5 |
| 10 | CLI exploration | 6 |
| 11 | Management API | 13 |
| 12 | Observability | 7+ |
| 13 | Dashboard | 4 |
| 14 | Blueprint management | 7+ |
| 15 | Scaling | 5 |
| 16 | Node lifecycle | 4 |
| 17 | Error handling | 4 |
| 18 | Cleanup | 2 |
| **Total** | | **~100** |
