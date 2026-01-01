# jbct-maven-plugin 0.4.0 Parse Error Bug Report

## Summary

jbct-maven-plugin 0.4.0 fails to parse valid Java 21+ source files with "Parse error at line 1:1".

## Environment

- **jbct-maven-plugin version**: 0.4.0
- **Java version**: 25 (configured in pom.xml)
- **Maven version**: 3.9.11
- **OS**: macOS Darwin 24.6.0

## Error Message

```
[ERROR] Error formatting .../FrameworkClassLoader.java: Parse error at FrameworkClassLoader.java:1:1 - Parse error
[ERROR] Error formatting .../SharedDependencyLoader.java: Parse error at SharedDependencyLoader.java:1:1 - Parse error
[ERROR] Failed to execute goal org.pragmatica-lite:jbct-maven-plugin:0.4.0:format (default) on project slice: Formatting failed for 2 file(s)
```

## Affected Files

### 1. FrameworkClassLoader.java

**Location**: `slice/src/main/java/org/pragmatica/aether/slice/FrameworkClassLoader.java`

**Java features used**:
- Method references: `Causes::fromThrowable`, `FrameworkClassLoader::toUrl`
- Array constructor references: `urls.toArray(URL[]::new)`
- Try-with-resources with `Stream<Path>`
- `var` local variable type inference

**Minimal reproduction** (standard Java 11+ code):
```java
package org.pragmatica.aether.slice;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FrameworkClassLoader extends URLClassLoader {

    public FrameworkClassLoader(URL[] frameworkJars) {
        super(frameworkJars, ClassLoader.getPlatformClassLoader());
    }

    public static FrameworkClassLoader fromJars(Path... jarPaths) {
        var urls = new ArrayList<URL>();
        // ... processing
        return new FrameworkClassLoader(urls.toArray(URL[]::new));
    }
}
```

### 2. SharedDependencyLoader.java

**Location**: `slice/src/main/java/org/pragmatica/aether/slice/dependency/SharedDependencyLoader.java`

**Java features used** (likely causing the parse error):

1. **Unnamed variable `_`** (Java 22 preview / Java 23+):
```java
.flatMap(_ -> processApiSequentially(remaining, sharedLibraryLoader, repository));
```

2. **`List.getFirst()`** (Java 21+):
```java
var dependency = dependencies.getFirst();
```

3. **Pattern matching in switch with record patterns** (Java 21+):
```java
result -> switch (result) {
    case CompatibilityResult.Compatible(var loadedVersion) -> {
        // ...
        yield Promise.success(null);
    }
    case CompatibilityResult.Conflict(var loadedVersion, var required) -> {
        // ...
        yield loadConflictIntoSlice(dependency, repository, conflictUrls);
    }
}
```

4. **Exhaustive switch on sealed type with record deconstruction**:
```java
return switch (pattern) {
    case VersionPattern.Exact(Version version) -> version;
    case VersionPattern.Range(Version from, _, _, _) -> from;
    case VersionPattern.Comparison(_, Version version) -> version;
    case VersionPattern.Tilde(Version version) -> version;
    case VersionPattern.Caret(Version version) -> version;
};
```

## Root Cause Analysis

The parser likely does not support:

1. **Unnamed variable `_`** - Introduced as preview in Java 22, finalized in Java 23
2. **Record patterns in switch** - Java 21 feature
3. **Pattern matching for switch** - Java 21 feature
4. **`List.getFirst()`** method - Java 21 SequencedCollection API

The error occurring at "line 1:1" suggests the parser fails during initial tokenization or grammar recognition, possibly due to unsupported language level.

## Expected Behavior

The formatter should either:
1. Successfully parse and format Java 21+ source files
2. Provide a meaningful error message indicating which language feature is unsupported
3. Skip files with unsupported features (with warning) rather than failing the build

## Workaround

Currently using `<skip>true</skip>` in plugin configuration.

## Suggested Fix

Update the Java parser to support Java 21+ language features, specifically:
- Record patterns (JEP 440)
- Pattern matching for switch (JEP 441)
- Unnamed patterns and variables (JEP 456)
- SequencedCollection API methods

## Full File Contents

See attached files or repository:
- https://github.com/siy/aether/blob/release-0.6.3/slice/src/main/java/org/pragmatica/aether/slice/FrameworkClassLoader.java
- https://github.com/siy/aether/blob/release-0.6.3/slice/src/main/java/org/pragmatica/aether/slice/dependency/SharedDependencyLoader.java
