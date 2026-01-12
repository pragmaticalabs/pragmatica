package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Initializes a new JBCT project structure.
 */
public final class ProjectInitializer {
    private static final String TEMPLATES_PATH = "/templates/";

    // Default versions - updated on each release
    private static final String DEFAULT_JBCT_VERSION = "0.4.8";
    private static final String DEFAULT_PRAGMATICA_VERSION = "0.9.10";

    private final Path projectDir;
    private final String groupId;
    private final String artifactId;
    private final String basePackage;
    private final String jbctVersion;
    private final String pragmaticaVersion;

    private ProjectInitializer(Path projectDir, String groupId, String artifactId, String basePackage,
                               String jbctVersion, String pragmaticaVersion) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.jbctVersion = jbctVersion;
        this.pragmaticaVersion = pragmaticaVersion;
    }

    /**
     * Create initializer with project parameters and default versions.
     */
    public static ProjectInitializer projectInitializer(Path projectDir, String groupId, String artifactId) {
        var basePackage = groupId + "." + artifactId.replace("-", "");
        return new ProjectInitializer(projectDir, groupId, artifactId, basePackage,
                                      DEFAULT_JBCT_VERSION, DEFAULT_PRAGMATICA_VERSION);
    }

    /**
     * Create initializer with project parameters and custom versions.
     */
    public static ProjectInitializer projectInitializer(Path projectDir, String groupId, String artifactId,
                                                        String jbctVersion, String pragmaticaVersion) {
        var basePackage = groupId + "." + artifactId.replace("-", "");
        return new ProjectInitializer(projectDir, groupId, artifactId, basePackage,
                                      jbctVersion, pragmaticaVersion);
    }

    /**
     * Create initializer with explicit base package.
     */
    public static ProjectInitializer projectInitializer(Path projectDir,
                                                        String groupId,
                                                        String artifactId,
                                                        String basePackage) {
        return new ProjectInitializer(projectDir, groupId, artifactId, basePackage,
                                      DEFAULT_JBCT_VERSION, DEFAULT_PRAGMATICA_VERSION);
    }

    /**
     * Initialize the project structure.
     *
     * @return List of created files
     */
    public Result<List<Path>> initialize() {
        return createDirectories()
                                .flatMap(_ -> createTemplateFiles())
                                .flatMap(templateFiles -> createGitkeepFiles()
                                                                            .map(gitkeepFiles -> {
                                                                                     var allFiles = new ArrayList<Path>();
                                                                                     allFiles.addAll(templateFiles);
                                                                                     allFiles.addAll(gitkeepFiles);
                                                                                     return allFiles;
                                                                                 }));
    }

    private Result<Path> createDirectories() {
        try{
            var srcMainJava = projectDir.resolve("src/main/java");
            var srcTestJava = projectDir.resolve("src/test/java");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);
            var packagePath = basePackage.replace(".", "/");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(srcTestJava.resolve(packagePath));
            return Result.success(projectDir);
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> createTemplateFiles() {
        // Fork-Join: Create template files in parallel
        return Result.allOf(createFile("pom.xml.template", projectDir.resolve("pom.xml")),
                            createFile("jbct.toml.template", projectDir.resolve("jbct.toml")),
                            createFile("gitignore.template", projectDir.resolve(".gitignore")),
                            createClaudeMd());
    }

    private Result<Path> createClaudeMd() {
        var targetPath = projectDir.resolve("CLAUDE.md");
        if (Files.exists(targetPath)) {
            System.out.println("  Skipped: CLAUDE.md (already exists)");
            return Result.success(targetPath);
        }
        return createFile("CLAUDE.md", targetPath);
    }

    private Result<List<Path>> createGitkeepFiles() {
        var packagePath = basePackage.replace(".", "/");
        var srcKeep = projectDir.resolve("src/main/java")
                                .resolve(packagePath)
                                .resolve(".gitkeep");
        var testKeep = projectDir.resolve("src/test/java")
                                 .resolve(packagePath)
                                 .resolve(".gitkeep");
        try{
            if (!Files.exists(srcKeep)) {
                Files.createFile(srcKeep);
            }
            if (!Files.exists(testKeep)) {
                Files.createFile(testKeep);
            }
            return Result.success(List.of(srcKeep, testKeep));
        } catch (Exception e) {
            return Causes.cause("Failed to create .gitkeep files: " + e.getMessage())
                         .result();
        }
    }

    private Result<Path> createFile(String templateName, Path targetPath) {
        if (Files.exists(targetPath)) {
            // Don't overwrite existing files
            return Result.success(targetPath);
        }
        try (var in = getClass()
                              .getResourceAsStream(TEMPLATES_PATH + templateName)) {
            if (in == null) {
                return Causes.cause("Template not found: " + templateName)
                             .result();
            }
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            content = substituteVariables(content);
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage())
                         .result();
        }
    }

    private String substituteVariables(String content) {
        return content.replace("{{groupId}}", groupId)
                      .replace("{{artifactId}}", artifactId)
                      .replace("{{projectName}}", capitalizeWords(artifactId))
                      .replace("{{basePackage}}", basePackage)
                      .replace("{{jbctVersion}}", jbctVersion)
                      .replace("{{pragmaticaVersion}}", pragmaticaVersion);
    }

    private String capitalizeWords(String s) {
        var words = s.split("-");
        var sb = new StringBuilder();
        for (var word : words) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Get the project directory.
     */
    public Path projectDir() {
        return projectDir;
    }

    /**
     * Get the group ID.
     */
    public String groupId() {
        return groupId;
    }

    /**
     * Get the artifact ID.
     */
    public String artifactId() {
        return artifactId;
    }

    /**
     * Get the base package.
     */
    public String basePackage() {
        return basePackage;
    }
}
