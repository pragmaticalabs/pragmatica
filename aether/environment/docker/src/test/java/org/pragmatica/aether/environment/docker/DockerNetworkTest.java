// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2131: the network a docker cluster joins is reused when present, created (labelled with its cluster) when absent, owned again only when it carries
/// THIS cluster's label, and removed only when owned and empty.
class DockerNetworkTest {
    /// A tiny docker: networks by name with their owner label and attached-container count.
    private static class Docker implements DockerCommandRunner {
        final List<String> commands = new ArrayList<>();
        final Map<String, String> labels = new HashMap<>();
        final Map<String, Integer> attached = new HashMap<>();
        boolean createFails;
        boolean rmFails;

        @Override
        public Promise<String> execute(List<String> command) {
            commands.add(String.join(" ", command));
            var name = command.getLast();

            return switch (command.get(2)) {
                case "inspect" -> inspect(command, name);
                case "create" -> create(command, name);
                case "rm" -> {
                    if (rmFails) {
                        yield Causes.cause("Docker command failed (exit 1): error while removing network: network has active endpoints").promise();
                    }

                    labels.remove(name);
                    yield Promise.success(name);
                }
                default -> Causes.cause("unexpected " + command).promise();
            };
        }

        private Promise<String> inspect(List<String> command, String name) {
            if (!labels.containsKey(name)) {
                return Causes.cause("No such network").promise();
            }

            var format = command.get(command.indexOf("--format") + 1);

            return Promise.success(format.contains("len .Containers") ? labels.get(name) + "|" + attached.getOrDefault(name, 0) : labels.get(name));
        }

        private Promise<String> create(List<String> command, String name) {
            if (createFails) {
                return Causes.cause("create failed").promise();
            }

            var label = command.get(command.indexOf("--label") + 1);

            labels.put(name, label.substring(label.indexOf('=') + 1));

            return Promise.success("id");
        }
    }

    @Test
    void anAbsentNetwork_isCreatedWithTheClustersLabel() {
        var docker = new Docker();

        assertThat(DockerNetwork.ensure(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Ensured.CREATED);
        assertThat(docker.commands).last().isEqualTo("docker network create --label aether.created-by-cluster=c1 net");
    }

    @Test
    void aNetworkWithoutOurLabel_isForeign_reusedAndNeverOwned() {
        var docker = new Docker();

        docker.labels.put("net", "");

        assertThat(DockerNetwork.ensure(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Ensured.FOREIGN);
        assertThat(docker.commands).noneMatch(command -> command.contains(" create "));
    }

    @Test
    void aNetworkWithAnotherClustersLabel_isForeign() {
        var docker = new Docker();

        docker.labels.put("net", "c2");

        assertThat(DockerNetwork.ensure(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Ensured.FOREIGN);
    }

    /// A re-run over a network an earlier run of THIS cluster left in place: it is ours again, so it is recorded and will not leak.
    @Test
    void aNetworkCarryingOurOwnLabel_isOwnedAgain() {
        var docker = new Docker();

        docker.labels.put("net", "c1");

        assertThat(DockerNetwork.ensure(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Ensured.OWN);
        assertThat(DockerNetwork.Ensured.OWN.owned()).isTrue();
        assertThat(DockerNetwork.Ensured.CREATED.owned()).isTrue();
        assertThat(DockerNetwork.Ensured.FOREIGN.owned()).isFalse();
    }

    /// A concurrent creator won the race: our create fails, the re-check finds the network, and ownership follows its label.
    @Test
    void aCreateThatLosesARace_followsTheWinnersLabel() {
        var docker = new Docker() {
            @Override
            public Promise<String> execute(List<String> command) {
                if (command.get(2).equals("create")) {
                    labels.put("net", "someone-else");
                    commands.add(String.join(" ", command));

                    return Causes.cause("already exists").promise();
                }

                return super.execute(command);
            }
        };

        assertThat(DockerNetwork.ensure(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Ensured.FOREIGN);
    }

    @Test
    void aCreateThatFailsForReal_isReported() {
        var docker = new Docker();

        docker.createFails = true;

        assertThat(DockerNetwork.ensure(docker, "net", "c1").await().isFailure()).isTrue();
    }

    @Test
    void anOwnedEmptyNetwork_isRemoved_byTheNameGiven() {
        var docker = new Docker();

        docker.labels.put("net", "c1");

        assertThat(DockerNetwork.removeIfOwnedAndEmpty(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Removal.REMOVED);
        assertThat(docker.commands).last().isEqualTo("docker network rm net");
    }

    @Test
    void anOwnedNetworkWithContainers_isLeftInPlace() {
        var docker = new Docker();

        docker.labels.put("net", "c1");
        docker.attached.put("net", 2);

        assertThat(DockerNetwork.removeIfOwnedAndEmpty(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Removal.IN_USE);
        assertThat(docker.commands).noneMatch(command -> command.contains(" rm "));
    }

    @Test
    void aNetworkWithoutOurLabel_isNeverRemoved_evenWhenEmpty() {
        var docker = new Docker();

        docker.labels.put("net", "c2");
        docker.labels.put("unlabelled", "");

        assertThat(DockerNetwork.removeIfOwnedAndEmpty(docker, "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Removal.NOT_OURS);
        assertThat(DockerNetwork.removeIfOwnedAndEmpty(docker, "unlabelled", "c1").await().unwrap()).isEqualTo(DockerNetwork.Removal.NOT_OURS);
        assertThat(docker.commands).noneMatch(command -> command.contains(" rm "));
    }

    @Test
    void aNetworkThatIsAlreadyGone_isNotAnError() {
        assertThat(DockerNetwork.removeIfOwnedAndEmpty(new Docker(), "net", "c1").await().unwrap()).isEqualTo(DockerNetwork.Removal.ABSENT);
    }

    /// A failed `docker network rm` is NOT "absent": the network is still there. It comes back as a failure carrying docker's own text.
    @Test
    void aFailedRm_isReportedWithDockersCause_notSwallowedAsAbsent() {
        var docker = new Docker();

        docker.labels.put("net", "c1");
        docker.rmFails = true;

        var result = DockerNetwork.removeIfOwnedAndEmpty(docker, "net", "c1").await();

        assertThat(result.isFailure()).as("not reported as ABSENT").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("network has active endpoints"));
        assertThat(docker.labels).as("the network is genuinely still there").containsKey("net");
    }
}
