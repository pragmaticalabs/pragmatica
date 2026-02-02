package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.slice.SliceConfig;
import org.pragmatica.jbct.slice.SliceManifest;
import org.pragmatica.jbct.slice.SliceManifest.SliceDependency;
import org.pragmatica.lang.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Generates Blueprint.toml from slice manifests and their transitive dependencies.
 * The blueprint lists all slices in topological order (dependencies before dependents).
 */
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

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

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
        if (localManifests.isEmpty()) {
            getLog().info("No slice manifests found - skipping blueprint generation");
            return;
        }
        var graph = buildDependencyGraph(localManifests);
        topologicalSort(graph);
        generateBlueprint();
        getLog().info("Generated blueprint: " + blueprintFile);
    }

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

    private Map<String, SliceEntry> buildDependencyGraph(List<SliceManifest> localManifests)
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
        // Second pass: resolve external dependencies from JAR files
        for (var manifest : localManifests) {
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
                getLog().warn("Skipping UNRESOLVED dependency: " + dep.artifact()
                              + " - not found in local graph");
                continue;
            }
            loadManifestFromDependency(dep.artifact(),
                                       dep.version()).onPresent(depManifest -> {
                                                                    // External dependencies use default config
            var entry = new SliceEntry(depArtifact,
                                       depManifest,
                                       SliceConfig.defaults(),
                                       true);
                                                                    graph.put(depArtifact, entry);
                                                                    try{
                                                                        resolveExternalDependencies(depManifest, graph);
                                                                    } catch (MojoExecutionException e) {
                                                                        throw new RuntimeException(e);
                                                                    }
                                                                })
                                      .onEmpty(() -> {
                                                   graph.put(depArtifact,
                                                             new SliceEntry(depArtifact,
                                                                            null,
                                                                            SliceConfig.defaults(),
                                                                            true));
                                                   getLog().debug("No manifest found for dependency: " + depArtifact);
                                               });
        }
    }

    private SliceConfig loadSliceConfig(SliceManifest manifest) {
        var configFile = manifest.configFile();
        if (configFile == null || configFile.isEmpty()) {
            getLog().info("No config file specified for slice: " + manifest.sliceName() + " - using defaults");
            return SliceConfig.defaults();
        }
        var configPath = classesDirectory.toPath()
                                         .resolve(configFile);
        if (!Files.exists(configPath)) {
            getLog().info("Config file not found for slice " + manifest.sliceName() + " (" + configFile
                          + ") - using defaults");
            return SliceConfig.defaults();
        }
        return SliceConfig.load(configPath)
                          .onFailure(cause -> getLog().warn("Failed to load config for slice " + manifest.sliceName()
                                                            + ": " + cause.message() + " - using defaults"))
                          .onSuccess(_ -> getLog().debug("Loaded config for slice: " + manifest.sliceName()))
                          .or(SliceConfig.defaults());
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
                            continue; // Skip unresolved deps not in graph
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
        try{
            Files.createDirectories(blueprintFile.toPath()
                                                 .getParent());
            Files.writeString(blueprintFile.toPath(), sb.toString());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write blueprint", e);
        }
    }

    private record SliceEntry(String artifact, SliceManifest manifest, SliceConfig config, boolean isDependency) {}
}
