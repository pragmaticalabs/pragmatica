// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.worker.metadata;

import java.util.function.Consumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;


/// Node-wide event budget with constant memory, including when many workers fail concurrently.
public final class WorkerMetadataFailureReporter {
    public record Rejection(NodeId worker, String reason, long suppressed) {}

    private final TimeSource clock;
    private final TimeSpan interval;
    private final Consumer<Rejection> publish;
    private long lastReport;
    private long suppressed;
    private boolean reported;

    private WorkerMetadataFailureReporter(TimeSource clock, TimeSpan interval, Consumer<Rejection> publish) {
        this.clock = clock;
        this.interval = interval;
        this.publish = publish;
    }

    public static WorkerMetadataFailureReporter workerMetadataFailureReporter(TimeSource clock,
                                                                              TimeSpan interval,
                                                                              Consumer<Rejection> publish) {
        return new WorkerMetadataFailureReporter(clock, interval, publish);
    }

    public synchronized Unit report(NodeId worker, String reason) {
        var now = clock.nanoTime();

        if (reported && now - lastReport < interval.nanos()) {
            suppressed++;

            return Unit.unit();
        }

        var rejection = new Rejection(worker, reason, suppressed);

        lastReport = now;
        reported = true;
        suppressed = 0;
        publish.accept(rejection);

        return Unit.unit();
    }
}
