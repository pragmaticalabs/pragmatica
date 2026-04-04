package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.stream.replication.GovernorFailoverHandler;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Coordinates streaming components (GovernorFailoverHandler, RetentionEnforcer)
/// as a single DelegatedComponent for the STREAMING task group.
///
/// RetentionEnforcer has a scheduled lifecycle (start/close).
/// GovernorFailoverHandler is stateless and invoked on demand -- no lifecycle management needed,
/// but it is held here for access by the streaming subsystem when active.
public final class StreamingCoordinator implements DelegatedComponent {
    private static final Logger log = LoggerFactory.getLogger(StreamingCoordinator.class);

    private final GovernorFailoverHandler failoverHandler;
    private final RetentionEnforcer retentionEnforcer;

    private final AtomicBoolean active = new AtomicBoolean(false);

    private StreamingCoordinator(GovernorFailoverHandler failoverHandler, RetentionEnforcer retentionEnforcer) {
        this.failoverHandler = failoverHandler;
        this.retentionEnforcer = retentionEnforcer;
    }

    public static StreamingCoordinator streamingCoordinator(GovernorFailoverHandler failoverHandler,
                                                            RetentionEnforcer retentionEnforcer) {
        return new StreamingCoordinator(failoverHandler, retentionEnforcer);
    }

    @Override public Promise<Unit> activate() {
        if (active.compareAndSet(false, true)) {
            retentionEnforcer.start();
            log.info("STREAMING delegation group activated");
        }
        return Promise.success(unit());
    }

    @Override public Promise<Unit> deactivate() {
        if (active.compareAndSet(true, false)) {
            retentionEnforcer.close();
            log.info("STREAMING delegation group deactivated");
        }
        return Promise.success(unit());
    }

    @Override public TaskGroup taskGroup() {
        return TaskGroup.STREAMING;
    }

    @Override public boolean isActive() {
        return active.get();
    }

    public GovernorFailoverHandler failoverHandler() {
        return failoverHandler;
    }
}
