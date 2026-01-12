# Slice Reference

Complete reference for annotations, generated artifacts, manifest formats, and CLI commands.

## @Slice Annotation

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Slice {}
```

### Requirements

The annotated interface must:

1. **Be a public interface**
2. **Have a static factory method** that returns the interface type
3. **All non-static, non-default methods** must:
   - Return `Promise<T>` where `T` is the response type
   - Have exactly one parameter (the request type)

### Valid Example

```java
@Slice
public interface OrderService {
    // API methods - will be included in generated API interface
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
    Promise<OrderStatus> getStatus(StatusRequest request);

    // Factory method - not included in API, used for wiring
    static OrderService orderService(InventoryService inventory) {
        return new OrderServiceImpl(inventory);
    }

    // Default methods - not included in API
    default Promise<OrderResult> placeOrderWithDefaults(String customerId) {
        return placeOrder(new PlaceOrderRequest(customerId, List.of(), null));
    }
}
```

### Invalid Examples

```java
// Missing factory method
@Slice
public interface BadService {
    Promise<String> doSomething(Request r);
}

// Factory returns wrong type
@Slice
public interface BadService {
    Promise<String> doSomething(Request r);
    static OtherService factory() { return new OtherServiceImpl(); }
}

// Method doesn't return Promise
@Slice
public interface BadService {
    String doSomething(Request r);  // Must return Promise<T>
    static BadService factory() { return new BadServiceImpl(); }
}

// Method has multiple parameters
@Slice
public interface BadService {
    Promise<String> doSomething(String a, int b);  // Must have exactly one param
    static BadService factory() { return new BadServiceImpl(); }
}
```

## Generated Artifacts

### API Interface

**Location:** `{package}.api.{SliceName}`

The generated API interface contains:
- All non-static, non-default methods from the `@Slice` interface
- No factory method
- No default methods

**Input:**
```java
package org.example.order;

@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
    static OrderService orderService(Inventory inv) { ... }
    default void helper() { ... }
}
```

**Output:**
```java
package org.example.order.api;

public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
}
```

### Factory Class

**Location:** `{package}.{SliceName}Factory`

Generated factory with two methods:

```java
public final class OrderServiceFactory {
    private OrderServiceFactory() {}

    /**
     * Create typed slice instance.
     */
    public static Promise<OrderService> create(
            Aspect<OrderService> aspect,
            SliceInvokerFacade invoker) { ... }

    /**
     * Create Slice wrapper for Aether runtime.
     */
    public static Promise<Slice> createSlice(
            Aspect<OrderService> aspect,
            SliceInvokerFacade invoker) { ... }
}
```

**Parameters:**
- `Aspect<T>`: Decorator for cross-cutting concerns (logging, metrics)
- `SliceInvokerFacade`: Runtime-provided invoker for external dependencies

### Slice Manifest

**Location:** `META-INF/slice/{SliceName}.manifest`

Properties file with slice metadata:

```properties
# Identity
slice.name=OrderService
slice.artifactSuffix=order-service
slice.package=org.example.order

# Classes for packaging
api.classes=org.example.order.api.OrderService
impl.classes=org.example.order.OrderService,\
             org.example.order.OrderServiceFactory,\
             org.example.order.OrderServiceFactory$orderServiceSlice

request.classes=org.example.order.PlaceOrderRequest
response.classes=org.example.order.OrderResult

# Artifact coordinates
base.artifact=org.example:commerce
api.artifactId=commerce-order-service-api
impl.artifactId=commerce-order-service

# Dependencies
dependencies.count=2
dependency.0.interface=org.example.inventory.InventoryService
dependency.0.artifact=org.example:inventory-service
dependency.0.version=1.0.0
dependency.0.external=true
dependency.1.interface=org.example.order.validation.Validator
dependency.1.artifact=
dependency.1.version=
dependency.1.external=false

# Slice configuration file path
config.file=slices/OrderService.toml

