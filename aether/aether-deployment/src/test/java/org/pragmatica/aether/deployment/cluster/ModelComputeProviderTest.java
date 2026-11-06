// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// The fake provider reproduces what P-G1 measured at Hetzner (p-g1-report.md): the model is only evidence if the fake behaves as the probe saw.
/// One test per measured row.
class ModelComputeProviderTest {
    private VirtualTime time;
    private ModelComputeProvider provider;

    @BeforeEach
    void setUp() {
        time = new VirtualTime();
        provider = new ModelComputeProvider(time);
    }

    private ProvisionRequest request(String name) {
        var context = ProvisionContext.forBootstrap(ClusterName.clusterName("test-cluster").unwrap(), "core", SourceName.DEFAULT, name);
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx23", "core", context).unwrap();

        return ProvisionRequest.resolve(spec, provider.providerDefaults()).unwrap();
    }

    private Promise<InstanceInfo> create(String name) {
        return provider.createFrom(request(name));
    }

    private List<InstanceInfo> listed() {
        return provider.listInstances().await().unwrap();
    }

    /// P-G1 A2: a duplicate name while the server exists is refused with 409 and creates nothing.
    @Test
    void duplicateName_whileTheServerExists_isRefusedWith409_andCreatesNothing() {
        create("a");
        time.advance(1_000);
        var second = create("a");

        time.advance(1_000);

        assertThat(second.await().isFailure()).isTrue();
        assertThat(provider.serversNamed("a")).hasSize(1);
        assertThat(provider.calls("create-apply")).extracting(ModelComputeProvider.Call::outcome).containsExactly("created", "409");
    }

    /// P-G1 A3: two concurrent creates of one name, atomic: exactly one 201 and one 409.
    @Test
    void twoConcurrentCreatesOfOneName_areAtomicByDefault_oneCreatedOneRefused() {
        create("a");
        create("a");
        time.advance(1_000);

        assertThat(provider.serversNamed("a")).hasSize(1);
        assertThat(provider.calls("create-apply")).extracting(ModelComputeProvider.Call::outcome).containsExactlyInAnyOrder("created", "409");
    }

    /// The window the probe did not see in five races, opened by a switch: both concurrent creates pass the uniqueness check.
    @Test
    void twoConcurrentCreatesOfOneName_withTheNonAtomicWindowOpen_bothSucceed() {
        provider.atomic = false;
        create("a");
        create("a");
        time.advance(1_000);

        assertThat(provider.serversNamed("a")).hasSize(2);
    }

    /// P-G1 A4: the same name in upper case is a distinct server.
    @Test
    void names_areCaseSensitive() {
        create("node");
        create("NODE");
        time.advance(1_000);

        assertThat(provider.servers()).extracting(ModelComputeProvider.Server::name).containsExactlyInAnyOrder("node", "NODE");
    }

    /// P-G1 A5: once the server is deleted the name is free again.
    @Test
    void aName_isReusable_onceItsServerIsDeleted() {
        create("a");
        time.advance(1_000);
        provider.terminate(provider.servers().getFirst().id());
        create("a");
        time.advance(1_000);

        assertThat(provider.serversNamed("a")).hasSize(1);
        assertThat(provider.calls("create-apply")).extracting(ModelComputeProvider.Call::outcome).containsExactly("created", "created");
    }

    /// P-G1 A6/A7: a created server is listed only after the visibility lag (0.6-0.9 s), though its name is taken at once.
    @Test
    void aCreatedServer_isListedOnlyAfterTheVisibilityLag_butItsNameIsTakenAtOnce() {
        provider.visibilityLagMs = 750;
        create("a");
        time.advance(450);

        assertThat(provider.serversNamed("a")).hasSize(1);
        assertThat(listed()).as("created, not yet listed").isEmpty();

        create("a");
        time.advance(450);

        assertThat(provider.calls("create-apply")).extracting(ModelComputeProvider.Call::outcome).containsExactly("created", "409");

        time.advance(750);

        assertThat(listed()).hasSize(1);
    }

    /// P-G1 A7, the cutoff that created a server: the caller times out although the server exists.
    @Test
    void aLostReply_appliedAndDropped_leavesTheServer_andTheCallerTimesOut() {
        provider.fate = (name, attempt) -> ModelComputeProvider.Fate.APPLIED_REPLY_LOST;
        var reply = create("a");

        time.advance(10_000);
        assertThat(reply.isResolved()).as("no reply yet").isFalse();
        time.advance(30_000);

        assertThat(reply.await().isFailure()).isTrue();
        assertThat(provider.serversNamed("a")).hasSize(1);
    }

    /// P-G1 A7, the cutoffs that created nothing: the caller times out and no server exists.
    @Test
    void aLostReply_droppedBeforeItWasApplied_leavesNothing_andTheCallerTimesOut() {
        provider.fate = (name, attempt) -> ModelComputeProvider.Fate.NOT_APPLIED_REPLY_LOST;
        var reply = create("a");

        time.advance(40_000);

        assertThat(reply.await().isFailure()).isTrue();
        assertThat(provider.servers()).isEmpty();
    }

    /// P-G1 A8: labels carry no uniqueness; two servers may share every label.
    @Test
    void labels_areNotUnique() {
        create("a");
        create("b");
        time.advance(2_000);

        var labelsOfA = provider.serversNamed("a").getFirst().labels();
        var labelsOfB = provider.serversNamed("b").getFirst().labels();

        assertThat(labelsOfA.get(ModelComputeProvider.CLUSTER_TAG)).isEqualTo(labelsOfB.get(ModelComputeProvider.CLUSTER_TAG));
        assertThat(listed().stream().map(InstanceInfo::tags).filter(tags -> tags.containsKey(ModelComputeProvider.CLUSTER_TAG)).count()).isEqualTo(2);
    }

    @Test
    void anOutage_failsEveryCall_andIsRecordedInTheAppendOnlyLog() {
        provider.outage = true;

        assertThat(create("a").await().isFailure()).isTrue();
        assertThat(provider.listInstances().await().isFailure()).isTrue();
        assertThat(provider.callLog()).extracting(ModelComputeProvider.Call::outcome).containsExactly("outage", "outage");
        assertThat(provider.callLog()).extracting(ModelComputeProvider.Call::seq).isSorted();
    }

    /// A process that is gone makes no call: nothing is logged and nothing resolves.
    @Test
    void aDeadCaller_makesNoCall_andNeverGetsAnAnswer() {
        var dead = provider.viewFor(() -> false);
        var reply = dead.createFrom(request("a"));

        time.advance(60_000);

        assertThat(reply.isResolved()).isFalse();
        assertThat(provider.callLog()).isEmpty();
    }
}
