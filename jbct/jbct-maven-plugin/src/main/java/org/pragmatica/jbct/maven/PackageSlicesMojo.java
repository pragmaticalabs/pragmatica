package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.slice.SliceManifest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.ClassElement;
import java.lang.classfile.MethodElement;
import java.lang.classfile.CodeElement;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.instruction.ConstantInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final String SLICE_MANIFEST_DIR = "META-INF/slice/";

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
            getLog().info("Skipping slice packaging");
            return;
        }
        var manifestDir = new File(classesDirectory, "META-INF/slice");
        if (!manifestDir.exists() || !manifestDir.isDirectory()) {
            getLog().info("No slice manifests found in " + manifestDir);
            return;
        }
        var manifestFiles = manifestDir.listFiles((dir, name) -> name.endsWith(".manifest"));
        if (manifestFiles == null || manifestFiles.length == 0) {
            getLog().info("No .manifest files found");
            return;
        }
        getLog().info("Found " + manifestFiles.length + " slice manifest(s)");
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
        getLog().info("Processing slice: " + manifest.sliceName());
        // Classify dependencies
        var classification = classifyDependencies(manifest);
        // Create Impl JAR (fat JAR with dependencies file and manifest entries)
        createImplJar(manifest, classification);
        // Generate POM for impl artifact
        generatePom(manifest);
    }

    private DependencyClassification classifyDependencies(SliceManifest manifest) {
        var sharedDeps = new ArrayList<ArtifactInfo>();
        var infraDeps = new ArrayList<ArtifactInfo>();
        var sliceDeps = new ArrayList<ArtifactInfo>();
        var externalDeps = new ArrayList<Artifact>();
        // Collect direct dependency keys for filtering transitives
        var directDependencyKeys = collectDirectDependencyKeys();
        for (var artifact : project.getArtifacts()) {
            var artifactId = artifact.getArtifactId();
            var scope = artifact.getScope();
            // Skip Aether runtime libs and pragmatica-lite - always provided by platform
            if (isAetherRuntime(artifact)) {
                continue;
            }
            // Skip transitives of provided dependencies (only include direct deps in dependencies file)
            var key = artifact.getGroupId() + ":" + artifact.getArtifactId();
            var isDirectDependency = directDependencyKeys.contains(key);
            if (artifactId.startsWith("infra-") && isDirectDependency) {
                // Infrastructure dependencies (direct only)
                infraDeps.add(toArtifactInfo(artifact));
            } else if (isSliceDependency(artifact) && isDirectDependency) {
                // Slice dependencies (direct only)
                // Read actual artifact names from manifest (not Maven artifact ID)
                sliceDeps.add(toSliceArtifactInfo(artifact));
            } else if ("provided".equals(scope) && isDirectDependency) {
                // Shared dependencies (provided scope, non-infra, direct only)
                sharedDeps.add(toArtifactInfo(artifact));
            } else if ("compile".equals(scope) || "runtime".equals(scope)) {
                // External libs - bundle into fat JAR (includes transitives)
                externalDeps.add(artifact);
            }
        }
        // Add same-module slice dependencies from manifest
        addLocalSliceDependencies(manifest, sliceDeps);
        return new DependencyClassification(sharedDeps, infraDeps, sliceDeps, externalDeps);
    }

    private void addLocalSliceDependencies(SliceManifest manifest, List<ArtifactInfo> sliceDeps) {
        // Check manifest dependencies for local slices (same module)
        for (var dep : manifest.dependencies()) {
            // Local slice dependencies have artifact coordinates but may be UNRESOLVED
            if (dep.artifact() == null || dep.artifact()
                                             .isEmpty()) {
                continue;
            }
            // Check if this is a local slice (same groupId and base artifactId)
            var depArtifact = dep.artifact();
            if (depArtifact.startsWith(project.getGroupId() + ":" + project.getArtifactId() + "-")) {
                // Extract slice artifact ID and create version range
                var version = "^" + project.getVersion();
                sliceDeps.add(new ArtifactInfo(project.getGroupId(),
                                               depArtifact.substring(project.getGroupId()
                                                                            .length() + 1),
                                               version));
                getLog().debug("Added local slice dependency: " + depArtifact + ":" + version);
            }
        }
    }

    private java.util.Set<String> collectDirectDependencyKeys() {
        var keys = new java.util.HashSet<String>();
        for (var dep : project.getDependencies()) {
            keys.add(dep.getGroupId() + ":" + dep.getArtifactId());
        }
        return keys;
    }

    private boolean isAetherRuntime(Artifact artifact) {
        var groupId = artifact.getGroupId();
        var artifactId = artifact.getArtifactId();
        // Skip runtime libraries AND compile-only tools (slice-processor)
        // Infrastructure (infra-*) and shared libs (core) should go in dependency file
        if ("org.pragmatica-lite.aether".equals(groupId)) {
            return artifactId.equals("slice-annotations") || artifactId.equals("slice-api");
        }
        // Skip slice-processor (compile-only tool)
        return "org.pragmatica-lite".equals(groupId) && artifactId.equals("slice-processor");
    }

    private boolean isSliceDependency(Artifact artifact) {
        var file = artifact.getFile();
        if (file == null || !file.exists() || !file.getName()
                                                   .endsWith(".jar")) {
            return false;
        }
        try (var jar = new JarFile(file)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName()
                         .startsWith(SLICE_MANIFEST_DIR) && entry.getName()
                                                                 .endsWith(".manifest")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            getLog().debug("Could not read JAR: " + file + " - " + e.getMessage());
            return false;
        }
    }

    private java.util.Optional<java.util.Properties> readFirstSliceManifest(Artifact artifact) {
        var file = artifact.getFile();
        if (file == null || !file.exists() || !file.getName()
                                                   .endsWith(".jar")) {
            return java.util.Optional.empty();
        }
        try (var jar = new JarFile(file)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName()
                         .startsWith(SLICE_MANIFEST_DIR) && entry.getName()
                                                                 .endsWith(".manifest")) {
                    var props = new java.util.Properties();
                    try (var stream = jar.getInputStream(entry)) {
                        props.load(stream);
                    }
                    return java.util.Optional.of(props);
                }
            }
            return java.util.Optional.empty();
        } catch (IOException e) {
            getLog().debug("Could not read JAR: " + file + " - " + e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private ArtifactInfo toArtifactInfo(Artifact artifact) {
        return new ArtifactInfo(artifact.getGroupId(), artifact.getArtifactId(), toSemverRange(artifact.getVersion()));
    }

    private ArtifactInfo toSliceArtifactInfo(Artifact artifact) {
        // Read slice artifact from manifest (has correct naming: groupId:artifactId-sliceName)
        return readFirstSliceManifest(artifact)
                   .flatMap(props -> {
                                var sliceArtifactId = props.getProperty("slice.artifactId");
                                var baseArtifact = props.getProperty("base.artifact");
                                if (sliceArtifactId != null && baseArtifact != null && baseArtifact.contains(":")) {
                                    var groupId = baseArtifact.split(":")[0];
                                    return java.util.Optional.of(new ArtifactInfo(groupId,
                                                                                  sliceArtifactId,
                                                                                  toSemverRange(artifact.getVersion())));
                                }
                                return java.util.Optional.<ArtifactInfo>empty();
                            })
                   // Fallback to Maven artifact
                   .orElseGet(() -> toArtifactInfo(artifact));
    }

    private String toSemverRange(String version) {
        // Convert exact version to semver range: 1.0.0 -> ^1.0.0
        if (version.startsWith("^") || version.startsWith("~")) {
            return version;
        }
        return "^" + version;
    }

    private void createImplJar(SliceManifest manifest, DependencyClassification classification)
    throws MojoExecutionException {
        var jarName = manifest.implArtifactId() + "-" + project.getVersion() + ".jar";
        var jarFile = new File(outputDirectory, jarName);
        try{
            var archiver = new JarArchiver();
            archiver.setDestFile(jarFile);
            // Generate dependency file content
            var depsContent = generateDependencyFile(manifest, classification);
            // Build version map from dependency file for bytecode transformation
            var versionMap = buildVersionMap(depsContent);
            // Add impl classes (includes request/response types) with bytecode transformation
            for (var className : manifest.allImplClasses()) {
                addClassFiles(archiver, className, versionMap);
            }
            // Add application shared code
            addSharedCode(archiver, manifest);
            // Bundle external libs into fat JAR
            bundleExternalLibs(archiver, classification.externalDeps());
            // Add dependency file
            addDependencyFile(archiver, manifest, depsContent);
            // Add filtered service file for SliceRouterFactory
            addServiceFile(archiver, manifest);
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
                getLog().debug("Could not scan slice subpackages: " + e.getMessage());
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
            getLog().debug("Could not read directory: " + dir + " - " + e.getMessage());
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
                        tempFile.toFile()
                                .deleteOnExit();
                        Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        archiver.addFile(tempFile.toFile(), entryName);
                    }
                }
            } catch (IOException e) {
                getLog().warn("Could not bundle library: " + file.getName() + " - " + e.getMessage());
            }
        }
    }

    private String generateDependencyFile(SliceManifest manifest, DependencyClassification classification) {
        var sb = new StringBuilder();
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

    private void addServiceFile(JarArchiver archiver, SliceManifest manifest)
    throws MojoExecutionException {
        var serviceFile = new File(classesDirectory,
            "META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory");
        if (!serviceFile.exists()) {
            return;
        }
        try {
            var routesClass = manifest.slicePackage() + "." + manifest.sliceName() + "Routes";
            var lines = Files.readAllLines(serviceFile.toPath());
            var filteredLines = lines.stream()
                                     .filter(line -> line.trim().equals(routesClass))
                                     .toList();
            if (!filteredLines.isEmpty()) {
                var tempService = Files.createTempFile("service-", ".txt");
                Files.writeString(tempService, String.join("\n", filteredLines));
                archiver.addFile(tempService.toFile(),
                    "META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory");
                getLog().debug("Added service file entry for: " + routesClass);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to add service file", e);
        }
    }

    /**
     * Builds artifact → version mapping from dependency file.
     * Maps "groupId:artifactId" → "1.0.0" (strips semver range prefix)
     */
    private Map<String, String> buildVersionMap(String depsContent) {
        var map = new HashMap<String, String>();
        if (depsContent == null || depsContent.isEmpty()) {
            return map;
        }
        var lines = depsContent.split("\n");
        boolean inSlicesSection = false;
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.equals("[slices]")) {
                inSlicesSection = true;
                continue;
            }
            if (trimmed.startsWith("[")) {
                inSlicesSection = false;
            }
            if (inSlicesSection && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                // Parse: org.example:artifact-name:^1.0.0
                var parts = trimmed.split(":");
                if (parts.length == 3) {
                    var artifact = parts[0] + ":" + parts[1];
                    var version = stripSemverPrefix(parts[2]);
                    map.put(artifact, version);
                }
            }
        }
        return map;
    }

    /**
     * Strip semver range prefix (^, ~) to get actual version.
     * ^1.0.0 → 1.0.0, ~2.1.0 → 2.1.0, 1.0.0 → 1.0.0
     */
    private String stripSemverPrefix(String version) {
        if (version.startsWith("^") || version.startsWith("~")) {
            return version.substring(1);
        }
        return version;
    }

    /**
     * Transforms factory .class file to replace UNRESOLVED version strings in constant pool.
     * Uses JEP 484 Class-File API for bytecode manipulation.
     */
    private byte[] transformFactoryBytecode(File classFile, Map<String, String> versionMap)
    throws IOException {
        var originalBytes = Files.readAllBytes(classFile.toPath());
        if (versionMap.isEmpty()) {
            return originalBytes;
        }
        var cf = ClassFile.of();
        var classModel = cf.parse(originalBytes);
        return cf.transformClass(classModel, (builder, element) -> transformClassElement(builder, element, versionMap));
    }

    private void transformClassElement(ClassBuilder builder, ClassElement element, Map<String, String> versionMap) {
        if (element instanceof MethodModel methodModel) {
            builder.transformMethod(methodModel,
                                    (methodBuilder, methodElement) -> transformMethodElement(methodBuilder,
                                                                                             methodElement,
                                                                                             versionMap));
        } else {
            builder.with(element);
        }
    }

    private void transformMethodElement(MethodBuilder builder, MethodElement element, Map<String, String> versionMap) {
        if (element instanceof CodeModel codeModel) {
            builder.transformCode(codeModel,
                                  (codeBuilder, codeElement) -> transformCodeElement(codeBuilder,
                                                                                     codeElement,
                                                                                     versionMap));
        } else {
            builder.with(element);
        }
    }

    private void transformCodeElement(CodeBuilder builder, CodeElement element, Map<String, String> versionMap) {
        if (element instanceof ConstantInstruction.LoadConstantInstruction ldc && ldc.constantValue() instanceof String str) {
            replaceUnresolvedConstant(builder, element, str, versionMap);
        } else {
            builder.with(element);
        }
    }

    private void replaceUnresolvedConstant(CodeBuilder builder,
                                           CodeElement element,
                                           String str,
                                           Map<String, String> versionMap) {
        if (str.contains(":UNRESOLVED")) {
            var lastColonIdx = str.lastIndexOf(":UNRESOLVED");
            if (lastColonIdx > 0) {
                var artifact = str.substring(0, lastColonIdx);
                var version = versionMap.get(artifact);
                if (version != null) {
                    builder.loadConstant(artifact + ":" + version);
                    getLog().debug("Transformed: " + str + " → " + artifact + ":" + version);
                    return;
                }
            }
        }
        builder.with(element);
    }

    private void addClassFiles(JarArchiver archiver, String className, Map<String, String> versionMap)
    throws MojoExecutionException {
        var classesPath = classesDirectory.toPath();
        var paths = SliceManifest.classToPathsWithInner(className, classesPath);
        try{
            for (var relativePath : paths) {
                var classFile = new File(classesDirectory, relativePath);
                if (classFile.exists()) {
                    // Transform factory classes with UNRESOLVED versions
                    if (className.endsWith("Factory") && !versionMap.isEmpty() &&
                    relativePath.equals(className.replace('.', '/') + ".class")) {
                        var transformedBytes = transformFactoryBytecode(classFile, versionMap);
                        // Write transformed bytecode to temp file for archiving
                        var tempClass = Files.createTempFile("factory-", ".class");
                        Files.write(tempClass, transformedBytes);
                        archiver.addFile(tempClass.toFile(), relativePath);
                        getLog().info("Transformed bytecode: " + className);
                    } else {
                        // Add non-factory classes and inner classes as-is
                        archiver.addFile(classFile, relativePath);
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to transform factory bytecode", e);
        }
    }

    private void generatePom(SliceManifest manifest) throws MojoExecutionException {
        var artifactId = manifest.implArtifactId();
        var pomFile = new File(outputDirectory, artifactId + "-" + project.getVersion() + ".pom");
        try (var writer = new FileWriter(pomFile)) {
            writer.write(generatePomContent(manifest));
            getLog().debug("Generated POM: " + pomFile.getName());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate POM", e);
        }
    }

    private String generatePomContent(SliceManifest manifest) {
        var artifactId = manifest.implArtifactId();
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
          .append(manifest.sliceName())
          .append("</name>\n");
        sb.append("    <description>Generated slice artifact</description>\n");
        sb.append("\n");
        sb.append("    <dependencies>\n");
        // Slice depends on pragmatica-lite core
        sb.append("        <dependency>\n");
        sb.append("            <groupId>org.pragmatica-lite</groupId>\n");
        sb.append("            <artifactId>core</artifactId>\n");
        sb.append("            <version>0.11.2</version>\n");
        sb.append("        </dependency>\n");
        // And slice-api for runtime
        sb.append("        <dependency>\n");
        sb.append("            <groupId>org.pragmatica.aether</groupId>\n");
        sb.append("            <artifactId>slice-api</artifactId>\n");
        sb.append("            <version>0.1.0</version>\n");
        sb.append("        </dependency>\n");
        sb.append("    </dependencies>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    private record ArtifactInfo(String groupId, String artifactId, String version) {}

    private record DependencyClassification(List<ArtifactInfo> sharedDeps,
                                            List<ArtifactInfo> infraDeps,
                                            List<ArtifactInfo> sliceDeps,
                                            List<Artifact> externalDeps) {
        DependencyClassification {
            sharedDeps = List.copyOf(sharedDeps);
            infraDeps = List.copyOf(infraDeps);
            sliceDeps = List.copyOf(sliceDeps);
            externalDeps = List.copyOf(externalDeps);
        }
    }
}
