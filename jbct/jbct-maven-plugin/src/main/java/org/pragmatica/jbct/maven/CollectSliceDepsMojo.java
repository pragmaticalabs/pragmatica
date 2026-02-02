package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Scans compile dependencies for slice manifests and writes interface-to-artifact mappings.
 * This allows the annotation processor to resolve slice dependency versions.
 *
 * <p>For each dependency JAR containing META-INF/slice/*.manifest files, extracts:
 * <ul>
 *   <li>{@code slice.interface} - the slice interface fully qualified name</li>
 *   <li>{@code slice.artifactId} - the slice artifact ID</li>
 *   <li>{@code base.artifact} - the groupId:baseArtifactId for dependency resolution.
 *       Format: {@code groupId:artifactId} where both components are non-blank Maven coordinates.
 *       Example: {@code org.example:inventory-service}</li>
 * </ul>
 *
 * <p>Writes mappings to slice-deps.properties in format:
 * <pre>
 * # Key: interface qualified name
 * # Value: groupId:artifactId:version
 * org.example.api.InventoryService=org.example:inventory-service:1.0.0
 * </pre>
 */
@Mojo(name = "collect-slice-deps",
 defaultPhase = LifecyclePhase.GENERATE_SOURCES,
 requiresDependencyResolution = ResolutionScope.COMPILE)
public class CollectSliceDepsMojo extends AbstractMojo {
    private static final String MANIFEST_DIR = "META-INF/slice/";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "jbct.outputFile",
    defaultValue = "${project.build.outputDirectory}/slice-deps.properties")
    private File outputFile;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping slice dependency collection");
            return;
        }
        var mappings = new Properties();
        for (var artifact : project.getArtifacts()) {
            var file = artifact.getFile();
            if (file == null || !file.exists() || !file.getName()
                                                       .endsWith(".jar")) {
                continue;
            }
            try{
                extractSliceManifest(file, artifact.getVersion(), mappings);
            } catch (IOException e) {
                getLog().debug("Could not read JAR: " + file + " - " + e.getMessage());
            }
        }
        writeOutput(mappings);
        validateHttpRoutingDependency();
    }

    private void extractSliceManifest(File jarFile, String version, Properties mappings) throws IOException {
        try (var jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var entryName = entry.getName();
                if (!entryName.startsWith(MANIFEST_DIR) || !entryName.endsWith(".manifest")) {
                    continue;
                }
                var props = new Properties();
                try (var stream = jar.getInputStream(entry)) {
                    props.load(stream);
                }
                var sliceInterface = props.getProperty("slice.interface");
                var sliceArtifactId = props.getProperty("slice.artifactId");
                if (sliceInterface == null || sliceArtifactId == null) {
                    getLog().warn("Incomplete slice manifest in " + jarFile.getName() + " (" + entryName
                                  + "): missing slice.interface or slice.artifactId");
                    continue;
                }
                // Extract groupId from base.artifact (groupId:baseArtifactId)
                var baseArtifact = props.getProperty("base.artifact");
                String groupId;
                if (baseArtifact != null && baseArtifact.contains(":")) {
                    var parts = baseArtifact.split(":", -1);
                    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                        || parts[0].contains(" ") || parts[1].contains(" ")
                        || parts[0].contains("/") || parts[1].contains("/")) {
                        getLog().warn("Invalid base.artifact format in " + jarFile.getName() + " (" + entryName
                                      + "): expected 'groupId:artifactId', got '" + baseArtifact + "'");
                        continue;
                    }
                    groupId = parts[0].trim();
                } else {
                    getLog().warn("Missing or invalid base.artifact in " + jarFile.getName() + " (" + entryName + ")");
                    continue;
                }
                // Value: groupId:artifactId:version
                var value = groupId + ":" + sliceArtifactId + ":" + version;
                mappings.setProperty(sliceInterface, value);
                getLog().debug("Found slice: " + sliceInterface + " -> " + value);
            }
        }
    }

    private void writeOutput(Properties mappings) throws MojoExecutionException {
        var parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (var writer = new FileWriter(outputFile)) {
            mappings.store(writer,
                           "Generated by jbct:collect-slice-deps\n# Key: API interface FQN\n# Value: groupId:artifactId:version");
            getLog().info("Wrote " + mappings.size() + " slice dependencies to " + outputFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write slice dependencies", e);
        }
    }

    private void validateHttpRoutingDependency() throws MojoExecutionException {
        // Scan for routes.toml files in configured resource directories
        var resourceDirs = project.getResources()
                                  .stream()
                                  .map(resource -> new File(resource.getDirectory()))
                                  .filter(File::exists)
                                  .toList();
        if (resourceDirs.isEmpty()) {
            return;
        }
        var routesTomlFiles = resourceDirs.stream()
                                          .flatMap(dir -> findRoutesTomlFiles(dir.toPath()).stream())
                                          .toList();
        if (routesTomlFiles.isEmpty()) {
            return;
        }
        // Check if http-routing-adapter dependency exists
        var hasRoutingAdapter = project.getArtifacts()
                                       .stream()
                                       .anyMatch(artifact -> "org.pragmatica-lite.aether".equals(artifact.getGroupId()) &&
        "http-routing-adapter".equals(artifact.getArtifactId()));
        if (!hasRoutingAdapter) {
            var sliceNames = routesTomlFiles.stream()
                                            .map(path -> {
                                                     for (var dir : resourceDirs) {
                                                         if (path.startsWith(dir.toPath())) {
                                                             return dir.toPath()
                                                                       .relativize(path)
                                                                       .toString();
                                                         }
                                                     }
                                                     return path.toString();
                                                 })
                                            .toList();
            var message = String.format("""
                                        HTTP routing configured but dependency missing.
                                        Found routes.toml in: %s

                                        Add to pom.xml:
                                        <dependency>
                                          <groupId>org.pragmatica-lite.aether</groupId>
                                          <artifactId>http-routing-adapter</artifactId>
                                          <version>${aether.version}</version>
                                          <scope>provided</scope>
                                        </dependency>
                                        """,
                                        String.join(", ", sliceNames));
            throw new MojoExecutionException(message);
        }
    }

    private List<Path> findRoutesTomlFiles(Path resourcesDir) {
        var routesTomlFiles = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(resourcesDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.getFileName()
                                     .toString()
                                     .equals("routes.toml"))
                 .forEach(routesTomlFiles::add);
        } catch (IOException e) {
            getLog().warn("Error scanning resources directory: " + e.getMessage());
        }
        return routesTomlFiles;
    }
}
