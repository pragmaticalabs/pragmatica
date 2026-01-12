package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.AiToolsInstaller;
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

    @Override
    public Integer call() {
        // Determine project directory
        if (projectDir == null) {
            projectDir = Path.of(System.getProperty("user.dir"));
        } else {
            projectDir = projectDir.toAbsolutePath();
        }
        // Determine artifact ID from directory name if not specified
        if (artifactId == null) {
            artifactId = projectDir.getFileName()
                                   .toString();
        }
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
                } catch (Exception e) {
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
                                            .fold(cause -> {
                                                      System.err.println("Warning: Failed to install AI tools: " + cause.message());
                                                      return false;
                                                  },
                                                  installedFiles -> {
                                                      if (!installedFiles.isEmpty()) {
                                                          System.out.println("Installed AI tools to: .claude/");
                                                          return true;
                                                      }
                                                      System.out.println("No AI tools files to install.");
                                                      return false;
                                                  });
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
        var initializer = ProjectInitializer.projectInitializer(projectDir, groupId, artifactId);
        return initializer.initialize()
                          .fold(cause -> {
                                    System.err.println("Error: " + cause.message());
                                    return false;
                                },
                                createdFiles -> {
                                    System.out.println();
                                    System.out.println("Created project files:");
                                    for (var file : createdFiles) {
                                        var relativePath = projectDir.relativize(file);
                                        System.out.println("  " + relativePath);
                                    }
                                    return true;
                                });
    }

    private boolean initSliceProject() {
        return SliceProjectInitializer.sliceProjectInitializer(projectDir, groupId, artifactId)
                                      .flatMap(initializer -> initializer.initialize()
                                                                         .map(createdFiles -> {
                                                                                  System.out.println();
                                                                                  System.out.println("Created slice project files:");
                                                                                  for (var file : createdFiles) {
                                                                                      var relativePath = projectDir.relativize(file);
                                                                                      System.out.println("  " + relativePath);
                                                                                  }
                                                                                  System.out.println();
                                                                                  System.out.println("Slice: " + initializer.sliceName());
                                                                                  return true;
                                                                              }))
                                      .fold(cause -> {
                                                System.err.println("Error: " + cause.message());
                                                return false;
                                            },
                                            success -> success);
    }
}
