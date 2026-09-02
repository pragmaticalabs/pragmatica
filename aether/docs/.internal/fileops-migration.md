# FileOps Migration Plan

**Date:** 2026-04-13
**PR:** #149 (FileOps utility)
**Goal:** Replace all direct `java.nio.file.Files` usage with `FileOps` from `org.pragmatica.lang.io`

---

## Summary

56 production files across 16 modules use `java.nio.file.Files` directly. After migration, none should import `java.nio.file.Files` — all file I/O goes through `FileOps`.

---

## Files to migrate, by module

### aether/cli (7 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `AetherCli.java` | exists, readAllBytes, readString, size | try/catch | TLS cert loading |
| `ApplyState.java` | createDirectories, deleteIfExists, exists, readString, writeString | try/catch | Apply state persistence |
| `BootstrapPhaseDeploy.java` | createTempFile, writeString | try/catch | Temp config for deploy |
| `BootstrapPhaseFormation.java` | createDirectories, writeString | try/catch | API key file storage |
| `BootstrapStatePersistence.java` | createDirectories, deleteIfExists, exists, readString, writeString | try/catch | Bootstrap state JSON |
| `ClusterApplyCommand.java` | readString | Result.lift | Config file reading |
| `ClusterBootstrapCommand.java` | readString | try/catch | Config file reading |
| `ClusterRegistry.java` | createDirectories, exists, writeString | try/catch | Local cluster registry |
| `ClusterRotateKeyCommand.java` | createDirectories, writeString | try/catch | Key file rotation |

### aether/aether-setup (3 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `DockerGenerator.java` | createDirectories, setPosixFilePermissions, writeString | throws Exception + Result.lift | Generator output |
| `KubernetesGenerator.java` | createDirectories, setPosixFilePermissions, writeString | throws Exception + Result.lift | Generator output |
| `LocalGenerator.java` | createDirectories, setPosixFilePermissions, writeString | throws Exception + Result.lift | Generator output |

### aether/pg-tools (5 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `CodegenPipeline.java` | createDirectories, writeString | try/catch | Generated Java files |
| `JooqXmlExporter.java` | createDirectories, writeString | try/catch → Result | XML export |
| `CheckJooqXmlMojo.java` | readString | try/catch → MojoExecutionException | Check goal |
| `ExportJooqXmlMojo.java` | readString | try/catch → RuntimeException | Export goal |
| `GenerateMojo.java` | createDirectories, readString, writeString | try/catch | Code generation |
| `LintMojo.java` | readString | try/catch | Lint input |

### aether/slice (4 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `BlueprintParser.java` | readString | try/catch → Result | Blueprint loading |
| `FrameworkClassLoader.java` | exists, isDirectory, list | non-throwing + try/catch | Classpath scanning |
| `LocalRepository.java` | exists | non-throwing | Artifact lookup |
| `RemoteRepository.java` | createDirectories, createTempFile, deleteIfExists, exists, move, write | try/catch | Artifact download |

### aether/node (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `BuiltinRepository.java` | createTempFile, write | try/catch | Artifact extraction |

### aether/environment-integration (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `FileSecretsProvider.java` | readString | try/catch → Promise | Secret file reading |

### aether/forge (2 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `StartupConfig.java` | exists, isReadable | non-throwing | Config validation |
| `SimulatorConfig.java` | exists, readString, writeString | Result.lift + non-throwing | Simulator state |

### aether/aether-config (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `ConfigValidator.java` | exists | non-throwing | Path validation |

### aether/aether-ttm-onnx (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `OnnxTTMPredictor.java` | exists | non-throwing | Model path check |

### integrations/config/toml (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `TomlParser.java` | readString | Result.lift | TOML file loading |

### integrations/consensus (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `GitBackedPersistence.java` | exists, readString, writeString | Result.lift | State persistence |

### integrations/storage (2 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `DefaultSnapshotManager.java` | createDirectories, deleteIfExists, readString, writeString | Result.lift | Snapshot files |
| `LocalDiskTier.java` | createDirectories, delete, exists, readAllBytes, size, walk, write | Result.lift + try/catch | AHSE local tier |

### jbct/jbct-core (5 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `ConfigLoader.java` | exists, isRegularFile | non-throwing | Config discovery |
| `FileCollector.java` | isDirectory, size | non-throwing | Source scanning |
| `GitHubContentFetcher.java` | createDirectories, writeString | try/catch → .await() | Download to disk |
| `SourceFile.java` | readString, writeString | try/catch → Result | Source read/write |
| `SourceRoot.java` | exists, isDirectory, walk | non-throwing + try/catch | Source tree walking |

