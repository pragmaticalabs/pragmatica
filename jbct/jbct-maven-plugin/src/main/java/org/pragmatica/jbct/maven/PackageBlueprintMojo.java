package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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

/// Packages a blueprint artifact JAR containing the blueprint definition,
/// optional resource configuration, and optional schema SQL files.
///
/// The resulting JAR has the classifier "blueprint" and is attached as a
/// secondary Maven artifact.
///
/// JAR structure:
///   META-INF/MANIFEST.MF          - Blueprint-Id, Blueprint-Version
///   META-INF/blueprint.toml       - slice list and instances
///   META-INF/resources.toml       - app-level config (if present)
///   schema/                       - SQL schema files (if present)
@Mojo(name = "package-blueprint",
 defaultPhase = LifecyclePhase.PACKAGE,
 requiresDependencyResolution = ResolutionScope.COMPILE)
@SuppressWarnings("JBCT-SEQ-01")
public class PackageBlueprintMojo extends AbstractMojo {
    private static final String CLASSIFIER = "blueprint";
    private static final String BLUEPRINT_TOML = "blueprint.toml";
    private static final String RESOURCES_TOML = "resources.toml";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    @Parameter(property = "jbct.blueprint.output",
    defaultValue = "${project.build.directory}/blueprint.toml")
    private File blueprintFile;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/resources.toml")
    private File resourcesTomlFile;

    @Parameter(defaultValue = "${project.basedir}/schema")
    private File schemaDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping blueprint packaging");
            return;
        }

        if (!blueprintFile.exists()) {
            getLog().info("No blueprint.toml found at " + blueprintFile + " - skipping blueprint packaging");
            return;
        }

        var blueprintId = readBlueprintId();
        var jarFile = buildJarFile();

        createBlueprintJar(jarFile, blueprintId);
        attachArtifact(jarFile);

        getLog().info("Created blueprint JAR: " + jarFile.getName());
    }

    private String readBlueprintId() throws MojoExecutionException {
        try {
            var content = Files.readString(blueprintFile.toPath());
            for (var line : content.split("\n")) {
                var trimmed = line.trim();
                if (trimmed.startsWith("id = \"") && trimmed.endsWith("\"")) {
                    return trimmed.substring(6, trimmed.length() - 1);
                }
            }
            return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read blueprint.toml", e);
        }
    }

    private File buildJarFile() {
        var jarName = project.getArtifactId() + "-" + project.getVersion() + "-" + CLASSIFIER + ".jar";
        return new File(outputDirectory, jarName);
    }

    private void createBlueprintJar(File jarFile, String blueprintId) throws MojoExecutionException {
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
            config.addManifestEntry("Blueprint-Id", blueprintId);
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
        if (!schemaDirectory.exists() || !schemaDirectory.isDirectory()) {
            return;
        }

        var schemaPath = schemaDirectory.toPath();

        try (var sqlFiles = findSqlFiles(schemaPath)) {
            sqlFiles.forEach(file -> addSchemaFile(archiver, schemaPath, file));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan schema directory", e);
        }
    }

    private Stream<Path> findSqlFiles(Path schemaPath) throws IOException {
        return Files.walk(schemaPath)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"));
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
}
