package org.pragmatica.jbct.init;

import org.pragmatica.jbct.slice.SliceManifest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// Fills add-only, manifest-derived configuration gaps in an existing Aether slice project.
///
/// Two safe fixes are applied, both derived from the slice manifests that
/// slice-processor writes to `target/classes/META-INF/slice/*.manifest` during
/// `mvn compile` (so a prior compile is required):
///
/// 1. A missing `src/main/resources/slices/<Name>.toml` is generated from the
///    same template used by [SliceAdder].
/// 2. A `resources.toml` `[section]` referenced by a `@ResourceQualifier`
///    annotation but absent from the file is appended as a minimal stub.
///
/// Every fix is add-only and idempotent: new files are written with
/// [ProjectFiles#writeNewFile] (which refuses to overwrite), and existing
/// `resources.toml` content is never reordered or rewritten — missing sections
/// are appended after a separator that preserves trailing newlines. A second run
/// detects the just-added configuration and does nothing.
public final class FixSlice {
    /// SQL datasource resource types get a `[database]`-style stub (reused from
    /// [PersistenceAdder]); every other type gets a bare header plus a TODO comment.
    private static final Set<String> SQL_RESOURCE_TYPES = Set.of("SqlConnector", "PgSqlConnector");

    /// Matches a whole-line TOML section header, e.g. `[database.pool_config]`.
    /// Mirrors the pattern used by the blueprint generator so the diff semantics match.
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*\\[([^\\[\\]]+)]\\s*$");

    private static final Cause NO_MANIFESTS =
        Causes.cause("No slice manifests found under target/classes/META-INF/slice. Run 'mvn compile' first.");

    private final Path projectDir;

    private FixSlice(Path projectDir) {
        this.projectDir = projectDir;
    }

    /// Create a FixSlice bound to the given project directory.
    public static Result<FixSlice> fixSlice(Path projectDir) {
        return Result.success(new FixSlice(projectDir));
    }

    /// The outcome of a fix-slice run.
    public record FixResult(List<Path> createdFiles, List<String> configuredSections) {
        public boolean nothingToFix() {
            return createdFiles.isEmpty() && configuredSections.isEmpty();
        }
    }

    /// Apply the manifest-derived fixes and report what was written.
    public Result<FixResult> fix() {
        return loadManifests().flatMap(this::applyFixes);
    }

    private Result<FixResult> applyFixes(List<SliceManifest> manifests) {
        return createMissingSliceConfigs(manifests)
                  .flatMap(createdConfigs -> ensureResourceSections(manifests, createdConfigs));
    }

    // --- Fix 1: missing slices/<Name>.toml ---

    private Result<List<Path>> createMissingSliceConfigs(List<SliceManifest> manifests) {
        var writes = manifests.stream()
                              .filter(this::sliceConfigMissing)
                              .map(this::writeSliceConfig)
                              .toList();
        return Result.allOf(writes);
    }

    private boolean sliceConfigMissing(SliceManifest manifest) {
        return !Files.exists(sliceConfigPath(manifest));
    }

    private Result<Path> writeSliceConfig(SliceManifest manifest) {
        var target = sliceConfigPath(manifest);
        return createDirectories(target.getParent())
                  .flatMap(_ -> ProjectFiles.writeNewFile(target, SliceAdder.sliceConfigContent(manifest.sliceName())));
    }

    private Path sliceConfigPath(SliceManifest manifest) {
        return projectDir.resolve("src/main/resources/slices/" + manifest.sliceName() + ".toml");
    }

    // --- Fix 2: missing resources.toml [section] ---

    private Result<FixResult> ensureResourceSections(List<SliceManifest> manifests, List<Path> createdConfigs) {
        return readResourcesToml().flatMap(existing -> applyResourceSections(manifests, createdConfigs, existing));
    }

    private Result<FixResult> applyResourceSections(List<SliceManifest> manifests,
                                                    List<Path> createdConfigs,
                                                    Option<String> existingContent) {
        Set<String> existingSections = existingContent.map(FixSlice::extractSectionHeaders).or(Set.of());
        var missing = missingSections(manifests, existingSections);
        if (missing.isEmpty()) {
            return Result.success(new FixResult(createdConfigs, List.of()));
        }
        return writeSections(existingContent, missing, createdConfigs);
    }

