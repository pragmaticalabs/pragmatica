# TOML Parser

Zero-dependency TOML parser with Result-based error handling and Option-based accessors.

## Overview

Parses TOML configuration files into an immutable `TomlDocument` with typed accessors returning `Option<T>`. Supports sections, nested sections, quoted/unquoted strings, multi-line strings, integers, floats, booleans, arrays, array of tables, and comments. Immutable updates via `doc.with(section, key, value)`.

All parsing errors are returned as typed `TomlError` causes (`SyntaxError`, `UnterminatedString`, `UnterminatedArray`, `InvalidValue`, `FileReadFailed`, `DuplicateKey`, `InvalidEscapeSequence`, `TableTypeMismatch`).

## Usage

```java
import org.pragmatica.config.toml.TomlParser;

// Parse from string
Result<TomlDocument> doc = TomlParser.parse(content);

// Parse from file
Result<TomlDocument> doc = TomlParser.parseFile(Path.of("config.toml"));

// Typed accessors
doc.getString("server", "host")           // Option<String>
doc.getInt("server", "port")              // Option<Integer>
doc.getBoolean("features", "enabled")     // Option<Boolean>
doc.getStringList("tags", "environments") // Option<List<String>>
doc.getTableArray("products")             // Option<List<Map<String, Object>>>

// Root-level properties (empty string section)
doc.getString("", "title")

// Section utilities
doc.hasSection("database")
doc.sectionNames()                        // Set<String>
doc.keys("database")                      // Set<String>

// Immutable update
TomlDocument updated = doc.with("server", "port", 9090);
```

## Dependencies

None -- this module only depends on `pragmatica-lite-core`.
