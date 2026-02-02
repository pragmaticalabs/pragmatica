package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Dependency descriptor parsed from META-INF/dependencies/ file format.
 * <p>
 * Format: {@code <fully.qualified.class>:<version-pattern>[:<optional-param-name>]}
 * <p>
 * Examples:
 * - {@code com.example.UserService:1.2.3} - Exact version, no param name
 * - {@code com.example.EmailService:^1.0.0:emailService} - Caret pattern with param name
 * - {@code com.example.OrderProcessor:[1.0.0,2.0.0):orderProcessor} - Range with param name
 *
 * @param sliceClassName Fully qualified class name of the dependency slice
 * @param versionPattern Version pattern for dependency resolution
 * @param parameterName  Optional parameter name for factory method (for verification)
 */
public record DependencyDescriptor(String sliceClassName, VersionPattern versionPattern, Option<String> parameterName) {
    /**
     * Parse dependency descriptor from string.
     * <p>
     * Format: {@code className:versionPattern[:paramName]}
     *
     * @param line The dependency descriptor string
     *
     * @return Parsed descriptor or error
     */
    public static Result<DependencyDescriptor> dependencyDescriptor(String line) {
        var trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return EMPTY_LINE.result();
        }
        if (trimmed.startsWith("#")) {
            return COMMENT_LINE.result();
        }
        var parts = trimmed.split(":", - 1);
        // -1 to include empty strings
        if (parts.length < 2) {
            return INVALID_FORMAT.apply(line)
                                 .result();
        }
        if (parts.length > 3) {
            return TOO_MANY_PARTS.apply(line)
                                 .result();
        }
        var className = parts[0].trim();
        var versionStr = parts[1].trim();
        var paramName = parts.length == 3
                        ? Option.option(parts[2].trim())
                        : Option.<String>none();
        if (className.isEmpty()) {
            return EMPTY_CLASS_NAME.apply(line)
                                   .result();
        }
        if (versionStr.isEmpty()) {
            return EMPTY_VERSION_PATTERN.apply(line)
                                        .result();
        }
        return VersionPattern.parse(versionStr)
                             .map(pattern -> new DependencyDescriptor(className, pattern, paramName));
    }

    /**
     * Format descriptor back to string representation.
     */
    public String asString() {
        var base = sliceClassName + ":" + versionPattern.asString();
        return parameterName.map(name -> base + ":" + name)
                            .or(base);
    }

    // Error constants
    private static final Cause EMPTY_LINE = Causes.cause("Dependency descriptor line is empty");
    private static final Cause COMMENT_LINE = Causes.cause("Dependency descriptor line is a comment");
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid dependency descriptor format: %s");
    private static final Fn1<Cause, String> TOO_MANY_PARTS = Causes.forOneValue("Too many parts in dependency descriptor: %s");
    private static final Fn1<Cause, String> EMPTY_CLASS_NAME = Causes.forOneValue("Empty class name in dependency descriptor: %s");
    private static final Fn1<Cause, String> EMPTY_VERSION_PATTERN = Causes.forOneValue("Empty version pattern in dependency descriptor: %s");
}
