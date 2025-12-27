package org.pragmatica.jbct.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Main entry point for the JBCT CLI tool.
 */
@Command(
        name = "jbct",
        description = "JBCT code formatting and linting tool",
        mixinStandardHelpOptions = true,
        versionProvider = JbctCommand.VersionProvider.class,
        subcommands = {
                FormatCommand.class,
                LintCommand.class,
                CheckCommand.class,
                UpgradeCommand.class,
                InitCommand.class,
                UpdateCommand.class
        }
)
public class JbctCommand implements Runnable {

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    boolean verbose;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JbctCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // If no subcommand is specified, print help
        CommandLine.usage(this, System.out);
    }

    /**
     * Version provider that reads version from resource file.
     */
    public static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { Version.get() };
        }
    }
}
