package org.pragmatica.cluster.node.rabia;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The order in which a [RabiaNode] brings its parts up and down. Every promise here resolves LATER, on another thread: a
/// fake that returns an already-resolved promise hides every ordering defect between a promise and its handlers.
class RabiaNodeLifecycleTest {
    private static final int TRIALS = 40;
    private static final Cause FAILED = Causes.cause("step failed (test cause)");

    private static Promise<Unit> later() {
        var promise = Promise.<Unit> promise();

        Thread.ofVirtual().start(() -> succeedAfterDelay(promise));

        return promise;
    }

    private static void succeedAfterDelay(Promise<Unit> promise) {
        LockSupport.parkNanos(2_000_000L);
        promise.succeed(Unit.unit());
    }

    /// The topology manager is started before consensus: consensus' start waits for the quorum the topology manager reports,
    /// so it must not begin ahead of it. Started by a handler on the network start, it ran concurrently with the consensus start.
    @Test
    void start_consensusStartsOnlyAfterTheTopologyManagerHas() {
        var consensusAhead = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            var topologyStarted = new AtomicBoolean();
            var topologyStartedAtConsensusStart = new AtomicBoolean();
            var result = RabiaNodeLifecycle.start(RabiaNodeLifecycleTest::later,
                                                  () -> {
                                                      LockSupport.parkNanos(1_000_000L);
                                                      topologyStarted.set(true);

                                                      return Promise.unitPromise();
                                                  },
                                                  () -> {
                                                      topologyStartedAtConsensusStart.set(topologyStarted.get());

                                                      return Promise.unitPromise();
                                                  }).await();

            assertThat(result.isSuccess()).isTrue();
            if (!topologyStartedAtConsensusStart.get()) {
                consensusAhead++;
            }
        }

        assertThat(consensusAhead).as("starts (of %d) in which consensus began before the topology manager had started", TRIALS).isZero();
    }

    @Test
    void start_networkFailure_startsNothingElse() {
        var started = new AtomicInteger();
        var result = RabiaNodeLifecycle.start(() -> FAILED.promise(),
                                              () -> {
                                                  started.incrementAndGet();

                                                  return Promise.unitPromise();
                                              },
                                              () -> {
                                                  started.incrementAndGet();

                                                  return Promise.unitPromise();
                                              }).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(started.get()).as("neither the topology manager nor consensus starts on a failed network").isZero();
    }

    /// The topology manager is stopped before the network: stopped by a handler on the consensus stop, the network stop ran
    /// beside it and could take the transport down under a topology manager still stopping.
    @Test
    void stop_networkStopsOnlyAfterTheTopologyManagerHas() {
        var networkAhead = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            var topologyStopped = new AtomicBoolean();
            var topologyStoppedAtNetworkStop = new AtomicBoolean();
            var result = RabiaNodeLifecycle.stop(RabiaNodeLifecycleTest::later,
                                                 () -> {
                                                     LockSupport.parkNanos(1_000_000L);
                                                     topologyStopped.set(true);

                                                     return Promise.unitPromise();
                                                 },
                                                 () -> {
                                                     topologyStoppedAtNetworkStop.set(topologyStopped.get());

                                                     return Promise.unitPromise();
                                                 }).await();

            assertThat(result.isSuccess()).isTrue();
            if (!topologyStoppedAtNetworkStop.get()) {
                networkAhead++;
            }
        }

        assertThat(networkAhead).as("stops (of %d) in which the network stopped before the topology manager had", TRIALS).isZero();
    }

    /// A failed consensus stop still stops the topology manager (as before), still skips the network stop, and is what the stop reports.
    @Test
    void stop_consensusFailure_stillStopsTheTopologyManager_skipsTheNetwork_andReportsTheFailure() {
        var topologyStopped = new AtomicBoolean();
        var networkStopped = new AtomicBoolean();
        var result = RabiaNodeLifecycle.stop(() -> FAILED.promise(),
                                             () -> {
                                                 topologyStopped.set(true);

                                                 return Promise.unitPromise();
                                             },
                                             () -> {
                                                 networkStopped.set(true);

                                                 return Promise.unitPromise();
                                             }).await();

        assertThat(topologyStopped.get()).isTrue();
        assertThat(networkStopped.get()).isFalse();
        assertThat(result.fold(Cause::message, _ -> "success")).isEqualTo(FAILED.message());
    }
}