    private static Map<String, String> missingSections(List<SliceManifest> manifests, Set<String> existingSections) {
        var missing = new LinkedHashMap<String, String>();
        for (var manifest : manifests) {
            for (var ref : manifest.resourceConfigRefs()) {
                if (!existingSections.contains(ref.configSection()) && !missing.containsKey(ref.configSection())) {
                    missing.put(ref.configSection(), ref.resourceType());
                }
            }
        }
        return missing;
    }

    private Result<FixResult> writeSections(Option<String> existingContent,
                                            Map<String, String> missing,
                                            List<Path> createdConfigs) {
        var blocks = renderBlocks(missing);
        var newContent = existingContent.map(content -> content + sectionSeparator(content) + blocks)
                                        .or(RESOURCES_HEADER + blocks);
        return writeString(resourcesTomlPath(), newContent)
                  .map(_ -> buildResult(createdConfigs, existingContent.isEmpty(), missing));
    }

    private FixResult buildResult(List<Path> createdConfigs, boolean freshResources, Map<String, String> missing) {
        var created = new ArrayList<>(createdConfigs);
        if (freshResources) {
            created.add(resourcesTomlPath());
        }
        return new FixResult(List.copyOf(created), List.copyOf(missing.keySet()));
    }

    private static String renderBlocks(Map<String, String> missing) {
        var sb = new StringBuilder();
        for (var entry : missing.entrySet()) {
            sb.append(renderBlock(entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    private static String renderBlock(String section, String type) {
        return SQL_RESOURCE_TYPES.contains(type)
               ? PersistenceAdder.databaseConfigStub(section)
               : bareStub(section, type);
    }

    private static String bareStub(String section, String type) {
        return "[" + section + "]\n# TODO: configure this " + type + " resource\n";
    }

    private static String sectionSeparator(String content) {
        return content.endsWith("\n")
               ? "\n"
               : "\n\n";
    }

    private static Set<String> extractSectionHeaders(String content) {
        var sections = new LinkedHashSet<String>();
        for (var line : content.split("\n")) {
            var matcher = SECTION_HEADER.matcher(line);
            if (matcher.matches()) {
                sections.add(matcher.group(1).trim());
            }
        }
        return sections;
    }

    private Path resourcesTomlPath() {
        return projectDir.resolve("src/main/resources/resources.toml");
    }

    // --- Manifest loading ---

    private Result<List<SliceManifest>> loadManifests() {
        var manifestDir = projectDir.resolve("target/classes/META-INF/slice");
        if (!Files.isDirectory(manifestDir)) {
            return NO_MANIFESTS.result();
        }
        return listManifestFiles(manifestDir).flatMap(FixSlice::loadAll);
    }

    private static Result<List<Path>> listManifestFiles(Path dir) {
        try (var stream = Files.list(dir)) {
            var files = stream.filter(path -> path.getFileName().toString().endsWith(".manifest"))
                              .sorted()
                              .toList();
            return Result.success(files);
        } catch (IOException e) {
            return Causes.cause("Failed to list manifests in " + dir + ": " + e.getMessage())
                         .result();
        }
    }

    private static Result<List<SliceManifest>> loadAll(List<Path> files) {
        if (files.isEmpty()) {
            return NO_MANIFESTS.result();
        }
        return Result.allOf(files.stream().map(SliceManifest::load).toList());
    }

    // --- File IO ---

    private static Result<Unit> createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
            return Result.success(Unit.unit());
        } catch (IOException e) {
            return Causes.cause("Failed to create directory " + dir + ": " + e.getMessage())
                         .result();
        }
    }

    private static Result<String> readString(Path path) {
        try {
            return Result.success(Files.readString(path));
        } catch (IOException e) {
            return Causes.cause("Failed to read " + path + ": " + e.getMessage())
                         .result();
        }
    }

    private static Result<Path> writeString(Path path, String content) {
        try {
            return Result.success(Files.writeString(path, content));
        } catch (IOException e) {
            return Causes.cause("Failed to write " + path + ": " + e.getMessage())
                         .result();
        }
    }

    private Result<Option<String>> readResourcesToml() {
        var path = resourcesTomlPath();
        if (!Files.exists(path)) {
            return Result.success(Option.none());
        }
        return readString(path).map(Option::some);
    }

    // Templates

    private static final String RESOURCES_HEADER = """
        # Resource configuration
        # Sections below are referenced by @ResourceQualifier annotations in the slices.

        """;
}
