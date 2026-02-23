---
RFC: 0006
Title: Resource Provisioning Architecture
Status: Draft
Author: Sergiy Yevtushenko
Created: 2026-02-04
Updated: 2026-02-19
Affects: [jbct-cli, aether]
---

## Summary

Type-safe resource injection into Aether slices via annotation-based qualifiers and configuration-driven provisioning. Defines the architecture for managing configured resources (database connections, HTTP clients, connection pools) as first-class slice dependencies alongside slice-to-slice invocation.

## Motivation

### Current Pain Points

1. **Implicit resource binding** - Resources hardcoded in factory methods with string-based config paths
2. **No type safety** - Config sections and resource types not verified at compile time
3. **Manual provisioning** - Developers must explicitly wire resource creation in every slice
4. **No reuse** - Each slice reimplements resource discovery and caching logic
5. **Config coupling** - Slice implementation coupled to specific aether.toml structure
6. **Testing friction** - Difficult to inject test doubles or alternative resources

### Goal

Provide a unified, type-safe mechanism for resource provisioning that:
- Enables developer-defined qualifiers for domain-specific resources
- Maintains separation between config loading and resource creation
- Supports SPI-based extensibility for new resource types
- Automatically caches resources by (type, config) key
- Enables parallel resolution of resources and cross-slice dependencies
- Preserves type safety across the entire provisioning pipeline

## Design

### Boundaries

- **jbct-cli**: Detects @ResourceQualifier annotations on slice factory parameters, generates resource provisioning calls in factory code
- **aether/config**: ConfigService SPI and TOML parsing
- **aether/infra-api**: ResourceProvider SPI, ResourceFactory interface
- **aether/slice-api**: @ResourceQualifier meta-annotation, SliceCreationContext
- **aether/infra-slices**: Concrete resource implementations (SqlConnector, JooqConnector, HttpClient, etc.) and their ResourceFactory SPI implementations

### 1. @ResourceQualifier Meta-Annotation

Enables users to define custom qualifiers that bind parameter types to configuration sections.

**Location:** `aether/slice-api` → `org.pragmatica.aether.slice.annotation`

**Definition:**
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ResourceQualifier {
    Class<?> type();        // Resource interface class (e.g., SqlConnector.class)
    String config();        // Config section path (e.g., "database.primary")
}
```

**Annotations created by users:**
```java
@ResourceQualifier(type = SqlConnector.class, config = "database.primary")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PrimaryDb {}

@ResourceQualifier(type = SqlConnector.class, config = "database.readonly")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ReadonlyDb {}

@ResourceQualifier(type = HttpClient.class, config = "http.external")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ExternalHttp {}
```

### 2. ResourceFactory SPI Interface

Factories implement type-safe creation of resources from configuration.

**Location:** `aether/infra-api` → `org.pragmatica.aether.infra`

**Definition:**
```java
public interface ResourceFactory<T, C> {
    Class<T> resourceType();
    Class<C> configType();
    Promise<T> create(C config);
}
```

**Example implementation:**
```java
public final class JdbcSqlConnectorFactory
       implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {

    @Override
    public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(
            DatabaseConnectorError::databaseFailure,
            () -> {
                var hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(config.effectiveJdbcUrl());
                config.username().onPresent(hikariConfig::setUsername);
                config.password().onPresent(hikariConfig::setPassword);
                hikariConfig.setConnectionTimeout(config.poolConfig().connectionTimeout().toMillis());
                // ... more config
                var dataSource = new HikariDataSource(hikariConfig);
                return JdbcSqlConnector.jdbcSqlConnector(config, dataSource);
            }
        );
    }
}
```

**Registration:** Factories registered via `META-INF/services/org.pragmatica.aether.infra.ResourceFactory`

### 3. ConfigService Interface

Loads typed configuration sections from aether.toml.

**Location:** `aether/config` → `org.pragmatica.aether.config`

**Definition:**
```java
public interface ConfigService {
    <T> Result<T> config(String section, Class<T> configClass);
    <T> Promise<T> configAsync(String section, Class<T> configClass);
    boolean hasSection(String section);
    Option<String> getString(String key);
    Option<Integer> getInt(String key);
    Option<Boolean> getBoolean(String key);

    static Option<ConfigService> instance();
    static void setInstance(ConfigService service);
}
```

**Responsibilities:**
- Parse aether.toml into domain-specific config classes
- Map configuration sections to typed POJOs/records
- Provide access to raw config values when needed

### 4. ResourceProvider Interface

Discovers ResourceFactory implementations via SPI, creates and caches resources.

**Location:** `aether/infra-api` → `org.pragmatica.aether.infra`

**Definition:**
```java
public interface ResourceProvider {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    boolean hasFactory(Class<?> resourceType);

