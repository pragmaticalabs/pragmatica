package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.doc.HeaderExtractor;
import org.pragmatica.jbct.doc.SourceResolver;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Print the class-level markdown header (`///` block) of a Pragmatica Core class or package.
///
/// The header is the "source-anchored skill chapter" — API documentation read straight from the
/// source of truth. Targets may be a fully-qualified class name (`org.pragmatica.lang.Verify`),
/// a package name (`org.pragmatica.lang.vo`, resolved to its `package-info.java`), or a simple
/// name (`Verify`, `Result`) searched across the core packages.
@Command(
 name = "doc",
 description = "Print the class-level documentation header of a Pragmatica Core class or package",
 mixinStandardHelpOptions = true)
public class DocCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<name>",
    description = "Class FQCN, simple name, or package name to document",
    arity = "1")
    String name;

    @picocli.CommandLine.Option(
    names = {"--api"},
    description = "Append a heuristic '## Public API' section listing top-level method signatures")
    boolean api;

    @picocli.CommandLine.Option(
    names = {"--jar"},
    description = "Explicit core sources jar to read from")
    Path jar;

    @picocli.CommandLine.Option(
    names = {"--use-version"},
    description = "Use this core version from the local Maven repository")
    String useVersion;

    @Override
    public Integer call() {
        return SourceResolver.resolve(name, Option.option(jar), Option.option(useVersion))
                             .flatMap(this::extract)
                             .fold(this::reportFailure, this::printMarkdown);
    }

    private Result<String> extract(String content) {
        return api ? HeaderExtractor.extractWithApi(content, name) : HeaderExtractor.extract(content, name);
    }

    private Integer printMarkdown(String markdown) {
        System.out.println(markdown);
        return 0;
    }

    private Integer reportFailure(Cause cause) {
        System.err.println(cause.message());
        return 1;
    }
}
