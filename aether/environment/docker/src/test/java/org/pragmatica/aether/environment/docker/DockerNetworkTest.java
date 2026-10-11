// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2131: the network a docker cluster joins is reused when present, created when absent, and removed only when empty.
class DockerNetworkTest {
    private static final class ScriptedRunner implements DockerCommandRunner {
        final List<String> commands = new ArrayList<>();
        final Function<List<String>, Promise<String>> answer;

        ScriptedRunner(Function<List<String>, Promise<String>> answer) {
            this.answer = answer;
        }

        @Override
        public Promise<String> execute(List<String> command) {
            commands.add(String.join(" ", command));

            return answer.apply(command);
        }
    }

    private static Promise<String> fail() {
        return Causes.cause("exit 1").promise();
    }

    @Test
    void anExistingNetwork_isReused_andNothingIsCreated() {
        var runner = new ScriptedRunner(_ -> Promise.success("[{}]"));

        assertThat(DockerNetwork.ensure(runner, "net").await().unwrap()).isEqualTo(DockerNetwork.Ensured.EXISTING);
        assertThat(runner.commands).containsExactly("docker network inspect net");
    }

    @Test
    void anAbsentNetwork_isCreated() {
        var runner = new ScriptedRunner(command -> command.contains("create") ? Promise.success("id") : fail());

        assertThat(DockerNetwork.ensure(runner, "net").await().unwrap()).isEqualTo(DockerNetwork.Ensured.CREATED);
        assertThat(runner.commands).containsExactly("docker network inspect net", "docker network create net");
    }

    /// A concurrent creator won the race: the create fails, the re-check finds the network, and that is a reuse.
    @Test
    void aCreateThatLosesARace_isAReuse_notAFailure() {
        var inspects = new int[]{0};
        var runner = new ScriptedRunner(command -> {
            if (command.contains("create")) {
                return fail();
            }

            return inspects[0]++ == 0 ? fail() : Promise.success("[{}]");
        });

        assertThat(DockerNetwork.ensure(runner, "net").await().unwrap()).isEqualTo(DockerNetwork.Ensured.EXISTING);
    }

    @Test
    void aCreateThatFailsForReal_isReported() {
        var runner = new ScriptedRunner(_ -> fail());

        assertThat(DockerNetwork.ensure(runner, "net").await().isFailure()).isTrue();
    }

    @Test
    void anEmptyNetwork_isRemoved() {
        var runner = new ScriptedRunner(command -> command.contains("--format") ? Promise.success("0") : Promise.success("net"));

        assertThat(DockerNetwork.removeIfEmpty(runner, "net").await().unwrap()).isEqualTo(DockerNetwork.Removal.REMOVED);
        assertThat(runner.commands).last().isEqualTo("docker network rm net");
    }

    @Test
    void aNetworkWithContainers_isLeftInPlace() {
        var runner = new ScriptedRunner(_ -> Promise.success("2"));

        assertThat(DockerNetwork.removeIfEmpty(runner, "net").await().unwrap()).isEqualTo(DockerNetwork.Removal.IN_USE);
        assertThat(runner.commands).noneMatch(command -> command.contains(" rm "));
    }

    @Test
    void aNetworkThatIsAlreadyGone_isNotAnError() {
        var runner = new ScriptedRunner(_ -> fail());

        assertThat(DockerNetwork.removeIfEmpty(runner, "net").await().unwrap()).isEqualTo(DockerNetwork.Removal.ABSENT);
    }
}
