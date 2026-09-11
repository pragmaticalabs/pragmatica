// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import org.pragmatica.aether.cli.cluster.CreatedResource.ProvisionedVm;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterDestroyCommandTest {

    private static final ClusterName CLUSTER_NAME = clusterName("test-cluster").unwrap();

    /// #998 — `fetchNodeIds` now takes the destroy TARGET's endpoint rather than re-reading
    /// [ClusterHttpClient#resolveEndpoint], so a target with no recorded endpoint cannot silently query the
    /// ACTIVE cluster. This is the value the stubbed override also carries, so the announced endpoint and
    /// the requested one are the same by construction.
    private static final Result<String> TARGET_ENDPOINT = Result.success("https://10.255.255.1:8080");

    private Function<ClusterName, Result<Option<BootstrapState>>> originalLoader;

    private Function<BootstrapState, Result<Unit>> originalCleaner;

    private BiFunction<BootstrapState, ClusterName, Result<Unit>> originalSweeper;

    /// #997 — `vmSweeper` had NO save/restore and NO test assignment anywhere, which is precisely why the
    /// sweep's position in the teardown order was unpinned and the defect was reachable only by inspection.
    private BiFunction<BootstrapState, ClusterName, Result<Unit>> originalVmSweeper;

    @BeforeEach
    void saveStaticSeams() {
        originalLoader = ClusterDestroyCommand.stateLoader;
        originalCleaner = ClusterDestroyCommand.resourceCleaner;
        originalSweeper = ClusterDestroyCommand.sshKeySweeper;
        originalVmSweeper = ClusterDestroyCommand.vmSweeper;
        ClusterDestroyCommand.sshKeySweeper = (state, name) -> Result.unitResult();
    }

    @AfterEach
    void restoreStaticSeams() {
        ClusterDestroyCommand.stateLoader = originalLoader;
        ClusterDestroyCommand.resourceCleaner = originalCleaner;
        ClusterDestroyCommand.sshKeySweeper = originalSweeper;
        ClusterDestroyCommand.vmSweeper = originalVmSweeper;
    }

    private static BootstrapState stateWithVms(int vmCount) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {
            phases.put(phase, PhaseStatus.COMPLETED);
        }
        var resources = new ArrayList<CreatedResource>();
        for (var i = 0; i <vmCount; i++) {
            resources.add(new ProvisionedVm("hetzner", "vm-" + i, "core-source", "core"));
        }
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             List.copyOf(resources),
                                             List.of(),
                                             List.of());
    }

    private static BootstrapState emptyState() {
        return BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-05-01T00:00:00Z");
    }

    /// #1023 — a ledger carrying the addresses [BootstrapPhaseCollect] records for EVERY provisioned node,
    /// which is the data the endpoint SPOF is repaired from. It is not a new field; `stateWithVms` simply
    /// never populated it, which is why no test could have caught the single-address endpoint.
    private static BootstrapState stateWithAddresses(String... addresses) {
        return BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-05-01T00:00:00Z")
                             .withCollectedAddresses(List.of(addresses));
    }

    private static BootstrapState stateWithSecret(String secret) {
        return BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-05-01T00:00:00Z")
                             .withClusterSecret(secret);
    }

    @Nested
    class CleanupInvocation {

        @Test
        void destroy_invokesBootstrapCleanup_whenStateFileExists() {
            var captured = new AtomicReference<BootstrapState>();
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(2)));
            ClusterDestroyCommand.resourceCleaner = state -> {
                captured.set(state);
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(2, captured.get().createdResources().size());
        }

        @Test
        void destroy_skipsCleanup_whenStateFileMissing() {
            var calls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> Result.success(none());
            ClusterDestroyCommand.resourceCleaner = state -> {
                calls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, calls.get());
        }

        @Test
        void destroy_skipsCleanup_whenBootstrapStateHasNoResources() {
            var calls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(emptyState()));
            ClusterDestroyCommand.resourceCleaner = state -> {
                calls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, calls.get());
        }

        /// #997 — the label sweep must STILL run when the ledger records nothing. That is not an edge case:
        /// an empty ledger is exactly #994's observed state, where every VM that was created is unrecorded
        /// and billing, so the label selector is the only thing that can find them. The reorder moved the
        /// sweep inside the ledger walk, and the walk is skipped entirely for an empty ledger — so without
        /// this branch the fix would have deleted the sweep for the one case it exists to serve.
        @Test
        void destroy_stillSweepsLabelledVms_whenBootstrapStateHasNoResources() {
            var sweeps = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(emptyState()));
            ClusterDestroyCommand.vmSweeper = (state, name) -> sweepCounted(sweeps);
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok, "a clean sweep over an empty ledger is a successful cleanup");
            assertEquals(1, sweeps.get(), "exactly one label sweep, even with nothing in the ledger");
        }

        /// And a sweep that fails there must fail the cleanup, so the registry entry is KEPT — the entry is
        /// the operator's only remaining handle on VMs the ledger never recorded (#521).
        @Test
        void destroy_reportsCleanupFailure_whenTheSweepFailsOverAnEmptyLedger() {
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(emptyState()));
            ClusterDestroyCommand.vmSweeper = (state, name) -> new TestCause("swept VM 42 would not delete").result();
            var command = new ClusterDestroyCommand();

            assertFalse(command.cleanupCloudResources(CLUSTER_NAME),
                        "unreaped labelled VMs cannot be reported as a successful cleanup");
        }

        private static Result<Unit> sweepCounted(AtomicInteger sweeps) {
            sweeps.incrementAndGet();

            return Result.unitResult();
        }

        @Test
        void destroy_keepResourcesFlag_skipsCleanup() {
            var loaderCalls = new AtomicInteger(0);
            var cleanerCalls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> {
                loaderCalls.incrementAndGet();
                return Result.success(some(stateWithVms(3)));
            };
            ClusterDestroyCommand.resourceCleaner = state -> {
                cleanerCalls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();
            command.setKeepResources(true);

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, loaderCalls.get());
            assertEquals(0, cleanerCalls.get());
        }

        @Test
        void destroy_partialCleanupFailure_returnsFalseButDoesNotThrow() {
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(1)));
            ClusterDestroyCommand.resourceCleaner = state -> new TestCause("api error").result();
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertFalse(ok);
        }

        @Test
        void destroy_passesCorrectClusterNameToLoader() {
            var capturedName = new AtomicReference<ClusterName>();
            ClusterDestroyCommand.stateLoader = name -> {
                capturedName.set(name);
                return Result.success(none());
            };
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(CLUSTER_NAME, capturedName.get());
        }

        @Test
        void destroy_invokesSshKeySweeper_afterStateCleanup() {
            var sweepClusterName = new AtomicReference<ClusterName>();
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(2)));
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            ClusterDestroyCommand.sshKeySweeper = (state, clusterName) -> {
                sweepClusterName.set(clusterName);
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(CLUSTER_NAME, sweepClusterName.get(),
                         "sweeper must be invoked with the cluster name from the loaded bootstrap state");
        }

        @Test
        void destroy_sweeperFailure_returnsFalseButDoesNotThrow() {
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(1)));
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            ClusterDestroyCommand.sshKeySweeper = (state, clusterName) -> new TestCause("sweep api error").result();
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertFalse(ok, "a failed ssh-key sweep must surface as cleanup failure");
        }

        @Test
        void destroy_keepResourcesFlag_skipsSweep() {
            var sweepCalls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(3)));
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            ClusterDestroyCommand.sshKeySweeper = (state, clusterName) -> {
                sweepCalls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();
            command.setKeepResources(true);

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, sweepCalls.get(), "--keep-resources must short-circuit before the sweeper runs");
        }
    }

    @Nested
    class ClusterOverrideValidation {

        private PrintStream originalErr;

        private ByteArrayOutputStream errCapture;

        @BeforeEach
        void redirectErr() {
            originalErr = System.err;
            errCapture = new ByteArrayOutputStream();
            System.setErr(new PrintStream(errCapture));
        }

        @AfterEach
        void restoreErr() {
            System.setErr(originalErr);
        }

        @Test
        void destroy_invalidClusterOverrideWithSpecialChars_returnsUsageExitCode() {
            var command = new ClusterDestroyCommand();
            command.setClusterNameOverride("INVALID@NAME");

            var exitCode = command.call();

            assertEquals(ExitCode.USAGE, exitCode);
            assertTrue(errCapture.toString().contains("Invalid --cluster value"),
                       "Expected validation error in stderr, got: " + errCapture);
        }

        @Test
        void destroy_invalidClusterOverrideStartingWithDigit_returnsUsageExitCode() {
            var command = new ClusterDestroyCommand();
            command.setClusterNameOverride("9bad-name");

            var exitCode = command.call();

            assertEquals(ExitCode.USAGE, exitCode);
        }

        @Test
        void destroy_invalidClusterOverrideUppercase_returnsUsageExitCode() {
            var command = new ClusterDestroyCommand();
            command.setClusterNameOverride("UpperCase");

            var exitCode = command.call();

            assertEquals(ExitCode.USAGE, exitCode);
        }

        @Test
        void destroy_clusterOverrideParsedFromCli() {
            var command = new ClusterDestroyCommand();
            new CommandLine(command).parseArgs("--cluster", "my-cluster", "--yes", "--keep-resources");

            assertTrue(command.cleanupCloudResources(clusterName("my-cluster").unwrap()),
                       "Override + --keep-resources should compose: cleanup short-circuits to ok");
        }

        @Test
        void destroy_clusterOverrideAndKeepResourcesCompose() {
            var loaderCalls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> {
                loaderCalls.incrementAndGet();
                return Result.success(some(stateWithVms(2)));
            };
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();

            var command = new ClusterDestroyCommand();
            new CommandLine(command).parseArgs("--cluster", "other-cluster", "--keep-resources", "--yes");

            var ok = command.cleanupCloudResources(clusterName("other-cluster").unwrap());

            assertTrue(ok);
            assertEquals(0, loaderCalls.get(), "--keep-resources must short-circuit before loader is consulted");
        }

        @Test
        void destroy_clusterOverrideRoutesNameToStateLoader() {
            var capturedName = new AtomicReference<ClusterName>();
            ClusterDestroyCommand.stateLoader = name -> {
                capturedName.set(name);
                return Result.success(none());
            };
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();

            var command = new ClusterDestroyCommand();
            new CommandLine(command).parseArgs("--cluster", "named-cluster", "--yes");

            command.cleanupCloudResources(clusterName("named-cluster").unwrap());

            assertEquals(clusterName("named-cluster").unwrap(), capturedName.get(),
                         "Override name must be routed to BootstrapStatePersistence loader");
        }
    }

    record TestCause(String message) implements Cause {}

    /// #521 — the money path. `destroy` used to remove the registry entry and exit 0 even when cloud
    /// cleanup had failed, so the operator lost the cluster handle while its VMs kept billing.
    @Nested
    class RegistryHonestyAndExitCode {

        private static final String ENDPOINT = "https://cluster.example:8080";

        private BiFunction<ClusterRegistry, ClusterName, Result<ClusterRegistry>> originalRemover;

        private List<String> removalCalls;

        @BeforeEach
        void captureRemovals() {
            originalRemover = ClusterDestroyCommand.registryRemover;
            removalCalls = new ArrayList<>();
            ClusterDestroyCommand.registryRemover = (registry, name) -> recordRemoval(registry, name);
        }

        @AfterEach
        void restoreRemover() {
            ClusterDestroyCommand.registryRemover = originalRemover;
        }

        private Result<ClusterRegistry> recordRemoval(ClusterRegistry registry, ClusterName name) {
            removalCalls.add(name.value());
            return registry.remove(name.value());
        }

        private static ClusterRegistry registryWith(ClusterName name) {
            return ClusterRegistry.clusterRegistry(Path.of("unused-in-test.toml"),
                                                   some(name.value()),
                                                   List.of(new ClusterRegistry.ClusterEntry(name.value(),
                                                                                            ENDPOINT,
                                                                                            none())));
        }

        private static Result<Integer> finalizeWith(boolean cleanupOk) {
            return ClusterDestroyCommand.finalizeDestruction(registryWith(CLUSTER_NAME),
                                                             CLUSTER_NAME,
                                                             cleanupOk,
                                                             List.of("node-1"),
                                                             List.of(new ClusterDestroyCommand.NodeResult("node-1", true)),
                                                             List.of(new ClusterDestroyCommand.NodeResult("node-1", true)));
        }

        @Test
        void finalizeDestruction_failedCleanup_keepsRegistryEntry() {
            finalizeWith(false).onFailure(cause -> fail("summary must still be produced: " + cause.message()));

            assertTrue(removalCalls.isEmpty(),
                       "a cluster whose VMs may still be billing must keep its registry entry so destroy can be retried");
        }

        @Test
        void finalizeDestruction_failedCleanup_returnsNonZeroExitCode() {
            finalizeWith(false).onFailure(cause -> fail(cause.message()))
                               .onSuccess(code -> assertEquals(ExitCode.CLEANUP_FAILED, (int) code,
                                                               "failed cloud cleanup must exit non-zero"));
        }

        @Test
        void finalizeDestruction_successfulCleanup_removesRegistryEntryAndSucceeds() {
            finalizeWith(true).onFailure(cause -> fail(cause.message()))
                              .onSuccess(code -> assertEquals(ExitCode.SUCCESS, (int) code));

            assertEquals(List.of(CLUSTER_NAME.value()), removalCalls,
                         "a successful cleanup removes the registry entry");
        }

        @Test
        void finalizeDestruction_keepResources_removesRegistryEntryAndSucceeds() {
            // --keep-resources routes cleanupCloudResources to `true`: skipping termination is the
            // explicitly acknowledged path, so removal + success is the correct outcome there.
            var command = new ClusterDestroyCommand();
            command.setKeepResources(true);

            var cleanupOk = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(cleanupOk);
            finalizeWith(cleanupOk).onFailure(cause -> fail(cause.message()))
                                   .onSuccess(code -> assertEquals(ExitCode.SUCCESS, (int) code));
            assertEquals(List.of(CLUSTER_NAME.value()), removalCalls,
                         "--keep-resources is the acknowledged path: the registry entry is still removed");
        }

        @Test
        void destroy_abortedAtConfirmationPrompt_returnsNonZeroExitCode() {
            var originalIn = System.in;
            var originalOut = System.out;
            var outCapture = new ByteArrayOutputStream();

            System.setIn(new ByteArrayInputStream(new byte[0]));
            System.setOut(new PrintStream(outCapture));
            try {
                var command = new ClusterDestroyCommand();
                command.setClusterNameOverride("aborted-cluster");

                var exitCode = command.call();

                assertTrue(outCapture.toString().contains("Aborted."),
                           "expected the abort path, got: " + outCapture);
                assertEquals(ExitCode.ERROR, exitCode,
                             "an aborted destroy must exit non-zero — exiting 0 is indistinguishable from success");
            } finally {
                System.setIn(originalIn);
                System.setOut(originalOut);
            }
        }
    }

    /// Guards the assumption every exit-code test rests on: picocli propagates the `Callable<Integer>`
    /// return value as the process exit code (`AetherCli.main` passes it straight to `System.exit`).
    @Nested
    class ExitCodePropagation {

        @Test
        void execute_propagatesCallableReturnValue_asProcessExitCode() {
            var exitCode = new CommandLine(new FixedCodeCommand()).execute();

            assertEquals(ExitCode.CLEANUP_FAILED, exitCode,
                         "a non-zero value returned by call() must reach the process exit code");
        }

        @Command(name = "fixed")
        static class FixedCodeCommand implements Callable<Integer> {
            @Override
            public Integer call() {
                return ExitCode.CLEANUP_FAILED;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // #995 — progress output. A teardown command that emits nothing is the one case where an operator
    // cannot tell "working" from "wedged", and the cost of guessing wrong is a half-destroyed cluster.
    // ---------------------------------------------------------------------------------------------

    /// Fails every request immediately, so the node-enumeration path is exercised without waiting out the
    /// real 130s ceiling. This pins the OBSERVABILITY properties that made #995 invisible — that the
    /// request is announced before it blocks and that its failure is reported — not the latency itself.
    private record FailingHttpOperations(String message) implements HttpOperations {
        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            return Causes.cause(message).promise();
        }
    }

    @Nested
    class DestroyProgressOutput {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        private PrintStream originalOut;

        private PrintStream originalErr;

        private HttpOperations originalHttpOps;

        private String originalEndpoint;

        @BeforeEach
        void captureStreamsAndStubHttp() {
            originalOut = System.out;
            originalErr = System.err;
            originalHttpOps = ClusterHttpClient.HTTP_OPS_REF.get();
            originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            ClusterHttpClient.setEndpointOverride("https://10.255.255.1:8080");
            ClusterHttpClient.HTTP_OPS_REF.set(new FailingHttpOperations("connection timed out"));
        }

        @AfterEach
        void restoreStreamsAndHttp() {
            System.setOut(originalOut);
            System.setErr(originalErr);
            ClusterHttpClient.HTTP_OPS_REF.set(originalHttpOps);
            ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        }

        private String stdout() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        /// #995 expectation 1+2. The node list is the FIRST thing destroy does and the last thing it used to
        /// talk about: one management request with a 130s ceiling, printed nothing before it and nothing
        /// after it failed. The announcement has to precede the request, or it cannot be read during it.
        @Test
        void fetchNodeIds_announcesTheRequestAndItsTimeout_beforeBlocking() {
            var nodeIds = new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT);

            assertTrue(nodeIds.isFailure(),
                       () -> "precondition: the stubbed request fails, and #998 requires that failure to be "
                             + "RETURNED rather than flattened into an empty list; got: " + nodeIds);
            assertTrue(stdout().contains("[Phase 1/5: ENUMERATE_NODES]"),
                       () -> "the phase must be announced in bootstrap's own shape; got:\n" + stdout());
            assertTrue(stdout().contains("https://10.255.255.1:8080"),
                       () -> "and name the endpoint it is waiting on; got:\n" + stdout());
            assertTrue(stdout().contains("timeout " + ClusterHttpClient.REQUEST_TIMEOUT.get().toSeconds() + "s"),
                       () -> "the announced timeout must be read from the timeout actually in force, so it "
                             + "cannot drift from the code; got:\n" + stdout());
        }

        /// The announcement is worthless if it is printed after the wait. Asserted positionally: the phase
        /// line must appear at character zero of stdout, before anything the request produced.
        @Test
        void fetchNodeIds_printsThePhaseLineFirst_notAfterTheRequestReturns() {
            new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT);

            assertEquals(0,
                         stdout().indexOf("[Phase 1/5: ENUMERATE_NODES]"),
                         () -> "the announcement must be the FIRST output, not a retrospective note; got:\n" + stdout());
        }

        /// `.or(List.of())` discarded this failure entirely, so a cluster whose management endpoint was
        /// unreachable looked exactly like a cluster with no nodes — and destroy went on to report success.
        @Test
        void fetchNodeIds_reportsTheFailure_insteadOfSilentlyTreatingItAsNoNodes() {
            new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT);

            assertTrue(stderr().contains("could not list cluster nodes"),
                       () -> "the failure must be reported, not swallowed; got:\n" + stderr());
            assertTrue(stderr().contains("connection timed out"),
                       () -> "including the underlying cause; got:\n" + stderr());
            assertTrue(stderr().contains("without a graceful drain"),
                       () -> "and its CONSEQUENCE: an empty node list means drain and shutdown are skipped; "
                             + "got:\n" + stderr());
        }

        /// Positive control for the two assertions above: the same seam, a SUCCEEDING request. Without it,
        /// "stderr contains the warning" proves only that some failure path ran, and the count of nodes
        /// reported could be a constant.
        @Test
        void fetchNodeIds_reportsTheNodeCount_andWarnsNothing_whenTheRequestSucceeds() {
            ClusterHttpClient.HTTP_OPS_REF.set(new FixedBodyHttpOperations("""
                [{"nodeId":"core-0"},{"nodeId":"core-1"}]
                """));

            var nodeIds = new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT);

            assertEquals(Result.success(List.of("core-0", "core-1")), nodeIds, "the stubbed body must parse");
            assertTrue(stdout().contains("2 node(s) reported by the cluster"),
                       () -> "a successful enumeration reports its own count; got:\n" + stdout());
            assertFalse(stderr().contains("could not list cluster nodes"),
                        () -> "and warns about nothing — which is what makes the failure case above a real "
                              + "signal rather than an always-on line; got:\n" + stderr());
        }

        /// #994 verification finding SF-3 — **renamed.** This was called
        /// `drainAllNodes_announcesTheDrainPhaseAndItsCeiling` and asserted the phase line and
        /// "Nothing to drain"; it passed an EMPTY node list, so it never entered the branch that prints a
        /// ceiling at all. The name claimed coverage the body did not have, which is worse than no test,
        /// because a reader auditing #995's deliverables would tick the ceiling off and move on. The ceiling
        /// is pinned by the sibling below, against a non-empty list.
        @Test
        void drainAllNodes_announcesThePhase_whenThereIsNothingToDrain() {
            new ClusterDestroyCommand().drainAllNodes(List.of());

            assertTrue(stdout().contains("[Phase 2/5: DRAIN_NODES]"),
                       () -> "drain must announce itself even with nothing to do, so a silent stretch is never "
                             + "ambiguous; got:\n" + stdout());
            assertTrue(stdout().contains("Nothing to drain"),
                       () -> "an empty node list is a statement, not silence; got:\n" + stdout());
        }

        /// #995 expectation 2, actually exercised: "if it can take minutes, say so before the wait begins",
        /// with the figures that bound the wait. Only the NON-EMPTY branch prints a ceiling, and no test
        /// entered it — so stripping the per-node ceiling line left all 724 tests green (measured, probe V9).
        ///
        /// The stubbed drain request fails immediately, so the announcement is asserted without waiting out
        /// the real 120 seconds; the ceiling is read from the enforcing constants rather than restated, so the
        /// announcement cannot drift from the code.
        @Test
        void drainAllNodes_announcesThePerNodeCeilingAndPollInterval_whenThereAreNodesToDrain() {
            var results = new ClusterDestroyCommand().drainAllNodes(List.of("core-0"));

            assertEquals(1, results.size(), "precondition: one node was processed");
            assertFalse(results.getFirst().success(),
                        "precondition: the stubbed drain POST fails, so the 120s poll loop is never entered");
            assertTrue(stdout().contains("Draining 1 node(s), up to " + ClusterDestroyCommand.DRAIN_TIMEOUT_SECONDS + "s each"),
                       () -> "the phase line must name the per-node budget and the worst-case total; got:\n" + stdout());
            assertTrue(stdout().contains("Draining node core-0 (waiting up to "
                                         + ClusterDestroyCommand.DRAIN_TIMEOUT_SECONDS
                                         + "s for DECOMMISSIONED, polling every "
                                         + ClusterDestroyCommand.DRAIN_POLL_INTERVAL_MS
                                         + "ms)"),
                       () -> "and each node must name its own ceiling BEFORE its wait begins — this is the line "
                             + "that turns a two-minute silence into a stated wait; got:\n" + stdout());
            assertFalse(stdout().contains("Nothing to drain"),
                        () -> "the empty-list wording must not appear for a non-empty list, or the two branches "
                              + "are indistinguishable; got:\n" + stdout());
        }

        @Test
        void shutdownAllNodes_announcesTheShutdownPhase() {
            new ClusterDestroyCommand().shutdownAllNodes(List.of());

            assertTrue(stdout().contains("[Phase 3/5: SHUTDOWN_NODES]"),
                       () -> "got:\n" + stdout());
        }

        /// The cleanup phase is where the minutes actually go — per-resource deletes plus a firewall retry
        /// budget. It must say so before it starts, and name the retry budget from the constants that bound it.
        @Test
        void cleanupCloudResources_announcesThePhaseAndTheFirewallRetryBudget() {
            ClusterDestroyCommand.stateLoader = name -> Result.success(none());

            new ClusterDestroyCommand().cleanupCloudResources(CLUSTER_NAME);

            assertTrue(stdout().contains("[Phase 4/5: CLOUD_CLEANUP]"), () -> "got:\n" + stdout());
            assertTrue(stdout().contains("retried up to " + BootstrapCleanup.FIREWALL_DELETE_ATTEMPTS + " times"),
                       () -> "the retry budget must come from the constant that enforces it; got:\n" + stdout());
        }

        @Test
        void finalizeDestruction_announcesTheRegistryPhase_andWhyTheEntryIsKept_whenCleanupFailed() {
            ClusterDestroyCommand.finalizeDestruction(ClusterRegistry.clusterRegistry(Path.of("unused-registry.toml"), none(), List.of()),
                                                      CLUSTER_NAME,
                                                      false,
                                                      List.of(),
                                                      List.of(),
                                                      List.of());

            assertTrue(stdout().contains("[Phase 5/5: REGISTRY]"), () -> "got:\n" + stdout());
            assertTrue(stdout().contains("Keeping the registry entry"),
                       () -> "the reason the entry survives is the operator's handle on billing resources — "
                             + "saying it beside the decision is the point; got:\n" + stdout());
        }

        /// #994 verification finding SF-3 — **no test reached `performDestruction` at all.** The three phase
        /// methods were each driven individually and every test entering via `call()` returned early, so
        /// deleting `announceDestroyPlan(clusterName)` — #995's entire "say so before the wait begins"
        /// deliverable — left all 724 tests green (measured, probe V3), and the PHASE ORDER was unpinned for
        /// the same reason.
        ///
        /// Driven end to end with a SUCCEEDING enumeration of an empty cluster, no bootstrap state (so
        /// cleanup is a no-op) and a no-op registry remover. What is left is exactly the observable sequence
        /// an operator reads.
        ///
        /// #998 — this test used to reach phases 2-5 through the FAILING HTTP stub, because a failed
        /// enumeration was flattened into an empty node list. That fixture is no longer valid: a failed
        /// enumeration now refuses. The property being pinned (all five phases, in order) is a property of
        /// the SUCCESS path, so the fixture now returns `[]` — a cluster that genuinely has no nodes. The two
        /// states the old fixture conflated are exactly the two #998 requires to be distinguishable, which is
        /// why the fixture was wrong and not the assertion.
        @Test
        void performDestruction_announcesThePlanFirst_thenRunsAllFivePhasesInOrder() {
            ClusterHttpClient.HTTP_OPS_REF.set(new FixedBodyHttpOperations("[]"));
            ClusterDestroyCommand.stateLoader = name -> Result.success(none());
            var originalRemover = ClusterDestroyCommand.registryRemover;

            ClusterDestroyCommand.registryRemover = (registry, name) -> Result.success(registry);
            try {
                var exitCode = new ClusterDestroyCommand().performDestruction(registryWithNoEntries(),
                                                                             CLUSTER_NAME,
                                                                             TARGET_ENDPOINT);

                exitCode.onFailure(cause -> fail("destroy must produce a summary: " + cause.message()))
                        .onSuccess(code -> assertEquals(ExitCode.SUCCESS, (int) code,
                                                        "nothing failed, so the command must exit 0"));
                assertEquals(0,
                             stdout().indexOf("Destroying cluster '" + CLUSTER_NAME + "' in 5 phases"),
                             () -> "the plan must be the FIRST thing printed — announced after the first wait it "
                                   + "cannot be read during it; got:\n" + stdout());
                assertTrue(stdout().contains("This can take minutes"),
                           () -> "#995 expectation 3: say up front that it can take minutes; got:\n" + stdout());
                assertTrue(stdout().contains("node enumeration waits up to "
                                             + ClusterHttpClient.REQUEST_TIMEOUT.get().toSeconds() + "s"),
                           () -> "with the enumeration ceiling read from the timeout in force; got:\n" + stdout());
                assertTrue(stdout().contains("each node's drain up to " + ClusterDestroyCommand.DRAIN_TIMEOUT_SECONDS + "s"),
                           () -> "and the drain ceiling read from the constant that enforces it; got:\n" + stdout());
                assertPhasesInOrder();
            } finally {
                ClusterDestroyCommand.registryRemover = originalRemover;
            }
        }

        /// Order, not merely presence: the announced plan describes a sequence, and a destroy that deleted
        /// cloud resources before draining would print the same five lines.
        private void assertPhasesInOrder() {
            var transcript = stdout();
            var phases = List.of("[Phase 1/5: ENUMERATE_NODES]",
                                 "[Phase 2/5: DRAIN_NODES]",
                                 "[Phase 3/5: SHUTDOWN_NODES]",
                                 "[Phase 4/5: CLOUD_CLEANUP]",
                                 "[Phase 5/5: REGISTRY]");
            var previous = -1;

            for (var phase : phases) {
                var at = transcript.indexOf(phase);

                assertTrue(at > previous,
                           () -> "expected " + phase + " after the previous phase, in:\n" + transcript);
                previous = at;
            }
        }

        private static ClusterRegistry registryWithNoEntries() {
            return ClusterRegistry.clusterRegistry(Path.of("unused-registry.toml"), none(), List.of());
        }

        /// #994 verification finding SF-1 — **an unreadable ledger is not an empty cluster.** Under the old
        /// `Option`-valued seam a torn `bootstrap-state.json` arrived as empty, which this method read as "no
        /// bootstrap state — skipping resource cleanup", returned `true` for, and which then removed the
        /// registry entry and exited 0 while every server the ledger named kept billing. The torn file is
        /// reachable by exactly the failure both incidents ended in: the operator killing bootstrap mid-write.
        @Test
        void cleanupCloudResources_refusesToReportDone_whenTheLedgerIsPresentButUnreadable() {
            ClusterDestroyCommand.stateLoader = name -> new TestCause("state file is not valid JSON").result();

            var ok = new ClusterDestroyCommand().cleanupCloudResources(CLUSTER_NAME);

            assertFalse(ok,
                        "a ledger that cannot be read names no resources, so cleanup CANNOT have succeeded — "
                        + "reporting true removes the registry entry, the operator's last handle on billing VMs");
            assertTrue(stderr().contains("REFUSING"),
                       () -> "and the refusal must be stated; got:\n" + stderr());
            assertTrue(stderr().contains("cloud-reaper.sh"),
                       () -> "with the recovery action; got:\n" + stderr());
        }

        /// Positive control, and it is the load-bearing arm: an ABSENT ledger must still be the cheerful path
        /// — nothing was created, so there is nothing to reap and `destroy` should finish. Without this,
        /// "returns false" would also be satisfied by a method that refuses whenever there is no state.
        @Test
        void cleanupCloudResources_reportsDone_whenThereIsNoLedgerAtAll() {
            ClusterDestroyCommand.stateLoader = name -> Result.success(none());

            var ok = new ClusterDestroyCommand().cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok, "no state file means nothing was created — destroy has nothing to reap and must finish");
            assertTrue(stdout().contains("No bootstrap state"),
                       () -> "and it says which of the two cases this is; got:\n" + stdout());
            assertFalse(stderr().contains("REFUSING"),
                        () -> "absent is not unreadable — conflating them is the defect; got:\n" + stderr());
        }
    }

    /// Returns one fixed body for every request, so the SUCCESS arm of the node-enumeration control has a
    /// real parse to do rather than an empty list asserted against itself.
    /// Counts requests so an absence claim has a number behind it: "no request was issued" is only evidence
    /// if the same stub is shown counting one when a request IS issued.
    private record CountingHttpOperations(AtomicInteger calls) implements HttpOperations {
        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            calls.incrementAndGet();

            return Causes.cause("enumeration stub").promise();
        }
    }

    /// #998 — node enumeration as a GATE.
    ///
    /// Observed live on 2026-09-11: the registry endpoint carried no port, enumeration got a
    /// `ConnectException`, the failure was flattened into an empty node list by `.or(List.of())`, and
    /// DRAIN_NODES and SHUTDOWN_NODES were skipped while the VMs were deleted anyway. The summary read
    /// `Nodes processed: 0 / Drains succeeded: 0/0` — honest, and describing a teardown that drained nothing.
    @Nested
    class EnumerationGate {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        private PrintStream originalOut;

        private PrintStream originalErr;

        private HttpOperations originalHttpOps;

        private String originalEndpoint;

        @BeforeEach
        void captureStreamsAndStubHttp() {
            originalOut = System.out;
            originalErr = System.err;
            originalHttpOps = ClusterHttpClient.HTTP_OPS_REF.get();
            originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            ClusterHttpClient.setEndpointOverride("https://10.255.255.1:8080");
            ClusterHttpClient.HTTP_OPS_REF.set(new FailingHttpOperations("connection timed out"));
        }

        @AfterEach
        void restoreStreamsAndHttp() {
            System.setOut(originalOut);
            System.setErr(originalErr);
            ClusterHttpClient.HTTP_OPS_REF.set(originalHttpOps);
            ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        }

        private String stdout() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        /// #998 expectation 2, and the load-bearing half is the COUNT: a refusal that still ran cloud cleanup
        /// would print the same refusal text. Nothing may be deleted, because an undrained destroy is not
        /// recoverable and a refused one is — the cluster, its VMs and its registry entry all survive.
        @Test
        void performDestruction_deletesNothingAndExitsNonZero_whenEnumerationFails() {
            var cleanups = new AtomicInteger(0);
            var removals = new AtomicInteger(0);
            var originalRemover = ClusterDestroyCommand.registryRemover;

            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(3)));
            ClusterDestroyCommand.resourceCleaner = state -> countedCleanup(cleanups);
            ClusterDestroyCommand.registryRemover = (registry, name) -> countedRemoval(removals, registry);
            try {
                var exitCode = new ClusterDestroyCommand().performDestruction(registryWithNoEntries(),
                                                                             CLUSTER_NAME,
                                                                             TARGET_ENDPOINT);

                exitCode.onFailure(cause -> fail("the refusal is a summary, not a thrown failure: " + cause.message()))
                        .onSuccess(code -> assertEquals(ExitCode.ERROR, (int) code,
                                                        "a destroy that could not drain must not exit 0"));
                assertEquals(0, cleanups.get(),
                             "NOTHING may be deleted: cloud cleanup must not run when the nodes were never drained");
                assertEquals(0, removals.get(),
                             "and the registry entry must survive, because it is the handle for the retry");
                assertTrue(stderr().contains("REFUSING to destroy '" + CLUSTER_NAME + "'"),
                           () -> "the refusal must be explicit; got:\n" + stderr());
                assertTrue(stderr().contains("--force-undrained"),
                           () -> "and must name the flag that overrides it; got:\n" + stderr());
            } finally {
                ClusterDestroyCommand.registryRemover = originalRemover;
            }
        }

        /// The positive control for the test above, and the test of the flag itself. Same fixture, same failing
        /// enumeration, `--force-undrained` set: cleanup now RUNS and the command exits 0. Without this, "zero
        /// cleanups" proves only that the fixture never reached cleanup for some other reason.
        @Test
        void performDestruction_proceedsUndrained_whenForceUndrainedIsGiven() {
            var cleanups = new AtomicInteger(0);
            var originalRemover = ClusterDestroyCommand.registryRemover;

            ClusterDestroyCommand.stateLoader = name -> Result.success(some(stateWithVms(3)));
            ClusterDestroyCommand.resourceCleaner = state -> countedCleanup(cleanups);
            ClusterDestroyCommand.registryRemover = (registry, name) -> Result.success(registry);
            var command = new ClusterDestroyCommand();

            command.setForceUndrained(true);
            try {
                var exitCode = command.performDestruction(registryWithNoEntries(), CLUSTER_NAME, TARGET_ENDPOINT);

                exitCode.onFailure(cause -> fail("the forced path must produce a summary: " + cause.message()))
                        .onSuccess(code -> assertEquals(ExitCode.SUCCESS, (int) code,
                                                        "the operator asked for the undrained teardown, so it succeeds"));
                assertEquals(1, cleanups.get(), "the forced path DOES reap cloud resources");
                assertTrue(stdout().contains("--force-undrained: continuing with an EMPTY node list"),
                           () -> "and says so, so the transcript records that no drain happened; got:\n" + stdout());
            } finally {
                ClusterDestroyCommand.registryRemover = originalRemover;
            }
        }

        /// #998 — a `--cluster X` whose registry entry is absent or blank used to install NO endpoint override,
        /// so `resolveEndpoint()` fell through to `registry.current()` and destroy enumerated, DRAINED and SHUT
        /// DOWN the ACTIVE cluster's nodes while reaping X's cloud resources. The request must not be issued at
        /// all.
        @Test
        void fetchNodeIds_issuesNoRequest_whenTheTargetHasNoRecordedEndpoint() {
            var calls = new AtomicInteger(0);
            ClusterHttpClient.HTTP_OPS_REF.set(new CountingHttpOperations(calls));

            var nodeIds = new ClusterDestroyCommand().fetchNodeIds(noRecordedEndpoint());

            assertTrue(nodeIds.isFailure(), () -> "an unknown endpoint is a failed enumeration: " + nodeIds);
            assertEquals(0, calls.get(),
                         "no HTTP request may be issued: the only endpoint available would be a DIFFERENT "
                         + "cluster's, and draining that one is worse than not draining this one");
            assertTrue(stderr().contains("draining and shutting down a different cluster's nodes"),
                       () -> "the hazard must be named, not just the absence; got:\n" + stderr());
        }

        /// Positive control for the count above: the SAME stub, a target that does have an endpoint. Without
        /// it, "zero requests" is equally consistent with a stub that is never wired in.
        @Test
        void fetchNodeIds_issuesExactlyOneRequest_whenTheTargetHasAnEndpoint() {
            var calls = new AtomicInteger(0);
            ClusterHttpClient.HTTP_OPS_REF.set(new CountingHttpOperations(calls));

            new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT);

            assertEquals(1, calls.get(), "one enumeration request, as announced");
        }

        /// #998 — the diagnostic that turns a bare `ConnectException` into something actionable. Every registry
        /// entry written before this fix is port-less, so this is the line an operator reads on a stale entry.
        @Test
        void fetchNodeIds_namesAPortlessEndpoint_asAnObservation() {
            new ClusterDestroyCommand().fetchNodeIds(Result.success("https://138.199.236.244"));

            assertTrue(stderr().contains("carries NO explicit port"),
                       () -> "the observed shape of the endpoint must be reported; got:\n" + stderr());
            assertTrue(stderr().contains("8080"),
                       () -> "with the port an Aether management API actually listens on; got:\n" + stderr());
        }

        /// Negative control: an endpoint that DOES carry a port must not attract the note. Otherwise the note
        /// is an always-on line and says nothing about this endpoint.
        @Test
        void fetchNodeIds_saysNothingAboutPorts_whenTheEndpointCarriesOne() {
            new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT);

            assertTrue(stderr().contains("could not list cluster nodes"),
                       () -> "precondition: the enumeration still failed; got:\n" + stderr());
            assertFalse(stderr().contains("carries NO explicit port"),
                        () -> "a fully-specified endpoint must not be blamed for the port; got:\n" + stderr());
        }

        private static Result<String> noRecordedEndpoint() {
            return ClusterDestroyCommand.DestroyError.General.NO_TARGET_ENDPOINT.result();
        }

        private static Result<Unit> countedCleanup(AtomicInteger cleanups) {
            cleanups.incrementAndGet();

            return Result.unitResult();
        }

        private static Result<ClusterRegistry> countedRemoval(AtomicInteger removals, ClusterRegistry registry) {
            removals.incrementAndGet();

            return Result.success(registry);
        }

        private static ClusterRegistry registryWithNoEntries() {
            return ClusterRegistry.clusterRegistry(Path.of("unused-registry.toml"), none(), List.of());
        }
    }

    /// #1023 — `cluster destroy` could not drain AT ALL on a TLS-auto_generate cloud cluster. Observed
    /// live on a 5-node Hetzner cluster on 2026-09-11; the final summary read
    /// `Drains succeeded: 0/0, Shutdowns succeeded: 0/0`, which is a SKIP wearing the shape of a pass.
    ///
    /// Two independent failures, pinned separately here because they have different fixes and different
    /// risk: the registry's endpoint names ONE node and cannot survive it, and the CLI holds no trust
    /// anchor for the CA the cluster generated for itself.
    @Nested
    class SingleEndpointSpofAndClusterTrust {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        private PrintStream originalOut;

        private PrintStream originalErr;

        private HttpOperations originalHttpOps;

        private String originalEndpoint;

        @BeforeEach
        void captureStreamsAndStubHttp() {
            originalOut = System.out;
            originalErr = System.err;
            originalHttpOps = ClusterHttpClient.HTTP_OPS_REF.get();
            originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            ClusterHttpClient.setEndpointOverride("https://10.255.255.1:8080");
        }

        @AfterEach
        void restoreStreamsAndHttp() {
            System.setOut(originalOut);
            System.setErr(originalErr);
            ClusterHttpClient.HTTP_OPS_REF.set(originalHttpOps);
            ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        }

        private String stdout() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        /// Failure 1, the defect itself: the node the registry names is DEAD and three healthy nodes are
        /// serving the same route. Asserted on the HOSTS the requests actually carried, in order — a call
        /// count cannot distinguish "tried the sibling" from "retried the primary twice".
        @Test
        void fetchNodeIds_fallsThroughToALiveSibling_whenTheRecordedEndpointIsDead() {
            var attempted = new ArrayList<String>();

            ClusterHttpClient.HTTP_OPS_REF.set(new HostSelectiveHttpOperations("10.0.0.3",
                                                                               """
                                                                               [{"nodeId":"core-2"}]
                                                                               """,
                                                                               attempted));

            var nodeIds = new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT,
                                                                   List.of("https://10.0.0.2:8080",
                                                                           "https://10.0.0.3:8080"));

            assertEquals(Result.success(List.of("core-2")), nodeIds,
                         "a live sibling answers the enumeration the dead recorded endpoint could not");
            assertEquals(List.of("10.255.255.1", "10.0.0.2", "10.0.0.3"), attempted,
                         "the recorded endpoint must be tried FIRST and each sibling in turn — the operator's "
                         + "recorded target is not silently replaced");
        }

        /// The half that makes the fallthrough worth having. Enumerating from a live node and then draining
        /// against the dead one would be a fix in name only, so the endpoint that answered must become the
        /// target for the REST of the destroy.
        @Test
        void fetchNodeIds_retargetsSubsequentRequests_ontoTheSiblingThatAnswered() {
            var attempted = new ArrayList<String>();

            ClusterHttpClient.HTTP_OPS_REF.set(new HostSelectiveHttpOperations("10.0.0.3", "[]", attempted));

            new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT, List.of("https://10.0.0.3:8080"));

            assertEquals("https://10.0.0.3:8080", ClusterHttpClient.ENDPOINT_OVERRIDE.get(),
                         "DRAIN and SHUTDOWN resolve this override, so it must name the node that proved "
                         + "reachable rather than the one the registry happens to record");
            assertTrue(stdout().contains("will use it"),
                       () -> "and the transcript must record the retarget; got:\n" + stdout());
        }

        /// Negative control, and it guards #998: when every candidate fails, the endpoint override goes back
        /// to what the operator recorded, the PRIMARY failure is the one reported, and the refusal stands.
        /// Leaving the override on the last address tried would make the refusal describe a target nobody
        /// chose.
        @Test
        void fetchNodeIds_restoresTheRecordedEndpointAndStillFails_whenEverySiblingAlsoFails() {
            var attempted = new ArrayList<String>();

            ClusterHttpClient.HTTP_OPS_REF.set(new HostSelectiveHttpOperations("no-host-answers", "[]", attempted));

            var nodeIds = new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT, List.of("https://10.0.0.2:8080"));

            assertTrue(nodeIds.isFailure(),
                       () -> "an unreachable cluster is still an unreachable cluster: " + nodeIds);
            assertEquals(List.of("10.255.255.1", "10.0.0.2"), attempted, "both candidates were tried");
            assertEquals("https://10.255.255.1:8080", ClusterHttpClient.ENDPOINT_OVERRIDE.get(),
                         "the target returns to the recorded endpoint once the recovery path is exhausted");
            assertTrue(stderr().contains("could not list cluster nodes"),
                       () -> "#998's refusal must survive the fallback; got:\n" + stderr());
        }

        /// Positive control for the two above: with NO siblings recorded the behaviour is exactly what it
        /// was before #1023 — one request, no fallback announcement. Without this, "tried 3 hosts" is
        /// equally consistent with a loop that always runs.
        @Test
        void fetchNodeIds_issuesOneRequestAndAnnouncesNoFallback_whenNoSiblingsAreRecorded() {
            var attempted = new ArrayList<String>();

            ClusterHttpClient.HTTP_OPS_REF.set(new HostSelectiveHttpOperations("no-host-answers", "[]", attempted));

            new ClusterDestroyCommand().fetchNodeIds(TARGET_ENDPOINT, List.of());

            assertEquals(List.of("10.255.255.1"), attempted, "exactly one request, to the recorded endpoint");
            assertFalse(stdout().contains("Trying recorded node address"),
                        () -> "there is nothing to fall back to, so nothing may be announced; got:\n" + stdout());
            assertTrue(stdout().contains("one request"),
                       () -> "and the announcement still describes a single request; got:\n" + stdout());
        }

        /// The construction itself, against #1023's OBSERVED registry entry. Scheme and port are borrowed
        /// from the recorded endpoint rather than defaulted — #998's reason for refusing to invent a port
        /// applies unchanged to the siblings built from it — and the primary's own address is not retried.
        @Test
        void siblingEndpoints_borrowTheRecordedSchemeAndPort_andExcludeThePrimary() {
            var state = stateWithAddresses("138.199.236.244", "10.0.0.2", "10.0.0.3");

            var siblings = ClusterDestroyCommand.siblingEndpoints(Result.success("https://138.199.236.244:8080"),
                                                                  some(state));

            assertEquals(List.of("https://10.0.0.2:8080", "https://10.0.0.3:8080"), siblings,
                         "https and 8080 come from the recorded entry, and the node it already names is not "
                         + "tried twice");
        }

        /// #998's property, preserved: a target with NO recorded endpoint must produce no candidates at all.
        /// The siblings are a recovery path for a known target, never a way to guess one — draining a
        /// different cluster's nodes is worse than not draining this one's.
        @Test
        void siblingEndpoints_areEmpty_whenTheTargetHasNoRecordedEndpoint() {
            var noEndpoint = ClusterDestroyCommand.DestroyError.General.NO_TARGET_ENDPOINT.<String> result();

            var siblings = ClusterDestroyCommand.siblingEndpoints(noEndpoint, some(stateWithAddresses("10.0.0.2")));

            assertEquals(List.of(), siblings,
                         "no recorded endpoint means no scheme and no port, so there is nothing to build a "
                         + "candidate from — and nothing may be invented");
        }

        @Test
        void siblingEndpoints_areEmpty_whenTheLedgerRecordsNoAddresses() {
            var siblings = ClusterDestroyCommand.siblingEndpoints(TARGET_ENDPOINT, some(stateWithVms(3)));

            assertEquals(List.of(), siblings, "a ledger with no collected addresses offers no fallback");
        }

        /// Failure 2: the CLI must hold a trust anchor for the CA the cluster generated for itself. The
        /// observable is that the HTTP client is REPLACED — `enableClusterTrust` installs a new client
        /// carrying an `SSLContext` whose trust store holds the derived CA and nothing else.
        @Test
        void prepareClusterTrust_installsTheDerivedClusterCa_whenHttpsAndASecretIsRecorded() {
            var before = ClusterHttpClient.HTTP_OPS_REF.get();

            ClusterDestroyCommand.prepareClusterTrust(TARGET_ENDPOINT, some(stateWithSecret("cluster-secret-for-test")));

            assertNotSame(before, ClusterHttpClient.HTTP_OPS_REF.get(),
                          "an SSLContext trusting the cluster's own CA must actually be installed — without it "
                          + "every request fails PKIX path building and nothing can be drained");
            assertTrue(stdout().contains("Trusting this cluster's own CA"),
                       () -> "and the anchor being used must be stated; got:\n" + stdout());
        }

        /// Negative control: a plain-http cluster needs no anchor, so the client must be left alone. Without
        /// this, "the client changed" would also be satisfied by an unconditional replacement.
        @Test
        void prepareClusterTrust_leavesTheClientUntouched_whenTheEndpointIsPlainHttp() {
            var before = ClusterHttpClient.HTTP_OPS_REF.get();

            ClusterDestroyCommand.prepareClusterTrust(Result.success("http://10.0.0.2:8080"),
                                                      some(stateWithSecret("cluster-secret-for-test")));

            assertSame(before, ClusterHttpClient.HTTP_OPS_REF.get(),
                       "an http endpoint has no certificate to verify, so no trust decision is taken");
            assertFalse(stdout().contains("Trusting this cluster's own CA"),
                        () -> "and nothing is announced; got:\n" + stdout());
        }

        /// The honest half. With no recorded secret there is no anchor to derive, and the tempting
        /// workaround — trusting whatever certificate is presented — is exactly what #209 removed. The
        /// client must be left VERIFYING, and the operator must be told which of the two states they are in
        /// before the request fails with a bare PKIX error.
        @Test
        void prepareClusterTrust_saysSoAndDisablesNothing_whenHttpsButNoSecretIsRecorded() {
            var before = ClusterHttpClient.HTTP_OPS_REF.get();

            ClusterDestroyCommand.prepareClusterTrust(TARGET_ENDPOINT, some(stateWithVms(1)));

            assertSame(before, ClusterHttpClient.HTTP_OPS_REF.get(),
                       "certificate verification must NOT be disabled as a workaround: a destroy that "
                       + "trusts any certificate is worse than one that refuses");
            assertTrue(stderr().contains("no cluster_secret is recorded"),
                       () -> "the reason must be named; got:\n" + stderr());
            assertTrue(stderr().contains("NOT disabled"),
                       () -> "and the refusal to work around it must be explicit; got:\n" + stderr());
        }

        @Test
        void prepareClusterTrust_leavesTheClientUntouched_whenThereIsNoLedgerAtAll() {
            var before = ClusterHttpClient.HTTP_OPS_REF.get();

            ClusterDestroyCommand.prepareClusterTrust(TARGET_ENDPOINT, none());

            assertSame(before, ClusterHttpClient.HTTP_OPS_REF.get(),
                       "no ledger means no secret to derive an anchor from");
            assertTrue(stderr().contains("no cluster_secret is recorded"),
                       () -> "and it is reported rather than passed over; got:\n" + stderr());
        }

        /// #1023's summary line. `0/0` is a ratio over an empty set — a SKIP — and it reads like a pass.
        /// The count is unchanged and was always honest; what was missing is which of the two it describes.
        @Test
        void printSummary_marksTheDrainAndShutdownCountsAsSkipped_whenNoNodesWereEnumerated() {
            ClusterDestroyCommand.finalizeDestruction(registryWithNoEntries(),
                                                      CLUSTER_NAME,
                                                      true,
                                                      List.of(),
                                                      List.of(),
                                                      List.of());

            assertTrue(stdout().contains("Drains succeeded: 0/0  (SKIPPED"),
                       () -> "a zero-denominator ratio must say it is a skip; got:\n" + stdout());
            assertTrue(stdout().contains("Shutdowns succeeded: 0/0  (SKIPPED"),
                       () -> "and both counts carry it; got:\n" + stdout());
        }

        /// Negative control: with nodes actually processed the note must NOT appear, or it is an always-on
        /// line that says nothing about this destroy.
        @Test
        void printSummary_omitsTheSkippedNote_whenNodesWereProcessed() {
            ClusterDestroyCommand.finalizeDestruction(registryWithNoEntries(),
                                                      CLUSTER_NAME,
                                                      true,
                                                      List.of("core-0"),
                                                      List.of(new ClusterDestroyCommand.NodeResult("core-0", true)),
                                                      List.of(new ClusterDestroyCommand.NodeResult("core-0", true)));

            assertTrue(stdout().contains("Drains succeeded: 1/1"),
                       () -> "precondition: a real drain was counted; got:\n" + stdout());
            assertFalse(stdout().contains("SKIPPED"),
                        () -> "nothing was skipped, so the note must be absent; got:\n" + stdout());
        }

        private static ClusterRegistry registryWithNoEntries() {
            return ClusterRegistry.clusterRegistry(Path.of("unused-registry.toml"), none(), List.of());
        }
    }

    /// Answers for exactly one host and fails for every other, recording the host of each request in order.
    /// The ORDER is the assertion that matters: a call count cannot tell "fell through to the sibling" from
    /// "retried the primary", and those are the two behaviours #1023 turns on.
    private record HostSelectiveHttpOperations(String liveHost, String body, List<String> attempted)
            implements HttpOperations {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            attempted.add(request.uri().getHost());

            return liveHost.equals(request.uri().getHost())
                   ? Promise.success(new HttpResult<>(200, HttpHeaders.of(Map.of(), (a, b) -> true), (T) body))
                   : Causes.cause("connection refused").promise();
        }
    }

    private record FixedBodyHttpOperations(String body) implements HttpOperations {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            return Promise.success(new HttpResult<>(200, HttpHeaders.of(Map.of(), (a, b) -> true), (T) body));
        }
    }
}