    static Option<ResourceProvider> instance();
    static void setInstance(ResourceProvider provider);
    static ResourceProvider resourceProvider();  // Create SPI-based instance
}
```

**Caching strategy:**
- Resources cached by (resourceType, configSection) tuple
- Single instance per configuration section
- Cache shared across all slices in runtime

**SPI discovery:**
- Discovers all ResourceFactory implementations via ServiceLoader
- Indexes factories by resource type
- Maps config sections to typed config classes via ConfigService

### 5. SliceCreationContext

Unified context passed to slice factory methods containing both invoker and resources.

**Location:** `aether/slice-api` → `org.pragmatica.aether.slice`

**Definition:**
```java
public interface SliceCreationContext {
    SliceInvokerFacade invoker();
    ResourceProviderFacade resources();

    static SliceCreationContext sliceCreationContext(
            SliceInvokerFacade invoker,
            ResourceProviderFacade resources) {
        return new DefaultSliceCreationContext(invoker, resources);
    }
}

record DefaultSliceCreationContext(
        SliceInvokerFacade invoker,
        ResourceProviderFacade resources) implements SliceCreationContext {
}
```

**Replaces:** Previous pattern where `SliceInvokerFacade` was passed directly as parameter

**Enables:** Parallel resolution of resources and cross-slice dependencies via `Promise.all()`

### 6. Generated Factory Code Pattern

**jbct-cli** detects @ResourceQualifier annotations and generates resource provisioning code.

**Input (user-defined factory):**
```java
@Slice
public interface OrderRepository {
    Promise<Order> save(Order order);

    static OrderRepository orderRepository(
            @PrimaryDb SqlConnector db,
            @ReadonlyDb SqlConnector reportDb,
            InventoryService inventory) {
        return new orderRepository(db, reportDb, inventory);
    }
}
```

**Generated factory (by jbct-cli):**
```java
public final class OrderRepositoryFactory {
    public static Promise<OrderRepository> orderRepository(
            Aspect<OrderRepository> aspect,
            SliceCreationContext ctx) {
        return Promise.all(
                ctx.resources().provide(SqlConnector.class, "database.primary"),
                ctx.resources().provide(SqlConnector.class, "database.readonly"),
                ctx.invoker().methodHandle("inventory:artifact", "save", ...),
                ctx.invoker().methodHandle("inventory:artifact", "check", ...))
            .map((db, reportDb, saveHandle, checkHandle) -> {
                var inventory = new inventoryServiceProxy(saveHandle, checkHandle);
                return aspect.apply(
                    OrderRepository.orderRepository(db, reportDb, inventory)
                );
            });
    }
}
```

**Processing rules:**
1. Detect all factory parameters
2. For each parameter:
   - If type has @ResourceQualifier annotation: extract resource type and config section → **resource dependency**
   - If type is @Slice interface: extract slice dependency → **slice dependency** (proxy via `SliceInvokerFacade`)
   - If type is plain interface with static factory method: → **plain interface dependency** (constructed locally)
   - Otherwise: error (unknown dependency type)
3. For plain interface dependencies, introspect their factory method parameters:
   - If any parameter has @ResourceQualifier: add transitive resource provisioning
   - Pass provisioned resources as arguments to the plain interface factory
4. Build Promise.all(...) chain capturing all async dependencies (resources + slice handles)
5. Extract qualifier info:
   - `@PrimaryDb` → `@ResourceQualifier(type=SqlConnector.class, config="database.primary")`
   - `@ReadonlyDb` → `@ResourceQualifier(type=SqlConnector.class, config="database.readonly")`
6. Generate `ctx.resources().provide(type, config)` calls (direct + transitive from plain interfaces)
7. Generate `ctx.invoker().methodHandle(...)` calls for slice dependencies
8. Construct plain interface instances by calling their factory methods with provisioned args
9. Map extracted promises to user factory method

### 7. Transitive Resource Provisioning via Plain Interfaces

When a slice depends on a plain interface (non-`@Slice`, has a static factory method), and that factory method has `@ResourceQualifier`-annotated parameters, the processor provisions those resources transitively.

**Input (plain interface with resource dependency):**
```java
// Not a @Slice — just a plain interface with a factory method
public interface KycVerificationStep {
    Promise<Boolean> verify(String customerId);

    static KycVerificationStep kycVerificationStep(
            @KycProvider HttpClient httpClient) {
        return new kycVerificationStep(httpClient);
    }
}

@Slice
public interface LoanService {
    Promise<LoanResult> processLoan(LoanRequest request);

