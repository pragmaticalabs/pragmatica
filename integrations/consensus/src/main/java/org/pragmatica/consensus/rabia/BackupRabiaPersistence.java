package org.pragmatica.consensus.rabia;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.LoggerFactory;


/// One in-flight immutable checkpoint plus one latest pending checkpoint. Never backs up ballots.
final class BackupRabiaPersistence<C extends Command> implements RabiaPersistence<C> {
    private final RabiaPersistence<C> durable;
    private final RabiaPersistence<C> backup;

    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    private final AtomicReference<Option<SavedState<C>>> pending = new AtomicReference<>(Option.none());
    private final AtomicReference<Option<Cause>> failure = new AtomicReference<>(Option.none());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    BackupRabiaPersistence(RabiaPersistence<C> durable, RabiaPersistence<C> backup) {
        this.durable = durable;
        this.backup = backup;
    }

    @Override
    public Result<Unit> append(RabiaProtocolMessage message) {
        return durable.append(message);
    }

    @Override
    public Result<List<RabiaProtocolMessage>> loadJournal() {
        return durable.loadJournal();
    }

    @Override
    public boolean checkpointRequired() {
        return durable.checkpointRequired();
    }

    @Override
    public Option<SavedState<C>> load() {
        return durable.load();
    }

    @Override
    public Result<Option<SavedState<C>>> loadVerified() {
        return durable.loadVerified();
    }

    @Override
    public Option<Cause> lastBackupFailure() {
        return failure.get();
    }

    @Override
    public Result<Unit> save(StateMachine<C> machine, Phase frontier, Collection<Batch<C>> batches) {
        return durable.save(machine, frontier, batches)
                      .onSuccess(_ -> enqueue());
    }

    @Override
    public Result<Unit> save(StateMachine<C> machine,
                             Phase frontier,
                             Collection<Batch<C>> batches,
                             VoterAuthority<C> authority) {
        return durable.save(machine, frontier, batches, authority)
                      .onSuccess(_ -> enqueue());
    }

    private void enqueue() {
        if (closed.get()) {
            return;
        }

        pending.set(durable.load());
        if (running.compareAndSet(false, true)) {
            scheduleDrain();
        }
    }

    private void scheduleDrain() {
        Result.lift(org.pragmatica.lang.utils.Causes::fromThrowable,
                    () -> {
                        worker.execute(this::drain);

                        return Unit.unit();
                    })
              .onFailure(cause -> {
                  running.set(false);
                  failure.set(Option.some(cause));
              });
    }

    private void drain() {
        while (!closed.get()) {
            var next = pending.getAndSet(Option.none());

            if (next.isEmpty()) {
                break;
            }

            next.onPresent(snapshot -> backup.saveSnapshot(snapshot)
                                             .onSuccess(_ -> failure.set(Option.none()))
                                             .onFailure(cause -> {
                                                            failure.set(Option.some(cause));
                                                            LoggerFactory.getLogger(BackupRabiaPersistence.class).error("Consensus checkpoint backup failed: {}",
                                                                                                                        cause);
                                                        }));
        }

        running.set(false);
        if (!closed.get() && pending.get().isPresent() && running.compareAndSet(false, true)) {
            scheduleDrain();
        }
    }

    @Override
    public Result<Unit> close() {
        closed.set(true);
        pending.set(Option.none());
        worker.shutdownNow();

        return durable.close();
    }
}