### jbct/jbct-cli (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `InitCommand.java` | createDirectories, exists | non-throwing + try/catch | Project init |

### jbct/jbct-init (12 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `AiToolsInstaller.java` | copy, createDirectories, exists, walkFileTree, writeString | try/catch | Skill installation |
| `AiToolsUpdater.java` | createDirectories, exists, readString, writeString | try/catch | Skill update |
| `EventAdder.java` | createDirectories, exists, readString, writeString | try/catch | Event scaffolding |
| `GitHubVersionResolver.java` | createDirectories, deleteIfExists, exists, newBufferedReader, newBufferedWriter | try/catch | Version cache |
| `JarInstaller.java` | copy, createDirectories, createTempFile, deleteIfExists, exists, isRegularFile, move | try/catch | JAR download |
| `PersistenceAdder.java` | createDirectories, readString, writeString | try/catch | Persistence scaffolding |
| `ProjectConfig.java` | exists, readString | try/catch | Config loading |
| `ProjectFiles.java` | exists, writeString | try/catch | File writing |
| `ProjectInitializer.java` | createDirectories, createFile, exists, writeString | try/catch | Project scaffolding |
| `SliceAdder.java` | createDirectories, exists | try/catch | Slice scaffolding |
| `SliceProjectInitializer.java` | createDirectories, exists, setPosixFilePermissions, writeString | try/catch | Slice project init |
| `SliceProjectValidator.java` | exists, list, readString | try/catch | Validation |

### jbct/jbct-maven-plugin (4 files)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `CollectSliceDepsMojo.java` | walk | try/catch | Dependency collection |
| `GenerateBlueprintMojo.java` | createDirectories, exists, readString, walk, writeString | try/catch | Blueprint generation |
| `PackageBlueprintMojo.java` | readString, walk | try/catch | Blueprint packaging |
| `PackageSlicesMojo.java` | copy, createTempFile, isDirectory, list, readAllBytes, walk, write, writeString | try/catch | Slice packaging |

### jbct/slice-processor (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `RouteConfigLoader.java` | exists, isRegularFile | non-throwing | Route config discovery |

### jbct/jbct-core/SliceManifest (1 file)

| File | Methods used | Current pattern | Notes |
|------|-------------|----------------|-------|
| `SliceManifest.java` | exists, list, newInputStream | try/catch + non-throwing | Manifest loading |

---

## Migration priority

### Tier 1 — highest value (removes JBCT-EX-01 suppressions)

The setup generators use `throws Exception` + `Result.lift()`. Migrating to `FileOps` eliminates the suppression and the two-layer pattern entirely:

- `DockerGenerator.java`
- `LocalGenerator.java`
- `KubernetesGenerator.java`

### Tier 2 — high volume (jbct-init, 12 files)

The `jbct-init` module is the heaviest user (12 files, 29 `createDirectories` + 9 `writeString`). All use try/catch with manual error handling. `FileOps` would eliminate ~100 lines of boilerplate.

### Tier 3 — core infrastructure

Files in `aether/cli`, `aether/slice`, `integrations/storage`, `integrations/consensus`. Mix of patterns — standardizing on `FileOps` improves consistency.

### Tier 4 — non-throwing only

Files that only use `exists()`, `isDirectory()`, `isRegularFile()`. Trivial delegation, low urgency, but completes the "single import" goal:

- `ConfigValidator.java`
- `OnnxTTMPredictor.java`
- `ConfigLoader.java`
- `FileCollector.java`
- `LocalRepository.java`
- `RouteConfigLoader.java`
- `StartupConfig.java`

---

## Migration checklist

For each file:

1. Replace `import java.nio.file.Files;` with `import static org.pragmatica.lang.io.FileOps.*;`
2. Replace `Files.readString(path)` → `readString(path)` (returns `Result<String>`)
3. Replace `Files.writeString(path, content)` → `writeString(path, content)` (returns `Result<Unit>`)
4. Replace `Files.createDirectories(path)` → `createDirectories(path)` (returns `Result<Path>`)
5. Replace `Files.exists(path)` → `exists(path)` (returns `boolean`)
6. Remove try/catch blocks around file operations — use `Result` chain instead
7. Remove `@SuppressWarnings("JBCT-EX-01")` where the suppression was for file I/O
8. Verify no remaining `java.nio.file.Files` import
