package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.slice.SliceManifest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.jar.JarArchiver;

/**
 * Packages slices into separate JAR artifacts.
 * Reads slice manifests from META-INF/slice/*.manifest and creates:
 * - {module}-{slice}-api.jar - API interface only
 * - {module}-{slice}.jar - Implementation + factory + request/response types (fat JAR)
 *
 * <p>The impl JAR includes:
 * <ul>
 *   <li>META-INF/dependencies/{FactoryClass} - runtime dependency file</li>
 *   <li>META-INF/MANIFEST.MF with Slice-Artifact and Slice-Class entries</li>
 *   <li>Bundled external libs (compile scope, non-slice, non-infra, non-provided)</li>
 *   <li>Application shared code (sibling shared package or slice subpackages)</li>
 * </ul>
 */
@Mojo(name = "package-slices",
 defaultPhase = LifecyclePhase.PACKAGE,
 requiresDependencyResolution = ResolutionScope.COMPILE)
public class PackageSlicesMojo extends AbstractMojo {
    private static final String SLICE_API_PROPERTIES = "META-INF/slice-api.properties";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog()
                  .info("Skipping slice packaging");
            return;
        }
        var manifestDir = new File(classesDirectory, "META-INF/slice");
        if (!manifestDir.exists() || !manifestDir.isDirectory()) {
            getLog()
                  .info("No slice manifests found in " + manifestDir);
            return;
        }
        var manifestFiles = manifestDir.listFiles((dir, name) -> name.endsWith(".manifest"));
        if (manifestFiles == null || manifestFiles.length == 0) {
            getLog()
                  .info("No .manifest files found");
            return;
        }
        getLog()
              .info("Found " + manifestFiles.length + " slice manifest(s)");
        for (var manifestFile : manifestFiles) {
            processManifest(manifestFile.toPath());
        }
    }

    private void processManifest(Path manifestPath) throws MojoExecutionException {
        var result = SliceManifest.load(manifestPath);
        if (result.isFailure()) {
            throw new MojoExecutionException("Failed to load manifest: " + manifestPath);
        }
        var manifest = result.unwrap();
        getLog()
              .info("Processing slice: " + manifest.sliceName());
        // Classify dependencies
        var classification = classifyDependencies(manifest);
        // Create API JAR
        createApiJar(manifest);
        // Create Impl JAR (fat JAR with dependencies file and manifest entries)
        createImplJar(manifest, classification);
        // Generate POMs
        generatePom(manifest, true);  // API POM
        generatePom(manifest, false); // Impl POM
    }

    private DependencyClassification classifyDependencies(SliceManifest manifest) {
        var apiDeps = new ArrayList<ArtifactInfo>();
        var sharedDeps = new ArrayList<ArtifactInfo>();
        var infraDeps = new ArrayList<ArtifactInfo>();
        var sliceDeps = new ArrayList<ArtifactInfo>();
        var externalDeps = new ArrayList<Artifact>();
        for (var artifact : project.getArtifacts()) {
            var artifactId = artifact.getArtifactId();
            var scope = artifact.getScope();
            if (artifactId.startsWith("infra-")) {
                // Infrastructure dependencies
                infraDeps.add(toArtifactInfo(artifact));
            } else if (isSliceDependency(artifact)) {
                // Slice dependencies - add both API and slice entry
                apiDeps.add(toApiArtifactInfo(artifact));
                sliceDeps.add(toArtifactInfo(artifact));
            } else if ("provided".equals(scope)) {
                // Shared dependencies (provided scope, non-infra)
                sharedDeps.add(toArtifactInfo(artifact));
            } else if ("compile".equals(scope) || "runtime".equals(scope)) {
                // External libs - bundle into fat JAR
                externalDeps.add(artifact);
            }
        }
        return new DependencyClassification(apiDeps, sharedDeps, infraDeps, sliceDeps, externalDeps);
    }

    private boolean isSliceDependency(Artifact artifact) {
        var file = artifact.getFile();
        if (file == null || !file.exists() || !file.getName()
                                                   .endsWith(".jar")) {
            return false;
        }
        try (var jar = new JarFile(file)) {
            return jar.getEntry(SLICE_API_PROPERTIES) != null;
        } catch (IOException e) {
            getLog()
                  .debug("Could not read JAR: " + file + " - " + e.getMessage());
            return false;
        }
    }

    private ArtifactInfo toArtifactInfo(Artifact artifact) {
        return new ArtifactInfo(artifact.getGroupId(), artifact.getArtifactId(), toSemverRange(artifact.getVersion()));
    }

    private ArtifactInfo toApiArtifactInfo(Artifact artifact) {
        // Convert slice artifact to its API artifact (add -api suffix)
        var apiArtifactId = artifact.getArtifactId() + "-api";
        return new ArtifactInfo(artifact.getGroupId(), apiArtifactId, toSemverRange(artifact.getVersion()));
    }

    private String toSemverRange(String version) {
        // Convert exact version to semver range: 1.0.0 -> ^1.0.0
        if (version.startsWith("^") || version.startsWith("~")) {
            return version;
        }
        return "^" + version;
    }

    private void createApiJar(SliceManifest manifest) throws MojoExecutionException {
        var jarName = manifest.apiArtifactId() + "-" + project.getVersion() + ".jar";
        var jarFile = new File(outputDirectory, jarName);
        try{
            var archiver = new JarArchiver();
            archiver.setDestFile(jarFile);
            // Add API classes
            for (var className : manifest.allApiClasses()) {
                addClassFiles(archiver, className);
            }
            var mavenArchiver = new MavenArchiver();
            mavenArchiver.setArchiver(archiver);
            mavenArchiver.setOutputFile(jarFile);
            var config = new MavenArchiveConfiguration();
            mavenArchiver.createArchive(null, project, config);
            getLog()
                  .info("Created API JAR: " + jarFile.getName());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to create API JAR", e);
        }
    }

    private void createImplJar(SliceManifest manifest, DependencyClassification classification)
    throws MojoExecutionException {
        var jarName = manifest.implArtifactId() + "-" + project.getVersion() + ".jar";
        var jarFile = new File(outputDirectory, jarName);
        try{
            var archiver = new JarArchiver();
            archiver.setDestFile(jarFile);
            // Add impl classes (includes request/response types)
            for (var className : manifest.allImplClasses()) {
                addClassFiles(archiver, className);
            }
            // Add application shared code
            addSharedCode(archiver, manifest);
            // Bundle external libs into fat JAR
            bundleExternalLibs(archiver, classification.externalDeps());
            // Generate and add dependency file
            var depsContent = generateDependencyFile(manifest, classification);
            addDependencyFile(archiver, manifest, depsContent);
            var mavenArchiver = new MavenArchiver();
            mavenArchiver.setArchiver(archiver);
            mavenArchiver.setOutputFile(jarFile);
            // Configure manifest entries
            var config = new MavenArchiveConfiguration();
            config.addManifestEntry("Slice-Artifact",
                                    project.getGroupId() + ":" + manifest.implArtifactId() + ":" + project.getVersion());
            config.addManifestEntry("Slice-Class",
                                    manifest.slicePackage() + "." + manifest.sliceName() + "Factory");
            mavenArchiver.createArchive(null, project, config);
            getLog()
                  .info("Created Impl JAR: " + jarFile.getName() + " (fat JAR with " + classification.externalDeps()
                                                                                                     .size()
                        + " bundled libs)");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to create Impl JAR", e);
        }
    }

    private void addSharedCode(JarArchiver archiver, SliceManifest manifest) {
        var slicePackage = manifest.slicePackage();
        if (slicePackage == null || slicePackage.isEmpty()) {
            return;
        }
        var classesPath = classesDirectory.toPath();
        // Find sibling shared package (e.g., org.example.shared for org.example.order)
        var packageParts = slicePackage.split("\\.");
        if (packageParts.length > 1) {
            var parentPackage = String.join("/", java.util.Arrays.copyOf(packageParts, packageParts.length - 1));
            var sharedDir = classesPath.resolve(parentPackage)
                                       .resolve("shared");
            addDirectoryClasses(archiver, sharedDir, classesPath);
        }
        // Find subpackages of slice package (e.g., org.example.order.utils)
        var sliceDir = classesPath.resolve(slicePackage.replace('.', '/'));
        if (Files.isDirectory(sliceDir)) {
            try (var stream = Files.walk(sliceDir)) {
                stream.filter(Files::isDirectory)
                      .filter(dir -> !dir.equals(sliceDir))
                      .forEach(dir -> addDirectoryClasses(archiver, dir, classesPath));
            } catch (IOException e) {
                getLog()
                      .debug("Could not scan slice subpackages: " + e.getMessage());
            }
        }
    }

    private void addDirectoryClasses(JarArchiver archiver, Path dir, Path classesPath) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString()
                                .endsWith(".class"))
                  .forEach(classFile -> {
                               var relativePath = classesPath.relativize(classFile)
                                                             .toString()
                                                             .replace('\\', '/');
                               archiver.addFile(classFile.toFile(),
                                                relativePath);
                           });
        } catch (IOException e) {
            getLog()
                  .debug("Could not read directory: " + dir + " - " + e.getMessage());
        }
    }

    private void bundleExternalLibs(JarArchiver archiver, List<Artifact> externalDeps) {
        for (var artifact : externalDeps) {
            var file = artifact.getFile();
            if (file == null || !file.exists() || !file.getName()
                                                       .endsWith(".jar")) {
                continue;
            }
            try (var jar = new JarFile(file)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();
                    // Skip META-INF files to avoid conflicts (except services)
                    if (entryName.startsWith("META-INF/") &&
                    !entryName.startsWith("META-INF/services/")) {
                        continue;
                    }
                    // Skip directories
                    if (entry.isDirectory()) {
                        continue;
                    }
                    // Skip module-info
                    if (entryName.equals("module-info.class")) {
                        continue;
                    }
                    // Extract and add to archiver
                    try (var input = jar.getInputStream(entry)) {
                        var tempFile = Files.createTempFile("jbct-", ".tmp");
                        tempFile.toFile().deleteOnExit();
                        Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        archiver.addFile(tempFile.toFile(), entryName);
                    }
                }
            } catch (IOException e) {
                getLog()
                      .warn("Could not bundle library: " + file.getName() + " - " + e.getMessage());
            }
        }
    }

    private String generateDependencyFile(SliceManifest manifest, DependencyClassification classification) {
        var sb = new StringBuilder();
        if (!classification.apiDeps()
                           .isEmpty()) {
            sb.append("[api]\n");
            for (var dep : classification.apiDeps()) {
                sb.append(dep.groupId())
                  .append(":")
                  .append(dep.artifactId())
                  .append(":")
                  .append(dep.version())
                  .append("\n");
            }
            sb.append("\n");
        }
        if (!classification.sharedDeps()
                           .isEmpty()) {
            sb.append("[shared]\n");
            for (var dep : classification.sharedDeps()) {
                sb.append(dep.groupId())
                  .append(":")
                  .append(dep.artifactId())
                  .append(":")
                  .append(dep.version())
                  .append("\n");
            }
            sb.append("\n");
        }
        if (!classification.infraDeps()
                           .isEmpty()) {
            sb.append("[infra]\n");
            for (var dep : classification.infraDeps()) {
                sb.append(dep.groupId())
                  .append(":")
                  .append(dep.artifactId())
                  .append(":")
                  .append(dep.version())
                  .append("\n");
            }
            sb.append("\n");
        }
        if (!classification.sliceDeps()
                           .isEmpty()) {
            sb.append("[slices]\n");
            for (var dep : classification.sliceDeps()) {
                sb.append(dep.groupId())
                  .append(":")
                  .append(dep.artifactId())
                  .append(":")
                  .append(dep.version())
                  .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void addDependencyFile(JarArchiver archiver, SliceManifest manifest, String content)
    throws IOException {
        var factoryClassName = manifest.slicePackage() + "." + manifest.sliceName() + "Factory";
        var tempFile = Files.createTempFile("deps-", ".txt");
        Files.writeString(tempFile, content);
        archiver.addFile(tempFile.toFile(), "META-INF/dependencies/" + factoryClassName);
    }

    private void addClassFiles(JarArchiver archiver, String className) {
        var classesPath = classesDirectory.toPath();
        var paths = SliceManifest.classToPathsWithInner(className, classesPath);
        for (var relativePath : paths) {
            var classFile = new File(classesDirectory, relativePath);
            if (classFile.exists()) {
                archiver.addFile(classFile, relativePath);
            }
        }
    }

    private void generatePom(SliceManifest manifest, boolean isApi) throws MojoExecutionException {
        var artifactId = isApi
                         ? manifest.apiArtifactId()
                         : manifest.implArtifactId();
        var pomFile = new File(outputDirectory, artifactId + "-" + project.getVersion() + ".pom");
        try (var writer = new FileWriter(pomFile)) {
            writer.write(generatePomContent(manifest, isApi));
            getLog()
                  .debug("Generated POM: " + pomFile.getName());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate POM", e);
        }
    }

    private String generatePomContent(SliceManifest manifest, boolean isApi) {
        var artifactId = isApi
                         ? manifest.apiArtifactId()
                         : manifest.implArtifactId();
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("\n");
        sb.append("    <groupId>")
          .append(project.getGroupId())
          .append("</groupId>\n");
        sb.append("    <artifactId>")
          .append(artifactId)
          .append("</artifactId>\n");
        sb.append("    <version>")
          .append(project.getVersion())
          .append("</version>\n");
        sb.append("    <packaging>jar</packaging>\n");
        sb.append("\n");
        sb.append("    <name>")
          .append(manifest.sliceName());
        sb.append(isApi
                  ? " API"
                  : "")
          .append("</name>\n");
        sb.append("    <description>Generated slice artifact</description>\n");
        sb.append("\n");
        sb.append("    <dependencies>\n");
        if (isApi) {
            // API only depends on pragmatica-lite core
            sb.append("        <dependency>\n");
            sb.append("            <groupId>org.pragmatica-lite</groupId>\n");
            sb.append("            <artifactId>core</artifactId>\n");
            sb.append("            <version>0.9.10</version>\n");
            sb.append("        </dependency>\n");
        } else {
            // Impl depends on its own API
            sb.append("        <dependency>\n");
            sb.append("            <groupId>")
              .append(project.getGroupId())
              .append("</groupId>\n");
            sb.append("            <artifactId>")
              .append(manifest.apiArtifactId())
              .append("</artifactId>\n");
            sb.append("            <version>")
              .append(project.getVersion())
              .append("</version>\n");
            sb.append("        </dependency>\n");
            // And slice-api for runtime
            sb.append("        <dependency>\n");
            sb.append("            <groupId>org.pragmatica.aether</groupId>\n");
            sb.append("            <artifactId>slice-api</artifactId>\n");
            sb.append("            <version>0.1.0</version>\n");
            sb.append("        </dependency>\n");
        }
        sb.append("    </dependencies>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    private record ArtifactInfo(String groupId, String artifactId, String version) {}

    private record DependencyClassification(List<ArtifactInfo> apiDeps,
                                            List<ArtifactInfo> sharedDeps,
                                            List<ArtifactInfo> infraDeps,
                                            List<ArtifactInfo> sliceDeps,
                                            List<Artifact> externalDeps) {}
}