# Metadata
generated.timestamp=2024-01-15T10:30:00Z
processor.version=0.4.8
```

### API Manifest

**Location:** `META-INF/slice-api.properties`

Maps artifact to API interface:

```properties
api.artifact=org.example:commerce:api
slice.artifact=org.example:commerce
api.interface=org.example.order.api.OrderService
impl.interface=org.example.order.OrderService
```

## Manifest Properties Reference

### Identity Properties

| Property | Description | Example |
|----------|-------------|---------|
| `slice.name` | Simple name of slice interface | `OrderService` |
| `slice.artifactSuffix` | Suffix for generated artifactId | `order-service` |
| `slice.package` | Package containing the slice | `org.example.order` |

### Class Lists

| Property | Description |
|----------|-------------|
| `api.classes` | Classes for API JAR (comma-separated) |
| `impl.classes` | Classes for Impl JAR (comma-separated) |
| `request.classes` | Request types (included in API JAR for nested records) |
| `response.classes` | Response types (included in API JAR for nested records) |

### Artifact Properties

| Property | Description | Example |
|----------|-------------|---------|
| `base.artifact` | Base Maven coordinates | `org.example:commerce` |
| `api.artifactId` | API artifact ID | `commerce-order-service-api` |
| `impl.artifactId` | Implementation artifact ID | `commerce-order-service` |

### Dependency Properties

| Property | Description |
|----------|-------------|
| `dependencies.count` | Number of dependencies |
| `dependency.N.interface` | Fully qualified interface name |
| `dependency.N.artifact` | Maven coordinates (groupId:artifactId) |
| `dependency.N.version` | Version |
| `dependency.N.external` | `true` if external dependency |

## Blueprint Format

`blueprint.toml` format for Aether deployment:

```toml
# Generated by jbct:generate-blueprint
# Regenerate with: mvn jbct:generate-blueprint

id = "org.example:commerce:1.0.0"

[[slices]]
artifact = "org.example:inventory-service:1.0.0"
instances = 1
# transitive dependency

[[slices]]
artifact = "org.example:payment-service:1.0.0"
instances = 1

[[slices]]
artifact = "org.example:commerce-order-service:1.0.0"
instances = 1
```

### Blueprint Properties

| Property | Description |
|----------|-------------|
| `id` | Blueprint identifier (Maven coordinates of source project) |
| `[[slices]]` | Array of slice entries |
| `artifact` | Full Maven coordinates (groupId:artifactId:version) |
| `instances` | Number of instances to deploy (default: 1) |
| `timeout_ms` | Request timeout in milliseconds (optional) |
| `memory_mb` | Memory allocation per instance (optional) |
| `load_balancing` | Load balancing strategy: `round_robin`, `least_connections` (optional) |
| `affinity_key` | Request field for sticky routing (optional) |

Slices are listed in **topological order** (dependencies before dependents).

## Slice Configuration

Per-slice configuration files define runtime properties for blueprint generation.

### Location

`src/main/resources/slices/{SliceName}.toml`

For a slice named `OrderService`, create `src/main/resources/slices/OrderService.toml`.

### Format

```toml
# Slice configuration for OrderService

[blueprint]
# Required: number of slice instances
instances = 3

# Optional: request timeout in milliseconds
timeout_ms = 30000

# Optional: memory allocation per instance
memory_mb = 512

# Optional: load balancing strategy
load_balancing = "round_robin"