    static LoanService loanService(KycVerificationStep kycStep) {
        return new loanService(kycStep);
    }
}
```

**Generated factory (by slice-processor):**
```java
public final class LoanServiceFactory {
    public static Promise<LoanService> loanService(
            Aspect<LoanService> aspect,
            SliceCreationContext ctx) {
        return Promise.all(
                ctx.resources().provide(HttpClient.class, "http.kyc"))
            .map((kycStep_httpClient) -> {
                var kycStep = KycVerificationStep.kycVerificationStep(kycStep_httpClient);
                return aspect.apply(LoanService.loanService(kycStep));
            });
    }
}
```

**Key behavior:**
- The processor introspects `KycVerificationStep.kycVerificationStep()` and discovers its `@KycProvider HttpClient` parameter
- It generates a `ctx.resources().provide(HttpClient.class, "http.kyc")` call
- The provisioned resource is passed as an argument: `kycVerificationStep(kycStep_httpClient)`
- Variable naming: `{depParamName}_{factoryParamName}` (e.g., `kycStep_httpClient`)
- Multiple plain interfaces with resource params are all resolved in the same `Promise.all()` call

### Configuration File Structure (aether.toml)

**Example:**
```toml
[database.primary]
driver = "postgresql"
jdbc_url = "jdbc:postgresql://localhost:5432/production"
username = "app_user"
password = "secret"
pool.min_connections = 5
pool.max_connections = 20
pool.connection_timeout_ms = 30000

[database.readonly]
driver = "postgresql"
jdbc_url = "jdbc:postgresql://localhost:5432/production-read-replica"
username = "readonly_user"
password = "secret_readonly"
pool.min_connections = 2
pool.max_connections = 10

[http.external]
base_url = "https://api.external.com"
connection_timeout_ms = 10000
read_timeout_ms = 30000
max_retries = 3
```

**Binding to config classes:**

DatabaseConnectorConfig record:
```java
public record DatabaseConnectorConfig(
    String driver,
    String jdbcUrl,
    String username,
    String password,
    PoolConfig poolConfig
) {
    // Fields snake_case in TOML, camelCase in Java via mapping
}
```

## Module Layout

```
aether/
├── config/                       # Configuration loading
│   ├── pom.xml
│   └── src/main/java/org/pragmatica/aether/config/
│       └── ConfigService.java    # Interface for loading typed config sections
│
├── infra-api/                    # Resource provisioning SPI
│   ├── pom.xml
│   └── src/main/java/org/pragmatica/aether/infra/
│       ├── ResourceFactory.java  # SPI: Factory interface
│       └── ResourceProvider.java # Provider discovering factories via SPI
│
├── slice-api/                    # Slice framework and resource binding
│   ├── pom.xml
│   └── src/main/java/org/pragmatica/aether/slice/
│       ├── annotation/
│       │   └── ResourceQualifier.java  # Meta-annotation for qualifiers
│       └── SliceCreationContext.java   # Unified context for slice creation
│
└── infra-slices/                 # Concrete resource implementations
    ├── pom.xml
    └── infra-db-connector/       # Database connector suite
        ├── api/
        │   ├── DatabaseConnector.java       (lifecycle: config, isHealthy, stop)
        │   ├── SqlConnector.java            (raw SQL: queryOne, queryList, update, batch)
        │   ├── DatabaseConnectorConfig.java
        │   └── DatabaseConnectorError.java
        │
        ├── db-jooq-api/
        │   ├── JooqConnector.java           (type-safe jOOQ: dsl, fetchOne, fetch, execute)
        │   └── Jooq.java                    (@ResourceQualifier for JooqConnector)
        │
        ├── jdbc/
        │   └── JdbcSqlConnectorFactory.java       (SPI: SqlConnector via JDBC)
        │
        ├── r2dbc/
        │   └── R2dbcSqlConnectorFactory.java      (SPI: SqlConnector via R2DBC, priority 10)
        │
        ├── jooq/jdbc/
        │   └── JdbcJooqConnectorFactory.java      (SPI: JooqConnector via JDBC)
        │
        └── jooq-r2dbc/
            └── R2dbcJooqConnectorFactory.java     (SPI: JooqConnector via R2DBC, priority 10)
