// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.storage;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.storage.DelegatedStorageAdapter.delegatedStorageAdapter;
import static org.pragmatica.lang.Unit.unit;

/// #250 review — `activate()`/`deactivate()` on the delegated managers are real operations that can
/// fail since #250 (they were no-ops before). Pins that a failing `Result` from either manager is
/// observed (logged at WARN with the cause) rather than silently discarded.
///
/// Log-capture strategy follows `ClusterTopologyManagerCasLossLoggingTest`: attach a log4j2
/// programmatic appender to the adapter's own logger and assert on captured WARN messages.
class DelegatedStorageAdapterTest {
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(DelegatedStorageAdapter.class, Level.WARN, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void activate_demotionManagerFails_logsWarningWithCause() {
        var adapter = delegatedStorageAdapter(failingDemotionManager("demotion boom"), succeedingGarbageCollector());

        adapter.activate().await();

        assertThat(capture.messages())
            .as("a failing demotion-manager activate() must be logged, not discarded")
            .anyMatch(msg -> msg.contains("demotion boom"));
    }

    /// A0b: the leader-pinned group that cannot start raises ONE operator warning for the group (not one per election), and
    /// the activation that succeeds closes it.
    @Test
    void activate_failingGroup_raisesOneOperatorWarningThenRecoversWhenItStarts() throws InterruptedException {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<org.pragmatica.utility.warning.OperatorWarning>();
        var degrade = org.pragmatica.storage.DegradeReporter.degradeReporter(DelegatedStorageAdapter.REPORT_SUBJECT);
        var broken = new java.util.concurrent.atomic.AtomicBoolean(true);
        var demotion = succeedingDemotionManager();
        var flaky = new DemotionManager() {
            @Override
            public int demote() {
                return 0;
            }

            @Override
            public DemotionStats stats() {
                return new DemotionStats(0, 0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return broken.get() ? Causes.cause("demotion boom").<Unit> result() : demotion.activate();
            }

            @Override
            public Result<Unit> deactivate() {
                return demotion.deactivate();
            }

            @Override
            public boolean isActive() {
                return demotion.isActive();
            }
        };
        var adapter = delegatedStorageAdapter(flaky, succeedingGarbageCollector(), degrade);
        degrade.bind(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(raised::add));

        adapter.activate().await();
        adapter.activate().await();
        awaitRaised(raised, 1);
        broken.set(false);
        adapter.activate().await();
        awaitRaised(raised, 2);

        assertThat(raised).extracting(org.pragmatica.utility.warning.OperatorWarning::code)
                          .containsExactly(org.pragmatica.utility.warning.OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STARTED,
                                           org.pragmatica.utility.warning.OperatorWarningCode.STORAGE_MAINTENANCE_STARTED);
        assertThat(raised.getFirst().subject()).isEqualTo(DelegatedStorageAdapter.REPORT_SUBJECT);
    }

    private static void awaitRaised(java.util.List<?> raised, int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raised).as("operator warnings handed to the sink within 5 s").hasSizeGreaterThanOrEqualTo(count);
    }

    @Test
    void activate_demotionManagerFails_adapterReportsInactive() {
        var adapter = delegatedStorageAdapter(failingDemotionManager("demotion boom"), succeedingGarbageCollector());

        adapter.activate().await();

        assertThat(adapter.isActive())
            .as("a failed manager activation must leave the adapter reporting inactive, not flip active=true regardless")
            .isFalse();
    }

    @Test
    void deactivate_garbageCollectorFails_logsWarningWithCause() {
        var adapter = delegatedStorageAdapter(succeedingDemotionManager(), failingGarbageCollector("gc boom"));

        adapter.activate().await();
        adapter.deactivate().await();

        assertThat(capture.messages())
            .as("a failing garbage-collector deactivate() must be logged, not discarded")
            .anyMatch(msg -> msg.contains("gc boom"));
    }

    /// #804 — a failed manager deactivation leaves the adapter ACTIVE: a maintenance pass may still be
    /// scheduled. Mutation that reddens it: flip the flag before (or regardless of) the manager results.
    @Test
    void deactivate_garbageCollectorFails_adapterStaysActive() {
        var adapter = delegatedStorageAdapter(succeedingDemotionManager(), failingGarbageCollector("gc boom"));

        adapter.activate().await();
        assertThat(adapter.isActive()).as("control: activation succeeded").isTrue();
        adapter.deactivate().await();

        assertThat(adapter.isActive())
            .as("#804: a failed garbage-collector deactivation must keep the adapter reporting active")
            .isTrue();
    }

    /// Control for the one above: when both managers deactivate, the adapter reports inactive.
    @Test
    void deactivate_bothSucceed_adapterReportsInactive() {
        var demotion = new Recording();
        var collector = new Recording();
        var adapter = delegatedStorageAdapter(demotion.demotionManager(Result.success(unit())),
                                              collector.garbageCollector(Result.success(unit())));

        adapter.activate().await();
        adapter.deactivate().await();

        assertThat(adapter.isActive()).isFalse();
        assertThat(demotion.deactivations).isEqualTo(1);
        assertThat(collector.deactivations).isEqualTo(1);
    }

    /// #804 addendum — a PARTIAL activation (demotion started, collector failed) must stop the manager that
    /// started. Before the fix it kept running while the adapter reported inactive, unreachable by any later
    /// `deactivate()`. Mutation that reddens it: drop the `stopPartiallyActivated` call.
    @Test
    void activate_secondManagerFails_stopsTheFirstAndReportsInactive() {
        var demotion = new Recording();
        var collector = new Recording();
        var adapter = delegatedStorageAdapter(demotion.demotionManager(Result.success(unit())),
                                              collector.garbageCollector(Causes.cause("gc start boom").result()));

        adapter.activate().await();

        assertThat(adapter.isActive()).isFalse();
        assertThat(demotion.activations).as("control: the demotion manager did start").isEqualTo(1);
        assertThat(demotion.deactivations)
            .as("#804: the manager that started is stopped when its partner failed to start")
            .isEqualTo(1);
        assertThat(collector.deactivations).as("the manager that never started is not stopped").isZero();
    }

    /// Counts activate/deactivate calls on a fake manager.
    /// #800: a failed deactivation raises one operator warning (not just a log line); a retry that stops the group closes it.
    @Test
    void deactivate_failure_raisesOneWarningThenRetrySuccessClosesIt() throws InterruptedException {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<org.pragmatica.utility.warning.OperatorWarning>();
        var gc = new SwitchableCollector();
        var adapter = adapterOver(gc, raised);

        adapter.activate().await();
        gc.failDeactivate.set(true);
        adapter.deactivate().await();
        adapter.retryPending();
        awaitRaised(raised, 1);
        gc.failDeactivate.set(false);
        adapter.retryPending();
        awaitRaised(raised, 2);

        assertThat(raised).extracting(org.pragmatica.utility.warning.OperatorWarning::code)
                          .containsExactly(org.pragmatica.utility.warning.OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STOPPED,
                                           org.pragmatica.utility.warning.OperatorWarningCode.STORAGE_MAINTENANCE_STOPPED);
        assertThat(raised.getFirst().subject()).isEqualTo(DelegatedStorageAdapter.REPORT_SUBJECT);
        assertThat(adapter.isActive()).isFalse();
    }

    /// #800: this node leads again before the stop could be retried: running the group is correct, so the warning closes.
    @Test
    void deactivate_failureThenLeaderAgain_closesTheWarning() throws InterruptedException {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<org.pragmatica.utility.warning.OperatorWarning>();
        var gc = new SwitchableCollector();
        var adapter = adapterOver(gc, raised);

        adapter.activate().await();
        gc.failDeactivate.set(true);
        adapter.deactivate().await();
        awaitRaised(raised, 1);
        adapter.activate().await();
        awaitRaised(raised, 2);

        assertThat(raised).extracting(org.pragmatica.utility.warning.OperatorWarning::code)
                          .containsExactly(org.pragmatica.utility.warning.OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STOPPED,
                                           org.pragmatica.utility.warning.OperatorWarningCode.STORAGE_MAINTENANCE_STOPPED);
    }

    /// #800: a failed activation is retried from the tick, at most MAX_RETRIES times per leader-change call, and a retry that
    /// succeeds activates the group (the already-open warning is closed by it, as by any activation).
    @Test
    void retryPending_failedActivation_retriesBoundedThenGivesUp() {
        var gc = new SwitchableCollector();
        var adapter = adapterOver(gc, new java.util.concurrent.CopyOnWriteArrayList<>());

        gc.failActivate.set(true);
        adapter.activate().await();

        for (var i = 0; i < DelegatedStorageAdapter.MAX_RETRIES + 2; i++) {
            adapter.retryPending();
        }

        assertThat(gc.activateCalls.get()).as("the call itself plus the bounded retries")
                                          .isEqualTo(1 + DelegatedStorageAdapter.MAX_RETRIES);
        gc.failActivate.set(false);
        adapter.retryPending();

        assertThat(adapter.isActive()).as("the budget is spent: only a new leader-change call tries again").isFalse();
        adapter.activate().await();
        assertThat(adapter.isActive()).isTrue();
    }

    @Test
    void retryPending_failedActivationThatLaterWorks_activatesTheGroup() {
        var gc = new SwitchableCollector();
        var adapter = adapterOver(gc, new java.util.concurrent.CopyOnWriteArrayList<>());

        gc.failActivate.set(true);
        adapter.activate().await();
        gc.failActivate.set(false);
        adapter.retryPending();

        assertThat(adapter.isActive()).isTrue();
    }

    @Test
    void retryPending_inTheWantedState_touchesNothing() {
        var gc = new SwitchableCollector();
        var adapter = adapterOver(gc, new java.util.concurrent.CopyOnWriteArrayList<>());

        adapter.retryPending();
        adapter.activate().await();
        adapter.retryPending();
        adapter.deactivate().await();
        adapter.retryPending();

        assertThat(gc.activateCalls.get()).isEqualTo(1);
        assertThat(gc.deactivateCalls.get()).isEqualTo(1);
    }

    /// #800: a deactivation that keeps failing is retried on the same bounded budget.
    @Test
    void retryPending_failedDeactivation_retriesBoundedThenGivesUp() {
        var gc = new SwitchableCollector();
        var adapter = adapterOver(gc, new java.util.concurrent.CopyOnWriteArrayList<>());

        adapter.activate().await();
        gc.failDeactivate.set(true);
        adapter.deactivate().await();

        for (var i = 0; i < DelegatedStorageAdapter.MAX_RETRIES + 2; i++) {
            adapter.retryPending();
        }

        assertThat(gc.deactivateCalls.get()).isEqualTo(1 + DelegatedStorageAdapter.MAX_RETRIES);
    }

    private static DelegatedStorageAdapter adapterOver(StorageGarbageCollector gc,
                                                       java.util.List<org.pragmatica.utility.warning.OperatorWarning> raised) {
        var degrade = org.pragmatica.storage.DegradeReporter.degradeReporter(DelegatedStorageAdapter.REPORT_SUBJECT);

        degrade.bind(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(raised::add));

        return delegatedStorageAdapter(succeedingDemotionManager(), gc, degrade);
    }

    /// A collector whose activation and deactivation fail on demand, counting the calls made to each.
    private static final class SwitchableCollector implements StorageGarbageCollector {
        final java.util.concurrent.atomic.AtomicBoolean failActivate = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean failDeactivate = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger activateCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger deactivateCalls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public int collectGarbage() {
            return 0;
        }

        @Override
        public GCStats stats() {
            return new GCStats(0, 0);
        }

        @Override
        public Result<Unit> activate() {
            activateCalls.incrementAndGet();

            return failActivate.get() ? Causes.cause("gc activate boom").<Unit> result() : Result.success(unit());
        }

        @Override
        public Result<Unit> deactivate() {
            deactivateCalls.incrementAndGet();

            return failDeactivate.get() ? Causes.cause("gc deactivate boom").<Unit> result() : Result.success(unit());
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }

    private static final class Recording {
        private int activations;
        private int deactivations;

        DemotionManager demotionManager(Result<Unit> onActivate) {
            var self = this;
            return new DemotionManager() {
                @Override
                public int demote() {
                    return 0;
                }

                @Override
                public DemotionStats stats() {
                    return new DemotionStats(0, 0, 0);
                }

                @Override
                public Result<Unit> activate() {
                    self.activations++;
                    return onActivate;
                }

                @Override
                public Result<Unit> deactivate() {
                    self.deactivations++;
                    return Result.success(unit());
                }

                @Override
                public boolean isActive() {
                    return false;
                }
            };
        }

        StorageGarbageCollector garbageCollector(Result<Unit> onActivate) {
            var self = this;
            return new StorageGarbageCollector() {
                @Override
                public int collectGarbage() {
                    return 0;
                }

                @Override
                public GCStats stats() {
                    return new GCStats(0, 0);
                }

                @Override
                public Result<Unit> activate() {
                    self.activations++;
                    return onActivate;
                }

                @Override
                public Result<Unit> deactivate() {
                    self.deactivations++;
                    return Result.success(unit());
                }

                @Override
                public boolean isActive() {
                    return false;
                }
            };
        }
    }

    // --- Fakes ---

    private static DemotionManager succeedingDemotionManager() {
        return demotionManagerReturning(Result.success(unit()), Result.success(unit()));
    }

    private static DemotionManager failingDemotionManager(String failureMessage) {
        return demotionManagerReturning(Causes.cause(failureMessage).result(), Result.success(unit()));
    }

    private static DemotionManager demotionManagerReturning(Result<Unit> onActivate, Result<Unit> onDeactivate) {
        return new DemotionManager() {
            @Override
            public int demote() {
                return 0;
            }

            @Override
            public DemotionStats stats() {
                return new DemotionStats(0, 0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return onActivate;
            }

            @Override
            public Result<Unit> deactivate() {
                return onDeactivate;
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }

    private static StorageGarbageCollector succeedingGarbageCollector() {
        return garbageCollectorReturning(Result.success(unit()), Result.success(unit()));
    }

    private static StorageGarbageCollector failingGarbageCollector(String failureMessage) {
        return garbageCollectorReturning(Result.success(unit()), Causes.cause(failureMessage).result());
    }

    private static StorageGarbageCollector garbageCollectorReturning(Result<Unit> onActivate, Result<Unit> onDeactivate) {
        return new StorageGarbageCollector() {
            @Override
            public int collectGarbage() {
                return 0;
            }

            @Override
            public GCStats stats() {
                return new GCStats(0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return onActivate;
            }

            @Override
            public Result<Unit> deactivate() {
                return onDeactivate;
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }
}
