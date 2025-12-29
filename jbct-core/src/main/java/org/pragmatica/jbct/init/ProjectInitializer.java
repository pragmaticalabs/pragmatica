package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Initializes a new JBCT project structure.
 */
public final class ProjectInitializer {

    private static final String TEMPLATES_PATH = "/templates/";

    private final Path projectDir;
    private final String groupId;
    private final String artifactId;
    private final String basePackage;

    private ProjectInitializer(Path projectDir, String groupId, String artifactId, String basePackage) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
    }

    /**
     * Create initializer with project parameters.
     */
    public static ProjectInitializer projectInitializer(Path projectDir, String groupId, String artifactId) {
        var basePackage = groupId + "." + artifactId.replace("-", "");
        return new ProjectInitializer(projectDir, groupId, artifactId, basePackage);
    }

    /**
     * Create initializer with explicit base package.
     */
    public static ProjectInitializer projectInitializer(Path projectDir, String groupId, String artifactId, String basePackage) {
        return new ProjectInitializer(projectDir, groupId, artifactId, basePackage);
    }

    /**
     * Initialize the project structure.
     *
     * @return List of created files
     */
    public Result<List<Path>> initialize() {
        try {
            // Create directories
            var srcMainJava = projectDir.resolve("src/main/java");
            var srcTestJava = projectDir.resolve("src/test/java");

            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            // Create package directories
            var packagePath = basePackage.replace(".", "/");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(srcTestJava.resolve(packagePath));

            var createdFiles = new java.util.ArrayList<Path>();

            // Create pom.xml
            createFile("pom.xml.template", projectDir.resolve("pom.xml"))
                .onSuccess(createdFiles::add);

            // Create jbct.toml
            createFile("jbct.toml.template", projectDir.resolve("jbct.toml"))
                .onSuccess(createdFiles::add);

            // Create .gitignore
            createFile("gitignore.template", projectDir.resolve(".gitignore"))
                .onSuccess(createdFiles::add);

            // Create .gitkeep files
            var srcKeep = srcMainJava.resolve(packagePath).resolve(".gitkeep");
            var testKeep = srcTestJava.resolve(packagePath).resolve(".gitkeep");
            Files.createFile(srcKeep);
            createdFiles.add(srcKeep);
            Files.createFile(testKeep);
            createdFiles.add(testKeep);

            return Result.success(createdFiles);
        } catch (Exception e) {
            return Causes.cause("Failed to initialize project: " + e.getMessage()).result();
        }
    }

    private Result<Path> createFile(String templateName, Path targetPath) {
        if (Files.exists(targetPath)) {
            // Don't overwrite existing files
            return Result.success(targetPath);
        }

        try (var in = getClass().getResourceAsStream(TEMPLATES_PATH + templateName)) {
            if (in == null) {
                return Causes.cause("Template not found: " + templateName).result();
            }

            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            content = substituteVariables(content);

            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage()).result();
        }
    }

    private String substituteVariables(String content) {
        return content
                .replace("{{groupId}}", groupId)
                .replace("{{artifactId}}", artifactId)
                .replace("{{projectName}}", capitalizeWords(artifactId))
                .replace("{{basePackage}}", basePackage);
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
