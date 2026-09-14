// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1022 — `appendResource` is the one read-modify-write path onto the persisted cleanup ledger, shared
/// by the bootstrap recorder (`BootstrapPhaseProvision.recordProvisionedVm`) and the label sweep
/// (`BootstrapCleanup.recordSweptVms`). The sweep is the only operator-side code that ever observes a
/// CTM auto-heal replacement, because the replacement is created by the cluster LEADER in
/// `aether-deployment` on a cloud VM while this ledger lives on the operator's machine — so these two
/// callers are the whole set, and they must not diverge.
///
/// Runs against the REAL `~/.aether/clusters/<name>` path rather than a temp dir, for the reason
/// `BootstrapStatePersistencePermissionsTest` gives: `AETHER_DIR` is an interface constant resolved
/// from `user.home` at class load, so a temp-dir variant would test a different path from the one that
/// ships. A per-run random cluster name keeps concurrent `aether/cli` reactors — the normal arrangement
/// in a multi-tree workspace — from racing each other's files.
class BootstrapStatePersistenceAppendResourceTest {
    private final ClusterName clusterName = uniqueClusterName();

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return ClusterName.clusterName("append-1022-test-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    @AfterEach
    @SuppressWarnings("JBCT-EX-01")
    void cleanUp() throws Exception {
        var dir = BootstrapStatePersistence.AETHER_DIR.resolve(clusterName.value());

        Files.deleteIfExists(dir.resolve(BootstrapStatePersistence.STATE_FILE_NAME));
        Files.deleteIfExists(dir);
    }

    private static CreatedResource vm(String resourceId) {
        return CreatedResource.ProvisionedVm.provisionedVm("hetzner", resourceId, "core-source", "core");
    }

    /// The property the whole fix rests on: a VM the ledger did not hold is IN it afterwards, by id.
    @Test
    void appendResource_addsTheResource_toAnExistingLedger() {
        var _ = BootstrapStatePersistence.save(BootstrapState.initialState(clusterName,
                                                                           "config-hash",
                                                                           "2026-09-12T00:00:00Z"));

        BootstrapStatePersistence.appendResource(clusterName, vm("165556142"))
                                 .onFailure(cause -> fail("appending to an existing ledger must succeed: "
                                                          + cause.message()));

        assertThat(recordedVmIds()).as("the appended VM must be readable back from the persisted ledger")
                                   .containsExactly("165556142");
    }

    /// Appends accumulate rather than replace — the sweep records one VM at a time precisely so that a
    /// crash mid-loop still leaves every earlier id on disk, which only holds if each write preserves
    /// the last.
    @Test
    void appendResource_accumulates_acrossSuccessiveAppends() {
        var _ = BootstrapStatePersistence.save(BootstrapState.initialState(clusterName,
                                                                           "config-hash",
                                                                           "2026-09-12T00:00:00Z"));

        BootstrapStatePersistence.appendResource(clusterName, vm("165556191"))
                                 .onFailure(cause -> fail("first append must succeed: " + cause.message()));
        BootstrapStatePersistence.appendResource(clusterName, vm("165556321"))
                                 .onFailure(cause -> fail("second append must succeed: " + cause.message()));

        assertThat(recordedVmIds()).as("each append must preserve the records already on disk")
                                   .containsExactly("165556191", "165556321");
    }

    /// **It must never CREATE a ledger.** An absent state file means this cluster was not bootstrapped
    /// from this machine; fabricating one would mint a cluster record with no secret, no source handle
    /// and no credential mapping — a file that reads as authoritative and can reap nothing. The failure
    /// is also what carries the id to the operator's transcript, so it has to be a failure and not a
    /// silent no-op.
    @Test
    void appendResource_absentLedger_failsAndWritesNoFile() {
        BootstrapStatePersistence.appendResource(clusterName, vm("165556365"))
                                 .onSuccess(_ -> fail("appending to an absent ledger must fail, never fabricate one"))
                                 .onFailure(cause -> assertThat(cause.message())
                                         .as("the refusal must say the ledger is absent, not that a write failed")
                                         .contains("no bootstrap state is persisted"));

        assertThat(Files.exists(BootstrapStatePersistence.statePath(clusterName)))
                .as("no state file may be created for a cluster that has none")
                .isFalse();
    }

    /// An unreadable ledger and an absent one are different facts calling for different operator
    /// actions, and the torn bytes are the only surviving trace of the paid VMs — so the refusal must
    /// name which it got, and must not overwrite the file it could not read.
    @Test
    @SuppressWarnings("JBCT-EX-01")
    void appendResource_unreadableLedger_failsDistinctly_andLeavesTheBytesIntact() throws Exception {
        var path = BootstrapStatePersistence.statePath(clusterName);

        Files.createDirectories(path.getParent());
        Files.writeString(path, "{ this is not valid bootstrap state");

        BootstrapStatePersistence.appendResource(clusterName, vm("165556365"))
                                 .onSuccess(_ -> fail("an unparseable ledger must not be appended to"))
                                 .onFailure(cause -> assertThat(cause.message())
                                         .as("an unreadable ledger must not be reported as an absent one")
                                         .contains("the persisted ledger is unreadable"));

        assertThat(Files.readString(path)).as("the torn bytes are the only trace of the paid VMs — never overwrite them")
                                          .isEqualTo("{ this is not valid bootstrap state");
    }

    private List<String> recordedVmIds() {
        return BootstrapStatePersistence.read(clusterName)
                                        .or(Option.empty())
                                        .map(BootstrapState::createdResources)
                                        .or(List.of())
                                        .stream()
                                        .filter(CreatedResource.ProvisionedVm.class::isInstance)
                                        .map(CreatedResource.ProvisionedVm.class::cast)
                                        .map(CreatedResource.ProvisionedVm::resourceId)
                                        .toList();
    }
}
