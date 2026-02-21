# Envelope Format Versioning

## What is the Envelope?

The **envelope** is the generated code structure that the slice-processor produces for each `@Slice` interface:

- **Factory class** (`{SliceName}Factory`) — the entry point for slice instantiation
- **Adapter record** — implements the slice interface, delegates to user code
- **Proxy records** — one per dependency, provides type-safe inter-slice communication
- **Manifest properties** — metadata written to `META-INF/slice/{SliceName}.manifest`

The envelope defines the contract between build-time code generation and runtime slice loading.

## What is `envelope.version`?

A simple integer that identifies the **format version** of the generated envelope code.

- Written to the Properties manifest during annotation processing
- Written to `Envelope-Version` in JAR MANIFEST.MF during packaging
- Checked by the runtime before loading a slice

**Key property:** The envelope version is decoupled from the project release version. It only changes when the generated code structure changes, not on every release.

## Current Version

`ENVELOPE_FORMAT_VERSION = 1` (defined in `ManifestGenerator.java`)

## When to Bump

Bump the envelope version when changing:

- Factory constructor signature (parameters, order)
- Generated methods in the Factory class (added, removed, renamed)
- Dependency wiring protocol (proxy record structure, how dependencies are injected)
- Resource provisioning through generated code
- Any change in `SliceProcessor` or `FactoryGenerator` output that would make old runtimes unable to call the generated code

## When NOT to Bump

Do not bump for:

- Adding new properties to the manifest file
- Changing logging or error messages in generated code
- Refactoring internal processor code that doesn't change output
- New slice lifecycle hooks (if backward compatible)
- Bug fixes in generation that don't change the structural contract

## Runtime Compatibility Check

The runtime (`SliceManifest.checkEnvelopeCompatibility()`) uses this logic:

| Envelope Version | Action |
|-----------------|--------|
| Missing | Warn, allow (backward compatibility with old JARs) |
| `"dev"` | Allow (development builds) |
| In `SUPPORTED_ENVELOPE_VERSIONS` | Allow |
| Not in supported set | Reject with error |

## Post-1.0 Multi-Version Support

Before 1.0, only one envelope version is supported at a time. After 1.0 GA:

1. Add new version to `SUPPORTED_ENVELOPE_VERSIONS` set in `SliceManifest.java`
2. Keep old versions in the set for backward compatibility
3. Version-specific loading logic can branch on the envelope version value
4. Remove old versions only in major releases with migration guides

## File Locations

| File | Role |
|------|------|
| `jbct/slice-processor/.../ManifestGenerator.java` | Defines `ENVELOPE_FORMAT_VERSION`, writes to Properties manifest |
| `jbct/jbct-maven-plugin/.../PackageSlicesMojo.java` | Copies Properties manifest into per-slice JAR, writes `Envelope-Version` to MANIFEST.MF |
| `aether/slice/.../SliceManifest.java` | Reads envelope version, defines `SUPPORTED_ENVELOPE_VERSIONS`, checks compatibility |
| `aether/slice/.../dependency/DependencyResolver.java` | Chains compatibility check into slice loading |
