// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.aether.cli.deploy.DeployCommand;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #834: `--force` accepts a publish that would re-apply schema versions an operator undid or baselined. It is an addition on
/// every command that posts a blueprint artifact to `/blueprints/deploy` or `/blueprints/publish`: default false, and its help
/// names the 409 it overrides. The server half (the body field it becomes) is `BlueprintForceFieldTest`.
class BlueprintForceOptionTest {
    private static final String COORDS = "org.example:my-blueprint:1.0.0";

    @Test
    void blueprintDeploy_forceDefaultsToFalse_andIsSetByTheFlag() throws Exception {
        assertForceFlag(new AetherCli.BlueprintCommand.DeployArtifactCommand(), COORDS);
    }

    @Test
    void blueprintPublish_forceDefaultsToFalse_andIsSetByTheFlag() throws Exception {
        assertForceFlag(new AetherCli.BlueprintCommand.PublishCommand(), COORDS);
    }

    @Test
    void blueprintUpload_forceDefaultsToFalse_andIsSetByTheFlag() throws Exception {
        assertForceFlag(new AetherCli.BlueprintCommand.UploadCommand(), "blueprint.jar", "-g", "org.example", "-a", "my-blueprint", "-v", "1.0.0");
    }

    @Test
    void deploy_forceDefaultsToFalse_andIsSetByTheFlag() throws Exception {
        assertForceFlag(new DeployCommand(), COORDS);
    }

    @Test
    void forceHelp_namesTheRefusalItOverrides() {
        var option = new CommandLine(new AetherCli.BlueprintCommand.PublishCommand()).getCommandSpec()
                                                                                    .findOption("--force");

        assertTrue(option.description()[0].contains("409"), "the help must say what --force overrides");
    }

    private static void assertForceFlag(Object command, String... args) throws Exception {
        new CommandLine(command).parseArgs(args);
        assertEquals(Boolean.FALSE, readField(command, "force"));

        var withFlag = new String[args.length + 1];
        System.arraycopy(args, 0, withFlag, 0, args.length);
        withFlag[args.length] = "--force";
        new CommandLine(command).parseArgs(withFlag);

        assertEquals(Boolean.TRUE, readField(command, "force"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
