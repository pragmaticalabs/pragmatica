# Aether Slice Development

Build self-contained, independently deployable business capabilities with the Aether slice framework.

## Overview

Documents the complete slice development workflow: from `@Slice` interface definition through annotation processing, code generation, packaging, and deployment via blueprints.

## Documentation

| Document | Description |
|----------|-------------|
| [Quickstart](quickstart.md) | Create your first slice in 5 minutes |
| [Development Guide](development-guide.md) | Complete development workflow |
| [Architecture](architecture.md) | Internal design, code generation, packaging |
| [Reference](reference.md) | `@Slice` API, manifest format, CLI commands |
| [Deployment](deployment.md) | Blueprints, Forge, environments |
| [Runtime](runtime.md) | How slices execute in Aether |
| [Troubleshooting](troubleshooting.md) | Common issues and solutions |

## Key Concepts

1. **Single-param methods** - All slice API methods take one request parameter and return `Promise<T>`
2. **Factory method** - Static method creating the slice instance with its dependencies
3. **All deps via invoker** - Dependencies generate proxies delegating to `SliceInvokerFacade`
4. **Blueprint** - TOML file listing slices in dependency order for deployment

## Build Pipeline

```
@Slice interface -> Annotation Processor -> Generated code + manifests
                                                |
                              Maven Plugin -> Slice JAR
                                                |
                              Blueprint Generator -> blueprint.toml
```

## Generated Artifacts

| Artifact | Purpose |
|----------|---------|
| Factory Class (`{pkg}.{Name}Factory`) | Creates instance with dependency wiring |
| Slice Manifest (`META-INF/slice/{Name}.manifest`) | Metadata for packaging/deployment |
| Slice JAR | Interface, factory, bundled dependencies (fat JAR) |

## Dependencies

- Java 25+
- Maven 3.8+
- JBCT CLI 0.6.1+
