package org.pragmatica.jbct.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression gate for `jbct init`'s option surface (getting-started dry-run findings).
///
/// A custom `@Option(names = "--version")` used to collide with `mixinStandardHelpOptions`, which
/// silently neutralised the standard `-h/--help` and `-V/--version` flags — `jbct init --help`
/// answered "Unknown option: '--help'". These assertions pin the command model so that regression
/// cannot return, and confirm the dead `--slice` flag stays removed.
class InitCommandCliTest {
    private static CommandLine.Model.CommandSpec initSpec() {
        return new CommandLine(new InitCommand()).getCommandSpec();
    }

    @Test
    void initCommand_standardHelpOption_isRecognized() {
        assertThat(initSpec().findOption("--help"))
                  .as("jbct init --help must render (mixinStandardHelpOptions), not report 'Unknown option'")
                  .isNotNull();
    }

    @Test
    void initCommand_versionFlag_isStandardVersionHelp_notAValueOption() {
        var version = initSpec().findOption("--version");

        assertThat(version)
                  .as("--version must exist as the standard version flag")
                  .isNotNull();
        assertThat(version.versionHelp())
                  .as("--version must be the standard version-help flag, not a dependency-pinning value option")
                  .isTrue();
    }

    @Test
    void initCommand_perToolVersionFlags_remainAvailable() {
        var spec = initSpec();

        assertThat(spec.findOption("--pragmatica-version")).isNotNull();
        assertThat(spec.findOption("--aether-version")).isNotNull();
        assertThat(spec.findOption("--jbct-version")).isNotNull();
    }

    @Test
    void initCommand_deadSliceFlag_isRemoved() {
        assertThat(initSpec().findOption("--slice"))
                  .as("the dead --slice flag must be removed; --no-slice is the real opt-out")
                  .isNull();
    }
}
