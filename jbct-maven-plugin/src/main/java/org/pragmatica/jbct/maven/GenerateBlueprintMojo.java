package org.pragmatica.jbct.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.pragmatica.jbct.slice.SliceConfig;
import org.pragmatica.jbct.slice.SliceManifest;
import org.pragmatica.jbct.slice.SliceManifest.SliceDependency;

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
            var artifact = project.getGroupId() + ":"
                          + manifest.implArtifactId() + ":"
                          + project.getVersion();
            var config = loadSliceConfig(manifest);
            var entry = new SliceEntry(artifact, manifest, config, false);
            graph.put(artifact, entry);

            // Map the slice interface to its artifact for internal dependency resolution
            var sliceInterface = manifest.slicePackage() + "." + manifest.sliceName();
            interfaceToArtifact.put(sliceInterface, artifact);

            // Also map the API interface
            if (!manifest.apiClasses().isEmpty()) {
                interfaceToArtifact.put(manifest.apiClasses().getFirst(), artifact);
            }
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
            if (!dep.external()) {
                continue;
            }

            var depArtifact = dep.artifact() + ":" + dep.version();
            if (graph.containsKey(depArtifact)) {
                continue;
            }

            var depManifest = loadManifestFromDependency(dep.artifact(), dep.version());
            if (depManifest != null) {
                // External dependencies use default config
                var entry = new SliceEntry(depArtifact, depManifest, SliceConfig.defaults(), true);
                graph.put(depArtifact, entry);
                resolveExternalDependencies(depManifest, graph);
            } else {
                graph.put(depArtifact, new SliceEntry(depArtifact, null, SliceConfig.defaults(), true));
                getLog().debug("No manifest found for dependency: " + depArtifact);
            }
        }
    }

    private SliceConfig loadSliceConfig(SliceManifest manifest) {
        var configFile = manifest.configFile();
        if (configFile == null || configFile.isEmpty()) {
            getLog().info("No config file specified for slice: " + manifest.sliceName() + " - using defaults");
            return SliceConfig.defaults();
        }

        var configPath = classesDirectory.toPath().resolve(configFile);
        if (!Files.exists(configPath)) {
            getLog().info("Config file not found for slice " + manifest.sliceName()
                          + " (" + configFile + ") - using defaults");
            return SliceConfig.defaults();
        }

        return SliceConfig.load(configPath)
                          .fold(cause -> {
                                    getLog().warn("Failed to load config for slice " + manifest.sliceName()
                                                  + ": " + cause.message() + " - using defaults");
                                    return SliceConfig.defaults();
                                },
                                config -> {
                                    getLog().debug("Loaded config for slice: " + manifest.sliceName());
                                    return config;
                                });
    }

    private SliceManifest loadManifestFromDependency(String groupArtifact, String version) {
        var parts = groupArtifact.split(":");
        if (parts.length != 2) {
            return null;
        }

        var groupId = parts[0];
        var artifactId = parts[1];

        for (var artifact : project.getArtifacts()) {
            if (artifact.getGroupId().equals(groupId) &&
                artifact.getArtifactId().equals(artifactId) &&
                artifact.getVersion().equals(version)) {

                return loadManifestFromJar(artifact.getFile());
            }
        }
        return null;
    }

    private SliceManifest loadManifestFromJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return null;
        }

        try (var jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/slice/") &&
                    entry.getName().endsWith(".manifest")) {
                    try (var input = jar.getInputStream(entry)) {
                        var result = SliceManifest.load(input);
                        if (result.isSuccess()) {
                            return result.unwrap();
                        }
                    }
                }
            }
        } catch (IOException e) {
            getLog().debug("Failed to read JAR: " + jarFile);
        }
        return null;
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
            for (var dep : entry.manifest().dependencies()) {
                String depArtifact;
                if (dep.external()) {
                    // External dependency: use artifact coordinates from manifest
                    depArtifact = dep.artifact() + ":" + dep.version();
                } else {
                    // Internal dependency: look up in interfaceToArtifact map
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
        var id = blueprintId != null ? blueprintId :
                 project.getGroupId() + ":" + project.getArtifactId()
                 + ":" + project.getVersion();

        var sb = new StringBuilder();
        sb.append("# Generated by jbct:generate-blueprint\n");
        sb.append("# Regenerate with: mvn jbct:generate-blueprint\n\n");
        sb.append("id = \"").append(id).append("\"\n\n");

        for (var entry : orderedSlices) {
            sb.append("[[slices]]\n");
            sb.append("artifact = \"").append(entry.artifact()).append("\"\n");
            sb.append("instances = ").append(entry.config().blueprint().instances()).append("\n");

            // Add optional properties if configured
            entry.config().blueprint().timeoutMs()
                 .onPresent(timeout -> sb.append("timeout_ms = ").append(timeout).append("\n"));
            entry.config().blueprint().memoryMb()
                 .onPresent(memory -> sb.append("memory_mb = ").append(memory).append("\n"));
            entry.config().blueprint().loadBalancing()
                 .onPresent(lb -> sb.append("load_balancing = \"").append(lb).append("\"\n"));
            entry.config().blueprint().affinityKey()
                 .onPresent(key -> sb.append("affinity_key = \"").append(key).append("\"\n"));

            if (entry.isDependency()) {
                sb.append("# transitive dependency\n");
            }
            sb.append("\n");
        }

        try {
            Files.createDirectories(blueprintFile.toPath().getParent());
            Files.writeString(blueprintFile.toPath(), sb.toString());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write blueprint", e);
        }
    }

    private record SliceEntry(String artifact, SliceManifest manifest, SliceConfig config, boolean isDependency) {}
}
