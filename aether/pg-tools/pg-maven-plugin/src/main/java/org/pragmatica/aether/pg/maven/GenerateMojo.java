package org.pragmatica.aether.pg.maven;

import org.pragmatica.aether.pg.codegen.CodegenConfig;
import org.pragmatica.aether.pg.codegen.CodegenPipeline;
import org.pragmatica.aether.pg.codegen.GeneratedFile;

import org.pragmatica.lang.Contract;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Contract
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/schema", property = "pg.schemaDir")
    private File schemaDir;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/pg", property = "pg.outputDir")
    private File outputDir;

    @Parameter(defaultValue = "${project.groupId}", property = "pg.packageName")
    private String packageName;

    @Parameter(property = "pg.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Override public void execute() throws MojoExecutionException {
        if ( skip) {
            getLog().info("pg-maven-plugin: skipped");
            return;
        }
        if ( !schemaDir.exists() || !schemaDir.isDirectory()) {
            getLog().info("Schema directory does not exist: " + schemaDir + " — nothing to generate");
            return;
        }
        var sqlFiles = findMigrationFiles();
        if ( sqlFiles.length == 0) {
            getLog().info("No V*.sql migration files found in " + schemaDir);
            return;
        }
        writeMigrationManifest(sqlFiles);
        var migrationScripts = readMigrationScripts(sqlFiles);
        var config = CodegenConfig.defaults(packageName, outputDir.toPath());
        var pipeline = new CodegenPipeline(config);
        pipeline.generate(migrationScripts).onSuccess(this::writeFiles)
                         .onFailure(cause -> throwGenerationFailure(cause.message()));
        project.addCompileSourceRoot(outputDir.getAbsolutePath());
    }

    private File[] findMigrationFiles() {
        var sqlFiles = schemaDir.listFiles((dir, name) -> name.matches("V.*\\.sql"));
        if ( sqlFiles == null || sqlFiles.length == 0) {
        return new File[0];}
        Arrays.sort(sqlFiles, Comparator.comparing(File::getName));
        return sqlFiles;
    }

    private void writeMigrationManifest(File[] sqlFiles) throws MojoExecutionException {
        var classesSchemaDir = Path.of(project.getBuild().getOutputDirectory(),
                                       "schema");
        var manifest = classesSchemaDir.resolve("migrations.list");
        var fileNames = Arrays.stream(sqlFiles).map(File::getName)
                                     .toList();
        try {
            Files.createDirectories(classesSchemaDir);
            Files.writeString(manifest, String.join("\n", fileNames));
            getLog().info("Generated migrations.list with " + fileNames.size() + " entries");
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to write migrations.list", e);
        }
    }

    private List<String> readMigrationScripts(File[] sqlFiles) throws MojoExecutionException {
        try {
            return Arrays.stream(sqlFiles).map(File::toPath)
                                .map(GenerateMojo::readFileContent)
                                .toList();
        }
        catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to read migration files", unwrapIoException(e));
        }
    }

    private void writeFiles(List<GeneratedFile> files) {
        for ( var file : files) {
        writeGeneratedFile(file);}
        getLog().info("Generated " + files.size() + " files from " + schemaDir);
    }

    private void writeGeneratedFile(GeneratedFile file) {
        try {
            Files.createDirectories(file.path().getParent());
            Files.writeString(file.path(), file.content());
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to write " + file.path(), e);
        }
    }

    private static String readFileContent(Path path) {
        try {
            return Files.readString(path);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }

    private static void throwGenerationFailure(String message) {
        throw new RuntimeException(new MojoExecutionException("Code generation failed: " + message));
    }

    private static Exception unwrapIoException(RuntimeException e) {
        return e.getCause() instanceof IOException io
               ? io
               : e;
    }
}
