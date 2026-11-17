package org.pragmatica.jbct.cli;

import java.io.PrintStream;
import java.nio.file.Path;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.lang.Option;


/// Configuration loading shared by the commands (#1455).
///
/// A layer that cannot be used (syntax error, unreadable file, wrong-typed key) is not skipped: the
/// command stops with exit status [#CONFIG_ERROR_EXIT], and stderr names the file and the offending
/// key or line. That is the delivery of the failure to the person running the tool; nothing else
/// consumes it.
public sealed interface CliConfig {
    int CONFIG_ERROR_EXIT = 2;

    /// Load the configuration that applies to a checked path, printing the refusal to `err` when it fails.
    ///
    /// The `jbct.toml` chain starts at the path's module directory ([ConfigLoader#loadForSourceRoot]), never at the
    /// working directory. A command given several paths passes its first: one run holds one configuration until
    /// the lint entry points resolve per source root (P2c).
    static Option<JbctConfig> load(Option<Path> explicitConfigPath, Path checkedPath, PrintStream err) {
        return load(explicitConfigPath, Option.none(), checkedPath, err);
    }

    /// Load the configuration for a checked path with the command's `--mode` folded last (C7): after the `jbct.toml`
    /// chain and after `--config`, so it wins over both. An unknown value is refused the same
    /// way a bad config layer is.
    static Option<JbctConfig> load(Option<Path> explicitConfigPath,
                                   Option<String> explicitMode,
                                   Path checkedPath,
                                   PrintStream err) {
        return ConfigLoader.loadForSourceRoot(explicitConfigPath, checkedPath)
                           .flatMap(config -> config.overrideMode(explicitMode))
                           .onFailure(cause -> err.println("Error: " + cause.message()))
                           .option();
    }

    /// The lint context of a loaded configuration: [LintContext#fromConfig], the one factory the CLI commands and the
    /// Maven mojos share, so `[lint] mode`, `--mode` and `[project] basePackage` reach every rule's context (#2525).
    /// `failOnWarning` only tightens the loaded lint settings; it never loosens them.
    static LintContext lintContext(JbctConfig config, boolean failOnWarning) {
        return LintContext.fromConfig(config).withConfig(failOnWarning
                                                         ? config.lint()
                                                                 .withFailOnWarning(true)
                                                         : config.lint());
    }

    record unused() implements CliConfig {}
}