```

## Runtime Flow

**Step 1: Aether startup**
- AetherNode initializes ConfigService with aether.toml
- AetherNode creates ResourceProvider via SPI discovery
- ResourceProvider discovers all ResourceFactory implementations
- ResourceProvider.setInstance() makes it globally accessible

**Step 2: Slice creation**
- User code calls slice factory method (e.g., `OrderRepository.orderRepository(...)`)
- jbct-generated wrapper factory is invoked with `SliceCreationContext`
- Generated code calls:
  ```
  ctx.resources().provide(SqlConnector.class, "database.primary")
  ```
- ResourceProvider flow:
  1. Check cache for (SqlConnector, "database.primary") → Found/Not found
  2. If not cached:
     a. ConfigService loads "database.primary" section as DatabaseConnectorConfig
     b. ResourceProvider discovers JdbcSqlConnectorFactory (via SPI, highest-priority factory whose `supports(config)` returns true)
     c. Calls factory.provision(config) → Promise<SqlConnector>
     d. Caches result
  3. Return cached/newly created SqlConnector

**Step 3: Cross-slice invocation (parallel)**
```
Promise.all(
    ctx.resources().provide(...),  // Parallel: fetch config, create resource
    ctx.invoker().methodHandle(...) // Parallel: resolve cross-slice dependency
).map(...)  // Wait for all, then compose
```

## Alternatives Considered

### 1. String-based qualifiers (rejected)

**Approach:** Use strings instead of annotations
```java
slice.factory(
    "@PrimaryDb",   // String qualifier
    "@InventoryService"
)
```

**Problems:**
- No compile-time verification
- IDE autocomplete difficult
- Refactoring breaks silently
- No connection between qualifier name and config section

**Chosen approach:** Annotation-based with embedded config path ensures type safety and discoverability.

### 2. Constructor injection (rejected)

**Approach:** Inject resources via constructor parameters
```java
public OrderRepository(
        @PrimaryDb SqlConnector db,
        InventoryService inventory) { ... }
```

**Problems:**
- Mixed concerns: infrastructure config mixed with business logic
- Doesn't work for static factory methods (preferred pattern in JBCT)
- Ordering complexity if multiple resources/slices
- Hard to support conditional provisioning (e.g., optional failover)

**Chosen approach:** Factory method parameters + SliceCreationContext separates config concern and enables declarative dependency ordering.

### 3. Centralized DI container (rejected)

**Approach:** Single container managing all resource provisioning
```java
container.register(SqlConnector.class, "primary", ...)
container.register(SqlConnector.class, "readonly", ...)
```

**Problems:**
- Requires manual registration (vs. SPI discovery)
- Config to resource-type binding implicit (vs. explicit in qualifier)
- Hard to extend without modifying container code
- Testing requires container mocking

**Chosen approach:** SPI-based ResourceFactory discovery is extensible and testable.

### 4. Config file location (TOML vs. code)

**Alternative:** Embed config paths in code
```java
@ResourceQualifier(type = SqlConnector.class, config = "database.primary")
```

**Problems with code-embedded:**
- Changes require recompilation
- Difficult to provide environment-specific values
- Not suitable for secrets management

**Chosen approach:** Config in aether.toml with code-embedded path reference separates deployment concerns.

## Migration

### From Manual Provisioning to Resource Provisioning

**Before (manual):**
```java
@Slice
public interface OrderRepository {
    Promise<Order> save(Order order);

    static OrderRepository orderRepository(
            ConfigService config,
            InventoryService inventory) {
        return config.config("database.primary", DatabaseConnectorConfig.class)
            .async()
            .flatMap(dbConfig ->
                ResourceFactory.jdbc().create(dbConfig)
            )
            .map(db -> new orderRepository(db, inventory));
    }
}
```

**After (resource provisioning):**
```java
@Slice
public interface OrderRepository {
    Promise<Order> save(Order order);

    static OrderRepository orderRepository(
            @PrimaryDb SqlConnector db,
            InventoryService inventory) {
        return new orderRepository(db, inventory);
    }
}
```

**Migration steps:**
1. Define @ResourceQualifier annotation in application code
2. Annotate factory parameters with qualifiers
3. Remove manual ConfigService/ResourceFactory provisioning code
4. Update aether.toml with resource configuration sections
5. Regenerate slice factories via jbct-cli

## Breaking Changes

Candidates for version bump:

1. ResourceFactory interface signature change
2. ResourceProvider method signature change
3. @ResourceQualifier meta-annotation attribute changes
4. SliceCreationContext method signature change
5. SPI registration path changes
6. ConfigService interface changes affecting deserialization

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) - Factory method naming
- [RFC-0004: Slice Packaging](RFC-0004-slice-packaging.md) - Slice JAR structure
- [RFC-0003: Invoker Protocol](RFC-0003-invoker-protocol.md) - Cross-slice method invocation
- Pragmatica Lite Promise/Result: https://github.com/siy/pragmatica-lite
- Aether configuration: docs/aether/configuration.md