# Optional: request field for sticky routing
affinity_key = "customerId"
```

### Configuration Properties

| Section | Property | Type | Default | Description |
|---------|----------|------|---------|-------------|
| `[blueprint]` | `instances` | int | `1` | Number of slice instances |
| `[blueprint]` | `timeout_ms` | int | - | Request timeout |
| `[blueprint]` | `memory_mb` | int | - | Memory per instance |
| `[blueprint]` | `load_balancing` | string | - | Load balancing strategy |
| `[blueprint]` | `affinity_key` | string | - | Field for sticky routing |

### Default Behavior

If no config file exists for a slice, defaults are used:
- `instances = 1`
- No timeout (uses runtime default)
- No memory limit
- Default load balancing
- No affinity

The plugin logs an info message when using defaults.

## Maven Plugin Goals

### jbct:collect-slice-deps

**Phase:** `generate-sources`

Collects provided dependencies to `slice-deps.properties`:

```bash
mvn jbct:collect-slice-deps
```

Creates `target/classes/slice-deps.properties`:
```properties
org.example\:inventory-service\:api=1.0.0
org.example\:pricing-engine\:api=2.1.0
```

### jbct:package-slices

**Phase:** `package`

Creates separate JARs from manifests:

```bash
mvn jbct:package-slices
```

For each manifest, creates:

**API JAR** (`{artifactId}-api-{version}.jar`):
- API interface
- Nested request/response types

**Impl JAR** (`{artifactId}-{version}.jar`):
- Implementation classes
- Factory class with proxy records
- Bundled external dependencies (fat JAR)
- `META-INF/dependencies/{FactoryClass}` - runtime dependency file
- MANIFEST.MF entries: `Slice-Artifact`, `Slice-Class`

### jbct:install-slices

**Phase:** `install`

Installs slice JARs to local Maven repository:

```bash
mvn jbct:install-slices
```

### jbct:deploy-slices

**Phase:** `deploy`

Deploys slice JARs to remote repository:

```bash
mvn jbct:deploy-slices
```

### jbct:generate-blueprint

**Phase:** `package`

Generates `blueprint.toml` from manifests:

```bash
mvn jbct:generate-blueprint
```

**Parameters:**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| blueprintFile | `jbct.blueprint.output` | `target/blueprint.toml` | Output file path |
| blueprintId | `jbct.blueprint.id` | Project GAV | Blueprint identifier |
| skip | `jbct.skip` | `false` | Skip execution |

### jbct:verify-slice

**Phase:** `verify`

Validates slice configuration:

```bash
mvn jbct:verify-slice
```

Checks:
- `@Slice` interface has factory method
- Factory method returns interface type
- All methods return `Promise<T>`
- All methods have one parameter

## CLI Commands

### jbct init --slice

Create new slice project:

```bash
jbct init --slice my-service
jbct init --slice my-service --package org.example.myservice
jbct init --slice my-service --group-id com.company
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--package` | Java package | Derived from artifactId |
| `--group-id` | Maven groupId | `org.example` |
| `--version` | Initial version | `1.0.0-SNAPSHOT` |

**Generated files:**
- `pom.xml` with all jbct plugin goals configured
- `jbct.toml` configuration
- `@Slice` interface with sample implementation
- Request/response records
- Slice config (`src/main/resources/slices/{SliceName}.toml`)
- Unit test
- Deploy scripts (`deploy-forge.sh`, `deploy-test.sh`, `deploy-prod.sh`)
- `generate-blueprint.sh`

### jbct verify-slice

Validate existing slice project:

```bash
jbct verify-slice
jbct verify-slice /path/to/project
```

## SliceInvokerFacade

Interface for inter-slice communication:

```java
public interface SliceInvokerFacade {
    /**
     * Invoke a method on a remote slice.
     *
     * @param sliceArtifact Maven coordinates of target slice
     * @param methodName    Method to invoke
     * @param request       Request object (single parameter)
     * @param responseType  Expected response class
     * @return Promise of response
     */
    <R> Promise<R> invoke(String sliceArtifact,
                          String methodName,
                          Object request,
                          Class<R> responseType);
}
```

Implemented by Aether runtime. Handles:
- Serialization/deserialization
- Network transport
- Load balancing
- Retries and circuit breaking

## Aspect Interface

Decorator for slice instances:

```java
public interface Aspect<T> {
    T apply(T instance);

    static <T> Aspect<T> identity() {
        return t -> t;
    }
}
```

**Usage:**

```java
public class LoggingAspect<T> implements Aspect<T> {
    @Override
    public T apply(T instance) {
        return createLoggingProxy(instance);
    }
}

// When creating slice
var logging = new LoggingAspect<OrderService>();
OrderServiceFactory.create(logging, invoker);
```

## Slice Interface

Wrapper for Aether runtime:

```java
public interface Slice {
    List<SliceMethod<?, ?>> methods();
}

public record SliceMethod<Req, Resp>(
    MethodName name,
    Function<Req, Promise<Resp>> handler,
    TypeToken<Resp> responseType,
    TypeToken<Req> requestType
) {}
```

Created by `createSlice()` factory method. Used internally by Aether for routing.

## POM Configuration

### Slice Project pom.xml

Generated by `jbct init --slice` with all configuration inlined:

```xml
<project>
    <groupId>org.example</groupId>
    <artifactId>my-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
        <pragmatica-lite.version>0.9.10</pragmatica-lite.version>
        <jbct.version>0.4.8</jbct.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>core</artifactId>
            <version>${pragmatica-lite.version}</version>
        </dependency>
        <dependency>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>slice-api</artifactId>
            <version>${pragmatica-lite.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.pragmatica-lite</groupId>
                <artifactId>jbct-maven-plugin</artifactId>
                <version>${jbct.version}</version>
                <executions>
                    <execution>
                        <id>jbct-check</id>
                        <goals>
                            <goal>format-check</goal>
                            <goal>lint</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>slice-deps</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>collect-slice-deps</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>slice-package</id>
                        <goals>
                            <goal>package-slices</goal>
                            <goal>generate-blueprint</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>slice-install</id>
                        <goals>
                            <goal>install-slices</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>slice-verify</id>
                        <goals>
                            <goal>verify-slice</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Adding External Slice Dependency

```xml
<dependency>
    <groupId>org.example</groupId>
    <artifactId>other-service-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

**Note:** Use `provided` scope for slice API dependencies. They're resolved at runtime by Aether.
