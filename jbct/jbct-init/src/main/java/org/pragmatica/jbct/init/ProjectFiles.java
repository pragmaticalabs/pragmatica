package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Shared file and naming utilities for project scaffolding.
public sealed interface ProjectFiles permits ProjectFiles.Sealed {
    /// Convert kebab-case or hyphenated string to CamelCase.
    static String toCamelCase(String s) {
        var words = s.split("-");
        var sb = new StringBuilder();
        for (var word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }

    /// Write content to a new file, failing if it already exists.
    static Result<Path> writeNewFile(Path targetPath, String content) {
        if (Files.exists(targetPath)) {
            return Causes.cause("File already exists: " + targetPath)
                         .result();
        }
        try {
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to write " + targetPath + ": " + e.getMessage())
                         .result();
        }
    }

    final class Sealed implements ProjectFiles {
        private Sealed() {}
    }
}
