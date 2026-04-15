package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.slice.SliceConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.jbct.slice.SliceManifest;
import org.pragmatica.jbct.slice.SliceManifest.ResourceConfigRef;
import org.pragmatica.jbct.slice.SliceManifest.SliceDependency;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.FileOps;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.archiver.jar.JarArchiver;

/// Generates Blueprint.toml from slice manifests and their transitive dependencies.
/// The blueprint lists all slices in topological order (dependencies before dependents).
@Contract
@Mojo(name = "generate-blueprint",
 defaultPhase = LifecyclePhase.PACKAGE,
 requiresDependencyResolution = ResolutionScope.COMPILE)
public class GenerateBlueprintMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    @Parameter(property = "jbct.blueprint.output",
    defaultValue = "${project.build.directory}/blueprint.toml")
    private File blueprintFile;

    @Parameter(property = "jbct.blueprint.id")
    private String blueprintId;

    @Parameter(property = "jbct.blueprint.package", defaultValue = "true")
    private boolean packageBlueprint;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/resources.toml")
    private File resourcesTomlFile;

    @Parameter(defaultValue = "${project.basedir}/schema")
    private File schemaDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Component
    private MavenProjectHelper projectHelper;

    private final List<SliceEntry> orderedSlices = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private final Set<String> inStack = new HashSet<>();

    // Maps interface qualified name to artifact for local slices
    private final Map<String, String> interfaceToArtifact = new LinkedHashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping blueprint generation");
            return;
        }
        var localManifests = loadLocalManifests();
        var dependencyManifests = loadDependencyManifests(localManifests);
        if (localManifests.isEmpty() && dependencyManifests.isEmpty()) {
            getLog().info("No slice manifests found (local or dependency) - skipping blueprint generation");
            return;
        }
        validateResourceConfigs(localManifests, dependencyManifests);
        var graph = buildDependencyGraph(localManifests, dependencyManifests);
        topologicalSort(graph);
        generateBlueprint();
        getLog().info("Generated blueprint: " + blueprintFile);

        if (packageBlueprint) {
            packageBlueprintJar();
        }
    }

    // --- Resource config validation ---

    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*\\[([^\\[\\]]+)]\\s*$");

    private void validateResourceConfigs(List<SliceManifest> localManifests,
                                         List<DependencyManifest> dependencyManifests) throws MojoExecutionException {
        var allManifests = new ArrayList<SliceManifest>(localManifests);
        dependencyManifests.stream().map(DependencyManifest::manifest).forEach(allManifests::add);

        var allRefs = collectAllConfigRefs(allManifests);
        if (allRefs.isEmpty()) {
            return;
        }

        var availableSections = parseResourcesTomlSections();
        var missing = new LinkedHashSet<String>();
        for (var ref : allRefs) {
            if (!availableSections.contains(ref.configSection())) {
                missing.add(ref.configSection());
            }
        }

        if (!missing.isEmpty()) {
            var message = new StringBuilder("Resource config validation failed.\n");
            message.append("The following config sections are referenced by @ResourceQualifier annotations but missing from resources.toml:\n\n");
            for (var section : missing) {
                var slices = allRefs.stream()
                                    .filter(r -> r.configSection().equals(section))
                                    .map(ResourceConfigRef::resourceType)
                                    .distinct()
                                    .toList();
                message.append("  [").append(section).append("]  — required by: ").append(String.join(", ", slices)).append("\n");
            }
            message.append("\nAdd the missing sections to: ").append(resourcesTomlFile.getPath());
            throw new MojoExecutionException(message.toString());
        }

        getLog().info("Resource config validation passed: " + allRefs.size() + " reference(s), "
                      + availableSections.size() + " section(s) in resources.toml");
    }

    private List<ResourceConfigRef> collectAllConfigRefs(List<SliceManifest> manifests) {
        var refs = new ArrayList<ResourceConfigRef>();
        for (var manifest : manifests) {
            for (var ref : manifest.resourceConfigRefs()) {
                refs.add(new ResourceConfigRef(manifest.sliceName() + ":" + ref.resourceType(), ref.configSection()));
            }
        }
        return refs;
    }

    private Set<String> parseResourcesTomlSections() throws MojoExecutionException {
        var path = resourcesTomlFile.toPath();
        if (!FileOps.exists(path)) {
            return Set.of();
        }
        var result = FileOps.readString(path).map(this::extractSectionHeaders);
        if (result.isFailure()) {
            throw new MojoExecutionException("Failed to read resources.toml: " + resourcesTomlFile);
        }
        return result.unwrap();
    }

    private Set<String> extractSectionHeaders(String content) {
        var sections = new LinkedHashSet<String>();
        for (var line : content.split("\n")) {
            var matcher = SECTION_HEADER.matcher(line);
            if (matcher.matches()) {
                sections.add(matcher.group(1).trim());
            }
        }
        return sections;
    }

    // --- Manifest loading ---

    private List<SliceManifest> loadLocalManifests() throws MojoExecutionException {
        var manifestDir = new File(classesDirectory, "META-INF/slice");
        if (!manifestDir.exists()) {
            return List.of();
        }
        var manifestFiles = manifestDir.listFiles((d, n) -> n.endsWith(".manifest"));
        if (manifestFiles == null) {
            return List.of();
        }
        var manifests = new ArrayList<SliceManifest>();
        for (var file : manifestFiles) {
            var result = SliceManifest.load(file.toPath());
            if (result.isSuccess()) {
                manifests.add(result.unwrap());
            } else {
                throw new MojoExecutionException("Failed to load: " + file);
            }
        }
        return manifests;
    }

    /// Scans compile-scope dependency JARs for slice manifests not already discovered locally.
    /// This enables blueprint-only assembly projects with zero local slices.
    private List<DependencyManifest> loadDependencyManifests(List<SliceManifest> localManifests) {
        // Collect local slice interface names to avoid duplicating local slices found in dependency JARs
        var localInterfaces = new HashSet<String>();
        for (var m : localManifests) {
            localInterfaces.add(m.slicePackage() + "." + m.sliceName());
        }
        var result = new ArrayList<DependencyManifest>();
        for (var artifact : project.getArtifacts()) {
            if (!"compile".equals(artifact.getScope()) && !"runtime".equals(artifact.getScope())) {
                continue;
            }
            var jar = artifact.getFile();
            if (jar == null || !jar.exists() || !jar.getName().endsWith(".jar")) {
                continue;
            }
            loadManifestFromJar(jar).onPresent(manifest -> addIfNotLocal(manifest, artifact, localInterfaces, result));
        }
        if (!result.isEmpty()) {
            getLog().info("Discovered " + result.size() + " external slice manifest(s) from dependencies");
        }
        return result;
    }

    private void addIfNotLocal(SliceManifest manifest,
                               org.apache.maven.artifact.Artifact artifact,
                               Set<String> localInterfaces,
                               List<DependencyManifest> result) {
        var iface = manifest.slicePackage() + "." + manifest.sliceName();
        if (!localInterfaces.contains(iface)) {
            var coords = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            result.add(new DependencyManifest(coords, manifest));
            getLog().debug("Discovered external slice manifest: " + iface + " from " + coords);
        }
    }

    private Map<String, SliceEntry> buildDependencyGraph(List<SliceManifest> localManifests,
                                                         List<DependencyManifest> dependencyManifests)
    throws MojoExecutionException {
        var graph = new LinkedHashMap<String, SliceEntry>();
        // First pass: register all local slices and build interface-to-artifact map
        for (var manifest : localManifests) {
            var artifact = project.getGroupId() + ":" + manifest.implArtifactId() + ":" + project.getVersion();
            var config = loadSliceConfig(manifest);
            var entry = new SliceEntry(artifact, manifest, config, false);
            graph.put(artifact, entry);
            // Map the slice interface to its artifact for dependency resolution
            var sliceInterface = manifest.slicePackage() + "." + manifest.sliceName();
            interfaceToArtifact.put(sliceInterface, artifact);
        }
        // Register external dependency manifests as top-level entries
        for (var dep : dependencyManifests) {
            if (!graph.containsKey(dep.artifact())) {
                var entry = new SliceEntry(dep.artifact(), dep.manifest(), SliceConfig.defaultConfig(), true);
                graph.put(dep.artifact(), entry);
                var iface = dep.manifest().slicePackage() + "." + dep.manifest().sliceName();
                interfaceToArtifact.put(iface, dep.artifact());
            }
        }
        // Third pass: resolve transitive external dependencies from JAR files
        var allManifests = new ArrayList<SliceManifest>();
        localManifests.forEach(allManifests::add);
        dependencyManifests.stream().map(DependencyManifest::manifest).forEach(allManifests::add);
        for (var manifest : allManifests) {
            resolveExternalDependencies(manifest, graph);
        }
        return graph;
    }

    private void resolveExternalDependencies(SliceManifest manifest,
                                             Map<String, SliceEntry> graph)
    throws MojoExecutionException {
        for (var dep : manifest.dependencies()) {
            // Skip dependencies without artifact coordinates (local to this module)
            if (dep.artifact() == null || dep.artifact()
                                             .isEmpty()) {
                continue;
            }
            var depArtifact = dep.artifact() + ":" + dep.version();
            // Skip if already in graph (includes local slices and previously resolved deps)
            if (graph.containsKey(depArtifact)) {
                continue;
            }
            // Handle UNRESOLVED local dependencies - find matching resolved key in graph
            if ("UNRESOLVED".equals(dep.version())) {
                var artifactWithoutVersion = dep.artifact();
                var resolvedKey = graph.keySet()
                                       .stream()
                                       .filter(key -> key.startsWith(artifactWithoutVersion + ":"))
                                       .findFirst();
                if (resolvedKey.isPresent()) {
                    // Dependency already in graph with resolved version, skip adding duplicate
                    continue;
                }
                // UNRESOLVED dependency not in graph - skip and warn
                getLog().warn("Skipping UNRESOLVED dependency: " + dep.artifact() + " - not found in local graph");
                continue;
            }
            var depManifestOpt = loadManifestFromDependency(dep.artifact(), dep.version());
            if (depManifestOpt.isPresent()) {
                var depManifest = depManifestOpt.unwrap();
                graph.put(depArtifact, new SliceEntry(depArtifact, depManifest, SliceConfig.defaultConfig(), true));
                resolveExternalDependencies(depManifest, graph);
            } else {
                graph.put(depArtifact, new SliceEntry(depArtifact, null, SliceConfig.defaultConfig(), true));
                getLog().debug("No manifest found for dependency: " + depArtifact);
            }
        }
    }

    private SliceConfig loadSliceConfig(SliceManifest manifest) {
        var configFile = manifest.configFile();
        if (configFile == null || configFile.isEmpty()) {
            getLog().info("No config file specified for slice: " + manifest.sliceName() + " - using defaults");
            return SliceConfig.defaultConfig();
        }
        var configPath = classesDirectory.toPath()
                                         .resolve(configFile);
        if (!FileOps.exists(configPath)) {
            getLog().info("Config file not found for slice " + manifest.sliceName() + " (" + configFile
                          + ") - using defaults");
            return SliceConfig.defaultConfig();
        }
        return SliceConfig.load(configPath)
                          .onFailure(cause -> getLog().warn("Failed to load config for slice " + manifest.sliceName()
                                                            + ": " + cause.message() + " - using defaults"))
                          .onSuccess(_ -> getLog().debug("Loaded config for slice: " + manifest.sliceName()))
                          .or(SliceConfig.defaultConfig());
    }

    private Option<SliceManifest> loadManifestFromDependency(String groupArtifact, String version) {
        var parts = groupArtifact.split(":");
        if (parts.length != 2) {
            return Option.none();
        }
        var groupId = parts[0];
        var artifactId = parts[1];
        for (var artifact : project.getArtifacts()) {
            if (artifact.getGroupId()
                        .equals(groupId) &&
            artifact.getArtifactId()
                    .equals(artifactId) &&
            artifact.getVersion()
                    .equals(version)) {
                return loadManifestFromJar(artifact.getFile());
            }
        }
        return Option.none();
    }

    private Option<SliceManifest> loadManifestFromJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return Option.none();
        }
        try (var jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName()
                         .startsWith("META-INF/slice/") &&
                entry.getName()
                     .endsWith(".manifest")) {
                    try (var input = jar.getInputStream(entry)) {
                        var result = SliceManifest.load(input);
                        if (result.isSuccess()) {
                            return Option.some(result.unwrap());
                        }
                    }
                }
            }
        } catch (IOException e) {
            getLog().debug("Failed to read JAR: " + jarFile);
        }
        return Option.none();
    }

    private void topologicalSort(Map<String, SliceEntry> graph) throws MojoExecutionException {
        for (var artifact : graph.keySet()) {
            if (!visited.contains(artifact)) {
                dfs(artifact, graph);
            }
        }
        Collections.reverse(orderedSlices);
    }

    private void dfs(String artifact, Map<String, SliceEntry> graph)
    throws MojoExecutionException {
        if (inStack.contains(artifact)) {
            throw new MojoExecutionException("Circular dependency detected: " + artifact);
        }
        if (visited.contains(artifact)) {
            return;
        }
        inStack.add(artifact);
        var entry = graph.get(artifact);
        if (entry != null && entry.manifest() != null) {
            for (var dep : entry.manifest()
                                .dependencies()) {
                String depArtifact;
                if (dep.artifact() != null && !dep.artifact()
                                                  .isEmpty()) {
                    // Dependency with artifact coordinates
                    if ("UNRESOLVED".equals(dep.version())) {
                        // Find matching resolved key in graph
                        var artifactPrefix = dep.artifact() + ":";
                        depArtifact = graph.keySet()
                                           .stream()
                                           .filter(key -> key.startsWith(artifactPrefix))
                                           .findFirst()
                                           .orElse(null);
                        if (depArtifact == null) {
                            continue;
                        }
                    } else {
                        depArtifact = dep.artifact() + ":" + dep.version();
                    }
                } else {
                    // Local dependency: look up in interfaceToArtifact map
                    depArtifact = interfaceToArtifact.get(dep.interfaceQualifiedName());
                    if (depArtifact == null) {
                        // Not a slice, skip (e.g., utility class)
                        continue;
                    }
                }
                if (graph.containsKey(depArtifact)) {
                    dfs(depArtifact, graph);
                }
            }
        }
        inStack.remove(artifact);
        visited.add(artifact);
        if (entry != null) {
            orderedSlices.add(entry);
        }
    }

    private void generateBlueprint() throws MojoExecutionException {
        var id = blueprintId != null
                 ? blueprintId
                 : project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
        var sb = new StringBuilder();
        sb.append("# Generated by jbct:generate-blueprint\n");
        sb.append("# Regenerate with: mvn jbct:generate-blueprint\n\n");
        sb.append("id = \"")
          .append(id)
          .append("\"\n");
        sb.append("version = \"")
          .append(project.getVersion())
          .append("\"\n\n");
        for (var entry : orderedSlices) {
            sb.append("[[slices]]\n");
            sb.append("artifact = \"")
              .append(entry.artifact())
              .append("\"\n");
            sb.append("instances = ")
              .append(entry.config()
                           .blueprint()
                           .instances())
              .append("\n");
            if (entry.isDependency()) {
                sb.append("# transitive dependency\n");
            }
            sb.append("\n");
        }
        var writeResult = FileOps.createDirectories(blueprintFile.toPath().getParent())
                                   .flatMap(_ -> FileOps.writeString(blueprintFile.toPath(), sb.toString()));
        if (writeResult.isFailure()) {
            throw new MojoExecutionException("Failed to write blueprint: " + blueprintFile);
        }
    }

    // --- Blueprint JAR packaging ---

    private static final String CLASSIFIER = "blueprint";
    private static final String BLUEPRINT_TOML = "blueprint.toml";
    private static final String RESOURCES_TOML = "resources.toml";

    private void packageBlueprintJar() throws MojoExecutionException {
        var id = readBlueprintId();
        var jarFile = buildJarFile();

        createBlueprintJar(jarFile, id);
        attachArtifact(jarFile);

        getLog().info("Created blueprint JAR: " + jarFile.getName());
    }

    private String readBlueprintId() throws MojoExecutionException {
        var defaultId = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
        var result = FileOps.readString(blueprintFile.toPath());
        if (result.isFailure()) {
            throw new MojoExecutionException("Failed to read blueprint.toml");
        }
        for (var line : result.unwrap().split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("id = \"") && trimmed.endsWith("\"")) {
                return trimmed.substring(6, trimmed.length() - 1);
            }
        }
        return defaultId;
    }

    private File buildJarFile() {
        var jarName = project.getArtifactId() + "-" + project.getVersion() + "-" + CLASSIFIER + ".jar";
        return new File(outputDirectory, jarName);
    }

    private void createBlueprintJar(File jarFile, String id) throws MojoExecutionException {
        try {
            var archiver = new JarArchiver();
            archiver.setDestFile(jarFile);

            archiver.addFile(blueprintFile, "META-INF/" + BLUEPRINT_TOML);
            addOptionalResourcesToml(archiver);
            addOptionalSchemaFiles(archiver);

            var mavenArchiver = new MavenArchiver();
            mavenArchiver.setArchiver(archiver);
            mavenArchiver.setOutputFile(jarFile);

            var config = new MavenArchiveConfiguration();
            config.addManifestEntry("Blueprint-Id", id);
            config.addManifestEntry("Blueprint-Version", project.getVersion());

            mavenArchiver.createArchive(null, project, config);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to create blueprint JAR", e);
        }
    }

    private void addOptionalResourcesToml(JarArchiver archiver) {
        if (resourcesTomlFile.exists()) {
            archiver.addFile(resourcesTomlFile, "META-INF/" + RESOURCES_TOML);
            getLog().debug("Added resources.toml to blueprint JAR");
        }
    }

    private void addOptionalSchemaFiles(JarArchiver archiver) throws MojoExecutionException {
        var schemaPath = schemaDirectory.toPath();
        if (!FileOps.exists(schemaPath) || !FileOps.isDirectory(schemaPath)) {
            return;
        }
        var sqlFiles = FileOps.walk(schemaPath, p -> FileOps.isRegularFile(p) && p.toString().endsWith(".sql"));
        if (sqlFiles.isFailure()) {
            throw new MojoExecutionException("Failed to scan schema directory: " + schemaDirectory);
        }
        sqlFiles.unwrap().forEach(file -> addSchemaFile(archiver, schemaPath, file));
    }

    private void addSchemaFile(JarArchiver archiver, Path schemaRoot, Path sqlFile) {
        var relativePath = schemaRoot.relativize(sqlFile).toString().replace('\\', '/');
        archiver.addFile(sqlFile.toFile(), "schema/" + relativePath);
        getLog().debug("Added schema file: " + relativePath);
    }

    private void attachArtifact(File jarFile) {
        projectHelper.attachArtifact(project, "jar", CLASSIFIER, jarFile);
        getLog().debug("Attached blueprint artifact with classifier: " + CLASSIFIER);
    }

    private record SliceEntry(String artifact, SliceManifest manifest, SliceConfig config, boolean isDependency) {}

    private record DependencyManifest(String artifact, SliceManifest manifest) {}
}
