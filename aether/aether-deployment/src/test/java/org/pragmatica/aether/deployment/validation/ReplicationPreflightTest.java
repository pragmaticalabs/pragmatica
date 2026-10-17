// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.aether.slice.ReplicationWarning;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #1564: deploy-time replication validation of the durable-entity and durable-topic sections a slice jar declares,
/// through the same [ReplicationContext#resolve] the resource factories apply at activation.
class ReplicationPreflightTest {
    private static final ReplicationContext BUILT_IN = ReplicationContext.BUILT_IN;

    @Test
    void entityFactorAboveDesiredCoreCount_failsTheDeploy() {
        var outcome = validate(entityJar("replication_factor = 5"), ReplicationContext.replicationContext(ReplicationFactors.BUILT_IN, 3));

        assertThat(causes(outcome)).contains(new ReplicationPreflight.ReplicationPreflightFailure("orders-slice",
                                                                                                 "entity",
                                                                                                 "entities.orders",
                                                                                                 new ReplicationFactorsError.ExceedsCoreCount(5,
                                                                                                                                              3)));
    }

    /// R5 at deploy: a factor below 3 from a DEFAULT is refused for an entity section.
    @Test
    void entityDefaultedFactorBelowThree_failsTheDeploy() {
        var outcome = validate(entityJar(""), ReplicationContext.replicationContext(new ReplicationFactors(2, 1), 0));

        assertThat(causes(outcome)).contains(new ReplicationPreflight.ReplicationPreflightFailure("orders-slice",
                                                                                                 "entity",
                                                                                                 "entities.orders",
                                                                                                 new ReplicationFactorsError.ImplicitFactorBelowThree(2)));
    }

    @Test
    void entityDeclaredFactorBelowThree_isAccepted_withTheLoudWarning() {
        var warnings = validate(entityJar("replication_factor = 2"), BUILT_IN).unwrap();

        assertThat(warnings).extracting(StreamValidationWarning::rule).contains(ReplicationWarning.FACTOR_BELOW_THREE.code());
        assertThat(warnings).extracting(StreamValidationWarning::field).contains("[entities.orders]");
    }

    @Test
    void durableTopicConfirmationAboveFactor_failsTheDeploy() {
        var outcome = validate(topicJar("durability = \"durable\"\nreplication_factor = 2\nconfirmation_factor = 3"), BUILT_IN);

        assertThat(causes(outcome)).contains(new ReplicationPreflight.ReplicationPreflightFailure("orders-slice",
                                                                                                 "durable topic",
                                                                                                 "order-events",
                                                                                                 new ReplicationFactorsError.ConfirmationOutOfRange(2,
                                                                                                                                                    3)));
    }

    /// An ephemeral topic has no factors, so its section is not resolved at all.
    @Test
    void ephemeralTopic_isNotResolved() {
        assertThat(validate(topicJar("replication_factor = 0"), BUILT_IN).unwrap()).isEmpty();
    }

    @Test
    void builtInDefaults_raiseNoWarning() {
        assertThat(validate(entityJar(""), BUILT_IN).unwrap()).isEmpty();
    }

    private static Result<List<StreamValidationWarning>> validate(SliceJar jar, ReplicationContext context) {
        return ReplicationPreflight.validate(List.of(jar), Option.none(), context);
    }

    private static SliceJar entityJar(String keys) {
        var toml = "[entities.orders]\nkeyspace = \"orders\"\npartition_count = 8\n" + keys + "\n";
        var topology = new SliceTopology("orders-slice",
                                         "org.example:orders-slice:1.0.0",
                                         List.of(),
                                         List.of(),
                                         List.of(new SliceTopology.ResourceDep("DurableEntity", "entities.orders")),
                                         List.of(),
                                         List.of());

        return SliceJar.sliceJar(artifact(), List.of(topology), Option.some(toml));
    }

    private static SliceJar topicJar(String keys) {
        var toml = "[order-events]\ntopic_name = \"order-events\"\n" + keys + "\n";
        var topology = new SliceTopology("orders-slice",
                                         "org.example:orders-slice:1.0.0",
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(new SliceTopology.TopicPub("order-events", "org.example:order-events:1.0.0", "OrderPlaced")),
                                         List.of());

        return SliceJar.sliceJar(artifact(), List.of(topology), Option.some(toml));
    }

    private static Artifact artifact() {
        return Artifact.artifact("org.example:orders-slice:1.0.0").unwrap();
    }

    /// The pre-flight aggregates every section's refusal ([Result#allOf]), so a refusal is found among the causes.
    private static List<Object> causes(Result<?> outcome) {
        return outcome.fold(cause -> cause.stream().<Object> map(inner -> inner).toList(), _ -> List.of("accepted"));
    }
}
