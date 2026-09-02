package org.pragmatica.jbct.maven;

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
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.slice.SliceManifest;
import org.pragmatica.lang.Option;

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


/// Packages slices into separate JAR artifacts.
/// Reads slice manifests from META-INF/slice/*.manifest and creates:
/// - {module}-{slice}-api.jar - API interface only
/// - {module}-{slice}.jar - Implementation + factory + request/response types
///   + declared message/event types (fat JAR)
///
///
/// The impl JAR includes:
///
///   - META-INF/dependencies/{FactoryClass} - runtime dependency file
///   - META-INF/MANIFEST.MF with Slice-Artifact and Slice-Class entries
///   - Bundled external libs (compile scope, non-slice, non-infra, non-provided)
///   - Slice subpackages, plus every class of this module referenced — transitively — from the
///     bytecode already in the bundle (reference closure)
///
@Mojo(name = "package-slices", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class PackageSlicesMojo extends AbstractMojo {
    private static final String SLICE_MANIFEST_DIR = "META-INF/slice/";
    private static final String CLASS_SUFFIX = ".class";

    /// Candidate internal names as they appear in a class file's UTF-8 constants: slash-separated
    /// Java identifiers (`org/example/Order`, `org/example/Order$Line`). Deliberately over-matches —
    /// see [#addReferencedClasses].
    private static final Pattern INTERNAL_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+");

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
            if (dep.artifact() == null || dep.artifact().isEmpty()) {
                continue;
            }
            // Check if this is a local slice (same groupId and base artifactId)
            var depArtifact = dep.artifact();

            if (depArtifact.startsWith(project.getGroupId() + ":" + project.getArtifactId() + "-")) {
                // Extract slice artifact ID and create version range
                var version = "^" + project.getVersion();

                sliceDeps.add(new ArtifactInfo(project.getGroupId(),
                                               depArtifact.substring(project.getGroupId().length() + 1),
                                               version));
                getLog().debug("Added local slice dependency: " + depArtifact + ":" + version);
            }
        }
    }

    private Set<String> collectDirectDependencyKeys() {
        var keys = new HashSet<String>();

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

        if (file == null || !file.exists() || !file.getName().endsWith(".jar")) {
            return false;
        }

        try (var jar = new JarFile(file)) {
            var entries = jar.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();

                if (entry.getName().startsWith(SLICE_MANIFEST_DIR) && entry.getName().endsWith(".manifest")) {
                    return true;
                }
            }

            return false;
        } catch (IOException e) {
            getLog().debug("Could not read JAR: " + file + " - " + e.getMessage());

            return false;
        }
    }

    private Option<Properties> readFirstSliceManifest(Artifact artifact) {
        var file = artifact.getFile();

        if (file == null || !file.exists() || !file.getName().endsWith(".jar")) {
            return Option.none();
        }

        try (var jar = new JarFile(file)) {
            var entries = jar.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();

                if (entry.getName().startsWith(SLICE_MANIFEST_DIR) && entry.getName().endsWith(".manifest")) {
                    var props = new Properties();

                    try (var stream = jar.getInputStream(entry)) {
                        props.load(stream);
                    }

                    return Option.some(props);
                }
            }

            return Option.none();
        } catch (IOException e) {
            getLog().debug("Could not read JAR: " + file + " - " + e.getMessage());

            return Option.none();
        }
    }

    private ArtifactInfo toArtifactInfo(Artifact artifact) {
        return new ArtifactInfo(artifact.getGroupId(), artifact.getArtifactId(), toSemverRange(artifact.getVersion()));
    }

    private ArtifactInfo toSliceArtifactInfo(Artifact artifact) {
        // Read slice artifact from manifest (has correct naming: groupId:artifactId-sliceName)
        return readFirstSliceManifest(artifact).flatMap(props -> extractSliceArtifactInfo(props,
                                                                                          artifact.getVersion()))
                                     .or(() -> toArtifactInfo(artifact));
    }

    private Option<ArtifactInfo> extractSliceArtifactInfo(Properties props, String version) {
        return Option.option(props.getProperty("slice.artifactId")).flatMap(sliceArtifactId -> Option.option(props.getProperty("base.artifact"))
                                                                                                     .filter(base -> base.contains(":"))
                                                                                                     .map(base -> new ArtifactInfo(base.split(":") [0],
                                                                                                                                   sliceArtifactId,
                                                                                                                                   toSemverRange(version))));
    }

    private String toSemverRange(String version) {
        // Convert exact version to semver range: 1.0.0 -> ^1.0.0
        if (version.startsWith("^") || version.startsWith("~")) {
            return version;
        }

        return "^" + version;
    }

    private void createImplJar(SliceManifest manifest, DependencyClassification classification) throws MojoExecutionException {
        var jarName = manifest.implArtifactId() + "-" + project.getVersion() + ".jar";
        var jarFile = new File(outputDirectory, jarName);

        try {
            var archiver = new JarArchiver();

            archiver.setDestFile(jarFile);
            // Generate dependency file content
            var depsContent = generateDependencyFile(manifest, classification);
            // Build version map from dependency file for bytecode transformation
            var versionMap = buildVersionMap(depsContent);
            // Add impl classes (includes request/response types) with bytecode transformation
            var bundled = new HashSet<String>();

            addBundleClasses(archiver, manifest, versionMap, bundled);
            // Bundle external libs into fat JAR
            bundleExternalLibs(archiver, classification.externalDeps());
            // Add dependency file
            addDependencyFile(archiver, manifest, depsContent);
            // Add filtered service file for SliceRouterFactory
            addServiceFile(archiver, manifest);
            // Include Properties manifest in per-slice JAR for runtime access
            var manifestFile = new File(classesDirectory, "META-INF/slice/" + manifest.sliceName() + ".manifest");

            if (manifestFile.exists()) {
                archiver.addFile(manifestFile, "META-INF/slice/" + manifest.sliceName() + ".manifest");
            }
            // Include intrinsic resources.toml in per-slice JAR — slice-composite's bottom layer
            // (Batch 1 layered-config refactor). Without this the slice classloader returns null
            // and the slice's intrinsic config (@PgSql/@Http/@Heartbeat sections) is unreachable.
            var resourcesTomlFile = new File(classesDirectory, "resources.toml");

            if (resourcesTomlFile.exists()) {
                archiver.addFile(resourcesTomlFile, "META-INF/resources.toml");
            }

            var mavenArchiver = new MavenArchiver();

            mavenArchiver.setArchiver(archiver);
            mavenArchiver.setOutputFile(jarFile);
            // Read envelope version from Properties manifest
            var envelopeVersion = "1";

            if (manifestFile.exists()) {
                var props = new Properties();

                try (var input = new FileInputStream(manifestFile)) {
                    props.load(input);
                    envelopeVersion = props.getProperty("envelope.version", "1");
                } catch (IOException e) {
                    getLog().debug("Could not read envelope version: " + e.getMessage());
                }
            }
            // Configure manifest entries
            var config = new MavenArchiveConfiguration();

            config.addManifestEntry("Envelope-Version", envelopeVersion);
            config.addManifestEntry("Slice-Artifact",
                                    project.getGroupId() + ":" + manifest.implArtifactId() + ":" + project.getVersion());
            config.addManifestEntry("Slice-Class",
                                    manifest.slicePackage() + "." + manifest.sliceName() + "Factory");
            mavenArchiver.createArchive(null, project, config);
            getLog().info("Created Impl JAR: " + jarFile.getName()
                         + " (fat JAR with " + classification.externalDeps().size()
                         + " bundled libs)");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to create Impl JAR", e);
        }
    }

    /// The complete class set of a slice bundle, in one place so a test and the real packaging run
    /// traverse the SAME path.
    ///
    /// This exists because of a verification gap, not for tidiness. The closure tests previously
    /// re-created this sequence themselves — declared classes, then subpackages, then the reference
    /// closure — and called each step directly. That proved the steps work, and proved nothing about
    /// whether packaging calls them: deleting the closure call from `createImplJar` left every test
    /// green while bundles shipped non-closed again. A mutation probe confirmed it — removing the call
    /// failed no test. Anything that must hold for a real bundle belongs here, and the test must enter
    /// through this method.
    private void addBundleClasses(JarArchiver archiver,
                                  SliceManifest manifest,
                                  Map<String, String> versionMap,
                                  Set<String> bundled) throws MojoExecutionException {
        for (var className : manifest.allImplClasses()) {
            addClassFiles(archiver, className, versionMap, bundled);
        }
        // Slice subpackages
        addSharedCode(archiver, manifest, bundled);
        // Close the bundle under bytecode reference
        addReferencedClasses(archiver, bundled);
    }

    private void addSharedCode(JarArchiver archiver, SliceManifest manifest, Set<String> bundled) {
        var slicePackage = manifest.slicePackage();

        if (slicePackage == null || slicePackage.isEmpty()) {
            return;
        }

        var classesPath = classesDirectory.toPath();
        // Find subpackages of slice package (e.g., org.example.order.utils)
        var sliceDir = classesPath.resolve(slicePackage.replace('.', '/'));

        if (Files.isDirectory(sliceDir)) {
            try (var stream = Files.walk(sliceDir)) {
                stream.filter(Files::isDirectory)
                      .filter(dir -> !dir.equals(sliceDir))
                      .forEach(dir -> addDirectoryClasses(archiver, dir, classesPath, bundled));
            } catch (IOException e) {
                getLog().debug("Could not scan slice subpackages: " + e.getMessage());
            }
        }
    }

    /// Close the bundle under bytecode reference: every class of THIS module reachable from bytecode
    /// already in the bundle ships with it, computed to a fixpoint so a chain of references is
    /// followed all the way down.
    ///
    /// This replaces a declaration-driven guess — manifest classes plus a *sibling* `shared` package
    /// found by dropping exactly one segment off the slice package. That guess resolved to a real
    /// directory only for a flat two-level layout; under a deeper package telescope
    /// (`system.subsystem.workflow.usecase`) it pointed at a directory that does not exist, and every
    /// type reached only from bytecode was silently omitted — non-closed bundles that either fail
    /// activation with `ClassNotFoundException` (missing type reachable from a factory signature) or
    /// survive activation and fail on first use with `NoClassDefFoundError`.
    ///
    /// Candidate names come from a raw scan of the class bytes and are INTERSECTED with the internal
    /// names that actually exist under `classesDirectory`. That intersection is what makes a parser
    /// unnecessary: the scan over-matches freely (string literals, fragments of unrelated constants),
    /// and a candidate that is not a real compiled class of this module simply finds no file and is
    /// dropped. Only this module's own output is pulled in — dependencies stay dependencies.
    private void addReferencedClasses(JarArchiver archiver, Set<String> bundled) {
        var classesPath = classesDirectory.toPath();
        var available = compiledClassNames(classesPath);
        var pending = new ArrayDeque<>(bundled);

        while (!pending.isEmpty()) {
            var current = pending.poll();

            for (var reference : scanReferences(classesPath.resolve(current + CLASS_SUFFIX))) {
                if (!available.contains(reference) || bundled.contains(reference)) {
                    continue;
                }
                // A referenced outer class is useless without its nested classes
                for (var name : withNestedClasses(reference, available)) {
                    if (bundled.add(name)) {
                        archiver.addFile(classesPath.resolve(name + CLASS_SUFFIX).toFile(), name + CLASS_SUFFIX);
                        pending.add(name);
                    }
                }
            }
        }
    }

    /// Internal names of every class compiled into this module's output directory.
    private Set<String> compiledClassNames(Path classesPath) {
        if (!Files.isDirectory(classesPath)) {
            return Set.of();
        }

        try (var stream = Files.walk(classesPath)) {
            return stream.filter(Files::isRegularFile)
                         .map(path -> classesPath.relativize(path)
                                                 .toString()
                                                 .replace('\\', '/'))
                         .filter(name -> name.endsWith(CLASS_SUFFIX))
                         .map(PackageSlicesMojo::toInternalName)
                         .collect(Collectors.toSet());
        } catch (IOException e) {
            getLog().debug("Could not scan compiled output: " + classesPath + " - " + e.getMessage());

            return Set.of();
        }
    }

    /// Candidate internal names appearing anywhere in a class file's bytes. Decoded as ISO-8859-1 so
    /// every byte maps to exactly one char — the scan needs byte positions preserved, not correct text.
    private Set<String> scanReferences(Path classFile) {
        try {
            var content = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            var matcher = INTERNAL_NAME.matcher(content);
            var names = new HashSet<String>();

            while (matcher.find()) {
                var name = matcher.group();

                names.add(name);
                // A match can START EARLIER than the type name. Constant-pool entries are length-prefixed
                // with no separator, so bytes belonging to the PREVIOUS entry run into the first path
                // segment: an annotation descriptor reads as `ALorg/example/Foo`, not `org/example/Foo`
                // and not even `Lorg/example/Foo`. Neither a fixed one-character strip nor cutting at
                // '/' boundaries recovers it — the first cut at '/' drops `org` along with the noise.
                //
                // Offer every offset INSIDE the first segment instead. One of them is the real internal
                // name whenever the run-together prefix sits there, and the rest are dropped by the
                // intersection with actually-compiled names, exactly like every other candidate. Bounded
                // by the length of one package segment, so it stays cheap. Measured on this project:
                // 26 real classes recovered per class scanned, 146 spurious candidates discarded.
                var firstSlash = name.indexOf('/');

                for (var offset = 1; offset < firstSlash; offset++) {
                    names.add(name.substring(offset));
                }
            }

            return names;
        } catch (IOException e) {
            getLog().debug("Could not scan class references: " + classFile + " - " + e.getMessage());

            return Set.of();
        }
    }

    private static List<String> withNestedClasses(String internalName, Set<String> available) {
        var prefix = internalName + "$";

        return Stream.concat(Stream.of(internalName),
                             available.stream()
                                      .filter(name -> name.startsWith(prefix)))
                     .toList();
    }

    private static String toInternalName(String relativePath) {
        return relativePath.substring(0, relativePath.length() - CLASS_SUFFIX.length());
    }

    private void addDirectoryClasses(JarArchiver archiver, Path dir, Path classesPath, Set<String> bundled) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString()
                                .endsWith(CLASS_SUFFIX))
                  .forEach(classFile -> {
                               var relativePath = classesPath.relativize(classFile)
                                                             .toString()
                                                             .replace('\\', '/');

                               if (bundled.add(toInternalName(relativePath))) {
                                   archiver.addFile(classFile.toFile(),
                                                    relativePath);
                               }
                           });
        } catch (IOException e) {
            getLog().debug("Could not read directory: " + dir + " - " + e.getMessage());
        }
    }

    private void bundleExternalLibs(JarArchiver archiver, List<Artifact> externalDeps) {
        for (var artifact : externalDeps) {
            var file = artifact.getFile();

            if (file == null || !file.exists() || !file.getName().endsWith(".jar")) {
                continue;
            }

            try (var jar = new JarFile(file)) {
                Enumeration<JarEntry> entries = jar.entries();

                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();
                    // Skip META-INF files to avoid conflicts (except services)
                    if (entryName.startsWith("META-INF/") && !entryName.startsWith("META-INF/services/")) {
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
                        Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
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

        if (!classification.sharedDeps().isEmpty()) {
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

        if (!classification.infraDeps().isEmpty()) {
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

        if (!classification.sliceDeps().isEmpty()) {
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

    private void addDependencyFile(JarArchiver archiver, SliceManifest manifest, String content) throws IOException {
        var factoryClassName = manifest.slicePackage() + "." + manifest.sliceName() + "Factory";
        var tempFile = Files.createTempFile("deps-", ".txt");

        tempFile.toFile().deleteOnExit();
        Files.writeString(tempFile, content);
        archiver.addFile(tempFile.toFile(), "META-INF/dependencies/" + factoryClassName);
    }

    private void addServiceFile(JarArchiver archiver, SliceManifest manifest) throws MojoExecutionException {
        var serviceFile = new File(classesDirectory,
                                   "META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory");

        if (!serviceFile.exists()) {
            return;
        }

        try {
            var routesClass = manifest.slicePackage() + "." + manifest.sliceName() + "Routes";
            var lines = Files.readAllLines(serviceFile.toPath());
            var filteredLines = lines.stream().filter(line -> line.trim()
                                                                  .equals(routesClass)).toList();

            if (!filteredLines.isEmpty()) {
                var tempService = Files.createTempFile("service-", ".txt");

                tempService.toFile().deleteOnExit();
                Files.writeString(tempService, String.join("\n", filteredLines));
                archiver.addFile(tempService.toFile(),
                                 "META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory");
                getLog().debug("Added service file entry for: " + routesClass);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to add service file", e);
        }
    }

    /// Builds artifact → version mapping from dependency file.
    /// Maps "groupId:artifactId" → "1.0.0" (strips semver range prefix)
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

    /// Strip semver range prefix (^, ~) to get actual version.
    /// ^1.0.0 → 1.0.0, ~2.1.0 → 2.1.0, 1.0.0 → 1.0.0
    private String stripSemverPrefix(String version) {
        if (version.startsWith("^") || version.startsWith("~")) {
            return version.substring(1);
        }

        return version;
    }

    /// Transforms factory .class file to replace UNRESOLVED version strings in constant pool.
    /// Uses JEP 484 Class-File API for bytecode manipulation.
    private byte[] transformFactoryBytecode(File classFile, Map<String, String> versionMap) throws IOException {
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

    private void addClassFiles(JarArchiver archiver,
                               String className,
                               Map<String, String> versionMap,
                               Set<String> bundled) throws MojoExecutionException {
        var classesPath = classesDirectory.toPath();
        var paths = SliceManifest.classToPathsWithInner(className, classesPath);

        try {
            for (var relativePath : paths) {
                var classFile = new File(classesDirectory, relativePath);

                if (classFile.exists() && bundled.add(toInternalName(relativePath))) {
                    // Transform factory classes with UNRESOLVED versions
                    if (className.endsWith("Factory") && !versionMap.isEmpty() && relativePath.equals(className.replace('.',
                                                                                                                        '/')
                                                                                                     + CLASS_SUFFIX)) {
                        var transformedBytes = transformFactoryBytecode(classFile, versionMap);
                        // Write transformed bytecode to temp file for archiving
                        var tempClass = Files.createTempFile("factory-", ".class");

                        tempClass.toFile().deleteOnExit();
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
        sb.append("    <groupId>").append(project.getGroupId()).append("</groupId>\n");
        sb.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        sb.append("    <version>").append(project.getVersion()).append("</version>\n");
        sb.append("    <packaging>jar</packaging>\n");
        sb.append("\n");
        sb.append("    <name>").append(manifest.sliceName()).append("</name>\n");
        sb.append("    <description>Generated slice artifact</description>\n");
        sb.append("\n");
        sb.append("    <dependencies>\n");
        // Slice depends on pragmatica-lite core
        sb.append("        <dependency>\n");
        sb.append("            <groupId>org.pragmatica-lite</groupId>\n");
        sb.append("            <artifactId>core</artifactId>\n");
        // version-literal: vestigial generated-pom dep coordinates; runtime resolves core/slice-api from the platform classpath — derive-or-drop tracked for rc3
        sb.append("            <version>0.11.2</version>\n");
        sb.append("        </dependency>\n");
        // And slice-api for runtime
        sb.append("        <dependency>\n");
        sb.append("            <groupId>org.pragmatica.aether</groupId>\n");
        sb.append("            <artifactId>slice-api</artifactId>\n");
        // version-literal: vestigial generated-pom dep coordinates; runtime resolves core/slice-api from the platform classpath — derive-or-drop tracked for rc3
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
