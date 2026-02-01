package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.AiToolsInstaller;
import org.pragmatica.jbct.init.GitHubVersionResolver;
import org.pragmatica.jbct.init.ProjectInitializer;
import org.pragmatica.jbct.init.SliceProjectInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Init command - create new JBCT project.
 */
@Command(
 name = "init",
 description = "Initialize a new JBCT project",
 mixinStandardHelpOptions = true)
public class InitCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<directory>",
    description = "Project directory (default: current directory)",
    arity = "0..1")
    Path projectDir;

    @Option(
    names = {"--group-id", "-g"},
    description = "Maven group ID (default: com.example)",
    defaultValue = "com.example")
    String groupId;

    @Option(
    names = {"--artifact-id", "-a"},
    description = "Maven artifact ID (default: directory name)")
    String artifactId;

    @Option(
    names = {"--slice"},
    description = "Create an Aether slice project")
    boolean slice;

    @Option(
    names = {"--ai-only"},
    description = "Only install AI tools (skip project files)")
    boolean aiOnly;

    @Option(
    names = {"--no-ai"},
    description = "Skip AI tools installation")
    boolean noAi;

    @Option(
    names = {"--force", "-f"},
    description = "Overwrite existing files")
    boolean force;

    @Option(
    names = {"--pragmatica-version"},
    description = "Override pragmatica-lite version")
    String pragmaticaVersion;

    @Option(
    names = {"--aether-version"},
    description = "Override aether version (slice projects only)")
    String aetherVersion;

    @Option(
    names = {"--jbct-version"},
    description = "Override jbct-maven-plugin version")
    String jbctVersion;

    @Override
    public Integer call() {
        // Validate group ID
        if (!isValidPackageName(groupId)) {
            System.err.println("Error: Invalid group ID '" + groupId + "'");
            System.err.println("Group ID must be a valid Java package name (e.g., com.example, org.mycompany)");
            return 1;
        }
        // Determine project directory
        projectDir = org.pragmatica.lang.Option.option(projectDir)
                                               .map(Path::toAbsolutePath)
                                               .or(() -> Path.of(System.getProperty("user.dir")));
        // Determine artifact ID from directory name if not specified
        artifactId = org.pragmatica.lang.Option.option(artifactId)
                                               .or(() -> projectDir.getFileName().toString());
        var projectCreated = false;
        var aiToolsInstalled = false;
        // Create project structure unless --ai-only
        if (!aiOnly) {
            var projectType = slice
                              ? "slice"
                              : "JBCT";
            System.out.println("Initializing " + projectType + " project in: " + projectDir);
            // Create directory if it's a new project
            if (!Files.exists(projectDir)) {
                try{
                    Files.createDirectories(projectDir);
                } catch (java.io.IOException e) {
                    System.err.println("Error: Failed to create directory: " + e.getMessage());
                    return 1;
                }
            }
            var projectResult = slice
                                ? initSliceProject()
                                : initRegularProject();
            if (!projectResult) {
                return 1;
            }
            projectCreated = true;
        }
        // Install AI tools unless --no-ai
        if (!noAi) {
            var installer = AiToolsInstaller.aiToolsInstaller(projectDir);
            if (Files.exists(installer.claudeDir()) && !force) {
                System.out.println();
                System.out.println("Skipped: .claude/ (already exists, use --force to overwrite)");
            } else {
                System.out.println();
                System.out.println("Installing AI tools...");
                aiToolsInstalled = installer.install()
                                            .onFailure(cause -> System.err.println("Warning: Failed to install AI tools: " + cause.message()))
                                            .onSuccess(this::printAiToolsResult)
                                            .map(files -> !files.isEmpty())
                                            .or(false);
            }
        }
        // Print summary
        System.out.println();
        if (projectCreated) {
            System.out.println("Project initialized successfully!");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  1. cd " + projectDir.getFileName());
            System.out.println("  2. Edit pom.xml to customize your project");
            System.out.println("  3. Run 'mvn verify' to check everything works");
        }
        if (aiToolsInstalled) {
            System.out.println();
            System.out.println("AI tools installed. In Claude Code:");
            System.out.println("  - Use /skill jbct for JBCT coding guidance");
            System.out.println("  - Use jbct-coder agent for code generation");
            System.out.println("  - Use jbct-reviewer agent for code review");
        }
        return 0;
    }

    private boolean initRegularProject() {
        var initializer = hasVersionOverrides()
                          ? ProjectInitializer.projectInitializer(projectDir,
                                                                  groupId,
                                                                  artifactId,
                                                                  effectiveJbctVersion(),
                                                                  effectivePragmaticaVersion())
                          : ProjectInitializer.projectInitializer(projectDir, groupId, artifactId);
        return initializer.initialize()
                          .onFailure(cause -> System.err.println("Error: " + cause.message()))
                          .onSuccess(this::printCreatedFiles)
                          .map(_ -> true)
                          .or(false);
    }

    private void printCreatedFiles(java.util.List<Path> createdFiles) {
        System.out.println();
        System.out.println("Created project files:");
        for (var file : createdFiles) {
            var relativePath = projectDir.relativize(file);
            System.out.println("  " + relativePath);
        }
    }

    private boolean hasVersionOverrides() {
        return org.pragmatica.lang.Option.option(pragmaticaVersion).isPresent()
               || org.pragmatica.lang.Option.option(aetherVersion).isPresent()
               || org.pragmatica.lang.Option.option(jbctVersion).isPresent();
    }

    private String effectivePragmaticaVersion() {
        return org.pragmatica.lang.Option.option(pragmaticaVersion)
                                         .or(GitHubVersionResolver::defaultPragmaticaVersion);
    }

    private String effectiveAetherVersion() {
        return org.pragmatica.lang.Option.option(aetherVersion)
                                         .or(GitHubVersionResolver::defaultAetherVersion);
    }

    private String effectiveJbctVersion() {
        return org.pragmatica.lang.Option.option(jbctVersion)
                                         .or(GitHubVersionResolver::defaultJbctVersion);
    }

    private boolean initSliceProject() {
        return SliceProjectInitializer.sliceProjectInitializer(projectDir, groupId, artifactId)
                                      .flatMap(initializer -> initializer.initialize()
                                                                         .onSuccess(createdFiles -> printSliceCreatedFiles(createdFiles,
                                                                                                                           initializer.sliceName())))
                                      .onFailure(cause -> System.err.println("Error: " + cause.message()))
                                      .map(_ -> true)
                                      .or(false);
    }

    private void printSliceCreatedFiles(java.util.List<Path> createdFiles, String sliceName) {
        System.out.println();
        System.out.println("Created slice project files:");
        for (var file : createdFiles) {
            var relativePath = projectDir.relativize(file);
            System.out.println("  " + relativePath);
        }
        System.out.println();
        System.out.println("Slice: " + sliceName);
    }

    private void printAiToolsResult(java.util.List<Path> installedFiles) {
        if (!installedFiles.isEmpty()) {
            System.out.println("Installed AI tools to: .claude/");
        } else {
            System.out.println("No AI tools files to install.");
        }
    }

    private static boolean isValidPackageName(String packageName) {
        return org.pragmatica.lang.Option.option(packageName)
                                         .filter(s -> !s.isBlank())
                                         .filter(s -> !s.startsWith(".") && !s.endsWith("."))
                                         .filter(s -> !s.contains(".."))
                                         .map(s -> s.split("\\."))
                                         .filter(InitCommand::allValidIdentifiers)
                                         .isPresent();
    }

    private static boolean allValidIdentifiers(String[] segments) {
        for (var segment : segments) {
            if (!isValidJavaIdentifier(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidJavaIdentifier(String identifier) {
        return org.pragmatica.lang.Option.option(identifier)
                                         .filter(s -> !s.isEmpty())
                                         .filter(s -> Character.isJavaIdentifierStart(s.charAt(0)))
                                         .filter(InitCommand::allCharsValidIdentifierParts)
                                         .filter(s -> !isJavaKeyword(s))
                                         .isPresent();
    }

    private static boolean allCharsValidIdentifierParts(String identifier) {
        for (int i = 1; i < identifier.length(); i++) {
            if (!Character.isJavaIdentifierPart(identifier.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJavaKeyword(String word) {
        return switch (word) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null" -> true;
            default -> false;
        };
    }
}
