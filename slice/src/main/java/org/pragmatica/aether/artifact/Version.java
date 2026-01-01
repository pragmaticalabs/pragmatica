package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.ensure;

public record Version(int major, int minor, int patch, String qualifier) {
    public static Result<Version> version(String versionString) {
        var parts = versionString.split("\\.");
        if (parts.length != 3) {
            return FORMAT_ERROR.apply(versionString)
                               .result();
        }
        int dashIndex = parts[2].indexOf('-');
        if (dashIndex > 0 && (dashIndex + 1) == parts[2].length()) {
            // Only dash at the end of the string
            return FORMAT_ERROR.apply(versionString)
                               .result();
        }
        var qualifier = (dashIndex > 0)
                        ? Option.option(parts[2].substring(dashIndex + 1))
                        : Option.<String>none();
        if (dashIndex > 0) {
            parts[2] = parts[2].substring(0, dashIndex);
        }
        return Result.all(Number.parseInt(parts[0]),
                          Number.parseInt(parts[1]),
                          Number.parseInt(parts[2]),
                          Result.success(qualifier))
                     .flatMap(Version::version);
    }

    public static Result<Version> version(int major, int minor, int patch, Option<String> qualifier) {
        var innerQualifier = qualifier.or("");
        return Result.all(ensure(major, Is::greaterThanOrEqualTo, 0),
                          ensure(minor, Is::greaterThanOrEqualTo, 0),
                          ensure(patch, Is::greaterThanOrEqualTo, 0),
                          ensure(innerQualifier, Is::matches, QUALIFIER_PATTERN))
                     .map(Version::new);
    }

    public String bareVersion() {
        return major + "." + minor + "." + patch;
    }

    public String withQualifier() {
        return qualifier.isEmpty()
               ? bareVersion()
               : bareVersion() + "-" + qualifier;
    }

    private static final Pattern QUALIFIER_PATTERN = Pattern.compile("^[\\-a-zA-Z0-9-_.]*$");

    private static final Fn1<Cause, String>FORMAT_ERROR = Causes.forOneValue(
    "Invalid version format: {0}, expected {major}.{minor}.{patch}[-{suffix}]");
}
