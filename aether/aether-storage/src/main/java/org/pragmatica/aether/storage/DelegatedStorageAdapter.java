package org.pragmatica.aether.storage;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Adapter wrapping storage components (DemotionManager, StorageGarbageCollector)
/// as a single DelegatedComponent for the STORAGE task group.
///
/// Both wrapped components follow the dormant/active lifecycle pattern:
/// activate() enables processing, deactivate() disables it.
/// This adapter coordinates their lifecycle as a single unit.
public final class DelegatedStorageAdapter implements DelegatedComponent {
    private static final Logger log = LoggerFactory.getLogger(DelegatedStorageAdapter.class);

    private final DemotionManager demotionManager;
    private final StorageGarbageCollector garbageCollector;

    private final AtomicBoolean active = new AtomicBoolean(false);

    private DelegatedStorageAdapter(DemotionManager demotionManager, StorageGarbageCollector garbageCollector) {
        this.demotionManager = demotionManager;
        this.garbageCollector = garbageCollector;
    }

    public static DelegatedStorageAdapter delegatedStorageAdapter(DemotionManager demotionManager,
                                                                  StorageGarbageCollector garbageCollector) {
        return new DelegatedStorageAdapter(demotionManager, garbageCollector);
    }

    @Override public Promise<Unit> activate() {
        if (active.compareAndSet(false, true)) {
            demotionManager.activate();
            garbageCollector.activate();
            log.info("STORAGE delegation group activated");
        }
        return Promise.success(unit());
    }

    @Override public Promise<Unit> deactivate() {
        if (active.compareAndSet(true, false)) {
            garbageCollector.deactivate();
            demotionManager.deactivate();
            log.info("STORAGE delegation group deactivated");
        }
        return Promise.success(unit());
    }

    @Override public TaskGroup taskGroup() {
        return TaskGroup.STORAGE;
    }

    @Override public boolean isActive() {
        return active.get();
    }
}
