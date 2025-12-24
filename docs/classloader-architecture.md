# Slice ClassLoader Architecture

## Overview

This document describes the classloader architecture for Aether slice isolation and dependency management.

## Problem Statement

The original `SliceClassLoader` used hardcoded package prefixes (`org.pragmatica.*`) to determine parent-first vs child-first loading. This approach:

1. Cannot distinguish framework code from application code using the same package prefix
2. Forces shared domain libraries (e.g., `order-domain`) to be on node classpath
3. Provides no explicit control over what's shared vs isolated

## Design Goals

1. **Explicit dependency declaration** - No magic package-based detection
2. **Shared library support** - Common pattern for domain objects across slices
3. **Version conflict resolution** - Handle incompatible versions gracefully
4. **Node isolation** - Keep Aether runtime separate from application code
5. **Self-contained option** - Allow fully isolated slices (fat JAR style)

---

## ClassLoader Hierarchy

```
┌─────────────────────────────────┐
│      Bootstrap ClassLoader      │  JDK classes (java.*, javax.*, etc.)
│            (JVM)                │
└───────────────┬─────────────────┘
                │
┌───────────────▼─────────────────┐
│      Node ClassLoader           │  Aether runtime, Pragmatica framework
│   (system/application loader)   │  Netty, SLF4J, etc.
└───────────────┬─────────────────┘
                │
┌───────────────▼─────────────────┐
│  Shared Library ClassLoader     │  Shared dependencies from [shared] sections
│      (one per node)             │  First-loaded version wins
└───────────────┬─────────────────┘
                │
┌───────────────▼─────────────────┐
│     Slice ClassLoader           │  Slice code + conflicting dependencies
│    (one per loaded slice)       │  Child-first for slice isolation
└─────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Contents | Loading Strategy |
|-------|----------|------------------|
| Bootstrap | JDK classes | N/A (JVM managed) |
| Node | Aether runtime, frameworks | Parent-first |
| Shared Library | Application shared deps | Parent-first (delegates to Node) |
| Slice | Slice code, conflict overrides | Child-first (slice isolation) |

---

## Dependency Descriptor Format

Located at: `META-INF/dependencies/{fully.qualified.SliceClassName}`

```ini
# Dependencies for org.example.order.PlaceOrderSlice

[shared]
# Loaded into Shared Library ClassLoader (single instance across slices)
# First slice to load determines canonical version
# Use version patterns for compatibility requirements
org.pragmatica-lite:core:^0.8.0
org.example:order-domain:^1.0.0
org.slf4j:slf4j-api:^2.0.0
com.fasterxml.jackson.core:jackson-databind:^2.15.0

[slices]
# Other slices this slice depends on (resolved via SliceRegistry)
# Called through runtime with binary serialization
org.example:inventory-service:^1.0.0
org.example:pricing-service:^1.0.0
```

### Section Semantics

**`[shared]`** - Libraries shared across all slices
- Loaded into Shared Library ClassLoader
- First slice to declare a dependency sets the canonical version
- Subsequent slices check compatibility using `VersionPattern.matches()`
- If compatible: use existing version from shared loader
- If conflict: load conflicting version into Slice ClassLoader (shadows shared)

**`[slices]`** - Inter-slice dependencies
- Resolved via `SliceRegistry` at runtime
- Calls go through Aether runtime with binary serialization
- Type identity handled by serialization boundary

---

## Version Pattern Syntax

Supported patterns (from `VersionPattern.java`):

| Pattern | Example | Meaning |
|---------|---------|---------|
| Exact | `1.2.3` | Exactly version 1.2.3 |
| Range | `[1.0.0,2.0.0)` | >= 1.0.0 and < 2.0.0 |
| Comparison | `>=1.5.0` | Greater than or equal to 1.5.0 |
| Tilde | `~1.2.3` | Patch-level: >= 1.2.3, < 1.3.0 |
| Caret | `^1.2.3` | Minor-level: >= 1.2.3, < 2.0.0 |

### Compatibility Check

When slice requests `order-domain:^1.2.0` and shared loader has `order-domain:1.3.5`:
- Pattern `^1.2.0` means: >= 1.2.0 AND < 2.0.0
- Version `1.3.5` matches pattern → **compatible**, use shared version

When slice requests `order-domain:^2.0.0` and shared loader has `order-domain:1.3.5`:
- Pattern `^2.0.0` means: >= 2.0.0 AND < 3.0.0
- Version `1.3.5` does NOT match → **conflict**, load 2.x into slice loader

---

## Loading Algorithm

### Slice Loading Process

```
loadSlice(artifact):
    1. Load slice JAR
    2. Parse dependency descriptor from META-INF/dependencies/{SliceClass}
    3. For each dependency in [shared]:
        a. Check if already in Shared Library ClassLoader
        b. If not present: load into Shared Library ClassLoader
        c. If present: check version compatibility
           - Compatible: reuse existing (no action)
           - Conflict: mark for slice-local loading
    4. Create SliceClassLoader with:
        - Parent: Shared Library ClassLoader
        - URLs: slice JAR + conflicting dependency JARs
    5. Load slice class
    6. Register slice methods
```

### Class Loading Order (SliceClassLoader)

```
loadClass(name):
    1. Check if already loaded → return cached
    2. Try to find in slice JAR (child-first for isolation)
       - Found → return (slice's own code or conflict override)
    3. Delegate to parent (Shared Library ClassLoader)
       - Shared Library delegates to Node ClassLoader
       - Node delegates to Bootstrap
    4. Not found → ClassNotFoundException
```

---

## Example Scenarios

### Scenario 1: Normal Loading (No Conflicts)

```
Slice A declares: order-domain:^1.0.0
Slice B declares: order-domain:^1.0.0

1. Load Slice A
   - order-domain:1.2.0 loaded into SharedLibraryClassLoader
   - SliceClassLoader(A) parent = SharedLibraryClassLoader

2. Load Slice B
   - Check order-domain: loaded 1.2.0, required ^1.0.0 → Compatible
   - SliceClassLoader(B) parent = SharedLibraryClassLoader
   - Both slices share same order-domain instance
```

### Scenario 2: Version Conflict

```
Slice A declares: order-domain:^1.0.0
Slice C declares: order-domain:^2.0.0

1. Load Slice A
   - order-domain:1.2.0 loaded into SharedLibraryClassLoader

2. Load Slice C
   - Check order-domain: loaded 1.2.0, required ^2.0.0 → Conflict!
   - Load order-domain:2.0.0 into SliceClassLoader(C)
   - SliceClassLoader(C) shadows shared version with its own
   - Slice C uses 2.0.0, Slice A uses 1.2.0
```

### Scenario 3: Self-Contained Slice

```
# Slice with empty [shared] section
[shared]
# (empty - all dependencies bundled in JAR)

[slices]
org.example:inventory-service:^1.0.0
```

- All dependencies in slice JAR
- Only uses SharedLibraryClassLoader for delegation chain
- Full isolation, larger JAR size

---

## Migration Notes

### Existing Slices

Current slices with no `[shared]` section:
- Treated as legacy mode
- All `org.pragmatica.*` classes from parent (backwards compatible)
- Warning logged recommending explicit dependency declaration

### New Slices

Must declare all shared dependencies explicitly:

```ini
[shared]
org.pragmatica-lite:core:^0.8.0
# ... other shared deps

[slices]
# ... slice deps
```
