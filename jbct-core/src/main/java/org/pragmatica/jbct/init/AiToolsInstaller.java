package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Installs AI tools (Claude Code skills and agents) to project's .claude/ directory.
 */
public final class AiToolsInstaller {
    private static final String AI_TOOLS_PATH = "/ai-tools/";
    private static final String SKILLS_SUBPATH = "skills/";
    private static final String AGENTS_SUBPATH = "agents/";

    private final Path claudeDir;

    private AiToolsInstaller(Path claudeDir) {
        this.claudeDir = claudeDir;
    }

    /**
     * Create installer for project directory.
     * AI tools will be installed to projectDir/.claude/
     */
    public static AiToolsInstaller aiToolsInstaller(Path projectDir) {
        return new AiToolsInstaller(projectDir.resolve(".claude"));
    }

    /**
     * Install AI tools from bundled resources.
     *
     * @return List of installed files
     */
    public Result<List<Path>> install() {
        return createDirectories()
                                .flatMap(_ -> installAllResources());
    }

    private Result<Unit> createDirectories() {
        try{
            var skillsDir = claudeDir.resolve("skills");
            var agentsDir = claudeDir.resolve("agents");
            Files.createDirectories(skillsDir);
            Files.createDirectories(agentsDir);
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> installAllResources() {
        var skillsDir = claudeDir.resolve("skills");
        var agentsDir = claudeDir.resolve("agents");
        // Fork-Join: Install skills and agents in parallel
        return Result.allOf(installFromResources(AI_TOOLS_PATH + SKILLS_SUBPATH, skillsDir),
                            installFromResources(AI_TOOLS_PATH + AGENTS_SUBPATH, agentsDir))
                     .map(lists -> lists.stream()
                                        .flatMap(List::stream)
                                        .toList());
    }

    private Result<List<Path>> installFromResources(String resourcePath, Path targetDir) {
        var installedFiles = new ArrayList<Path>();
        try{
            var resource = getClass()
                                   .getResource(resourcePath);
            if (resource == null) {
                return Result.success(installedFiles);
            }
            if ("jar".equals(resource.getProtocol())) {
                // Running from JAR - extract files
                return installFromJar(resourcePath, targetDir);
            } else {
                // Running from filesystem (development)
                return installFromFilesystem(Path.of(resource.toURI()),
                                             targetDir);
            }
        } catch (Exception e) {
            return Causes.cause("Failed to install from " + resourcePath + ": " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> installFromJar(String resourcePath, Path targetDir) {
        var installedFiles = new ArrayList<Path>();
        try{
            var jarPath = getClass()
                                  .getProtectionDomain()
                                  .getCodeSource()
                                  .getLocation()
                                  .toURI();
            try (var jar = new JarFile(Path.of(jarPath)
                                           .toFile())) {
                var entries = jar.entries();
                var basePath = resourcePath.startsWith("/")
                               ? resourcePath.substring(1)
                               : resourcePath;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var name = entry.getName();
                    if (name.startsWith(basePath) && !entry.isDirectory()) {
                        var relativePath = name.substring(basePath.length());
                        var targetFile = targetDir.resolve(relativePath);
                        // Create parent directories
                        Files.createDirectories(targetFile.getParent());
                        // Copy file
                        try (var in = jar.getInputStream(entry)) {
                            Files.copy(in, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            installedFiles.add(targetFile);
                        }
                    }
                }
            }
            return Result.success(installedFiles);
        } catch (Exception e) {
            return Causes.cause("Failed to extract from JAR: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> installFromFilesystem(Path sourcePath, Path targetDir) {
        var installedFiles = new ArrayList<Path>();
        try{
            if (!Files.exists(sourcePath)) {
                return Result.success(installedFiles);
            }
            Files.walkFileTree(sourcePath,
                               new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                   var relativePath = sourcePath.relativize(file);
                                   var targetFile = targetDir.resolve(relativePath);
                                   Files.createDirectories(targetFile.getParent());
                                   Files.copy(file, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                   installedFiles.add(targetFile);
                                   return FileVisitResult.CONTINUE;
                               }
            });
            return Result.success(installedFiles);
        } catch (Exception e) {
            return Causes.cause("Failed to copy files: " + e.getMessage())
                         .result();
        }
    }

    /**
     * Get the Claude directory.
     */
    public Path claudeDir() {
        return claudeDir;
    }

    /**
     * Get the path where skills are installed.
     */
    public Path skillsDir() {
        return claudeDir.resolve("skills");
    }

    /**
     * Get the path where agents are installed.
     */
    public Path agentsDir() {
        return claudeDir.resolve("agents");
    }
}
