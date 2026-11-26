/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The walks FlakeScan's `REPO_WALK` kind accepts without SourceWalk (#3703), each listed under the reason it cannot meet another
/// module's `target/` while a parallel build rewrites it.
///
/// The scan sees a walk call and the file it is in; it cannot see what the root expression evaluates to. So every walk call in a
/// test source is refused unless it is declared here, and the declaration is the review of its root. The list is a multiset, like
/// the flake allow-list: a walk added to a declared file raises the count and is refused again, and a declared walk that
/// disappears is reported stale (delete the entry). What a declaration CANNOT do is follow the root afterwards: a `@TempDir` walk
/// later pointed at the repository still passes until someone reads it, which is why each list names the origin of the root and
/// not merely the file.
///
/// Unlike the flake allow-list this one may grow, by a reviewed declaration under one of the three reasons. A scan of a repository
/// or multi-module root is never one of them: it uses `org.pragmatica.testlogging.SourceWalk`.
final class RepoWalkExceptions {
    /// The root is a directory the test itself creates (`@TempDir`, `createTempDirectory`, a data directory under one): no other
    /// module writes there. `M1MeasureTest` is here for the temp copy it walks, and under [#CLASSES] for the class directory it copies.
    private static final List<String> TEMP = List.of(
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/EmptiedPartitionRestartTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerAdvanceLeaseTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/PartitionProvenanceRecordingTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RefusedCutAlarmOrderTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RefusedCutAlarmOrderTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RefusedCutAlarmOrderTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RepairWitnessProbesTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RepairWitnessProbesTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RepairWitnessProbesTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/ReplicaWalCatchupTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/ReplicaWalTierPrefixCatchupTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamNoWalRestartOffsetTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerAckedMarkerTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerAckedMarkerTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerDivergentTailTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerDivergentTailTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerDivergentTailTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerDivergentTailTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerEpochStartsTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionStateLifetimeTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/RetentionFloorCrashWindowTest.java",
        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/V1866R2ProbeTest.java",
        "aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapExistingClusterGuardTest.java",
        "aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapLaunchOnceTest.java",
        "aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapLaunchOnceTest.java",
        "aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapLaunchOnceTest.java",
        "aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/LedgerDirExtension.java",
        "aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/SshKeyResolverTest.java",
        "aether/ember/src/test/java/org/pragmatica/aether/ember/EmberColdRestartAckedLossTripwireTest.java",
        "aether/ember/src/test/java/org/pragmatica/aether/ember/IdentityRestartHarness.java",
        "aether/ember/src/test/java/org/pragmatica/aether/ember/M1MeasureTest.java",
        "aether/ember/src/test/java/org/pragmatica/aether/ember/StreamTripwireSupport.java",
        "aether/environment/docker/src/test/java/org/pragmatica/aether/environment/docker/SingleFileTarTest.java",
        "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/MultiPartitionCrashDurabilityTest.java",
        "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamCrashDurabilityTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/http/AppHttpServerIngestGateTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/http/ForwardByReferenceScenario.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/StorageDegradeEventsTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/StorageFactoryVolumeSentinelTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/StreamDirectoryDurabilityTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/StreamSealerOnDiskTripwireTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/WalTruncationDurableBoundBootTest.java",
        "generated-project-gate/src/test/java/org/pragmatica/generatedgate/GeneratedProjectsBuildIT.java",
        "integrations/serialization/json/src/test/java/org/pragmatica/serialization/json/JsonCodecRegistryTest.java",
        "integrations/storage/src/test/java/org/pragmatica/storage/AppendLogInventoryTest.java",
        "integrations/storage/src/test/java/org/pragmatica/storage/AppendLogVolumeReadOnlyTest.java",
        "integrations/storage/src/test/java/org/pragmatica/storage/LargeObjectProbe.java",
        "integrations/storage/src/test/java/org/pragmatica/storage/LocalDiskTierScanVanishTest.java",
        "jbct/jbct-init/src/test/java/org/pragmatica/jbct/init/AiToolsInstallerCopyTest.java",
        "jbct/jbct-init/src/test/java/org/pragmatica/jbct/init/AiToolsInstallerPinTest.java",
        "jbct/jbct-lint/src/test/java/org/pragmatica/jbct/lint/cst/RootResolverPathFormTest.java",
        "jbct/slice-processor/src/test/java/org/pragmatica/jbct/slice/SliceProcessorTest.java",
        "test-logging/src/test/java/org/pragmatica/testlogging/SourceWalkTest.java",
        "test-logging/src/test/java/org/pragmatica/testlogging/SourceWalkTest.java"
    );

    /// The root is a fixed path inside the module's own sources (`src/main/java`, `src/test/java`, a resource or docs directory)
    /// or a fixed package of them: sources, never build output.
    private static final List<String> OWN_SOURCE = List.of(
        "aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/fsm/write/LeaderWriteScanTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/api/routes/ManagementRouteCoverageTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/docs/ManagementCurlPathsDocsTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/CheckpointFrameTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/ClusterTestPortsTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/DirectiveDeletersTripwireTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/EngineMarkerWritersTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/EngineMarkerWritersTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/deployment/FsmWarningCatalogueTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/deployment/WiringArchitectureTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/deployment/WiringArchitectureTest.java",
        "aether/pg-tools/pg-parser/src/test/java/org/pragmatica/aether/pg/parser/SqlCorpus.java"
    );

    /// The root is a class directory (the code source of a class the test loaded): complete before the forked JVM that runs the
    /// test started, and written by no module after that.
    private static final List<String> CLASSES = List.of(
        "aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/fsm/write/LeaderWriteReflectionTest.java",
        "aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/BytecodeReachability.java",
        "aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/ReactorRootsTest.java",
        "aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/ReflectiveConfigExemptions.java",
        "aether/ember/src/test/java/org/pragmatica/aether/ember/M1EchoPreflight.java",
        "aether/ember/src/test/java/org/pragmatica/aether/ember/M1MeasureTest.java",
        "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/KnownRedExtensionTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/api/routes/ManagementStatusCoverageTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/OldArityCallerTripwireTest.java",
        "aether/node/src/test/java/org/pragmatica/aether/node/ReplicaSeam.java",
        "integrations/cluster/src/test/java/org/pragmatica/cluster/state/kvstore/ReplicaSeam.java"
    );

    private RepoWalkExceptions() {}

    /// File (relative to the repository root) to the number of walk calls declared in it, over the three reasons.
    static Map<String, Integer> declaredWalks() {
        var counts = new HashMap<String, Integer>();

        TEMP.forEach(file -> counts.merge(file, 1, Integer::sum));
        OWN_SOURCE.forEach(file -> counts.merge(file, 1, Integer::sum));
        CLASSES.forEach(file -> counts.merge(file, 1, Integer::sum));
        return Map.copyOf(counts);
    }
}
