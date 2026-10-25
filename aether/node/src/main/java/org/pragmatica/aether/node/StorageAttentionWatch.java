// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.StorageCondition;
import org.pragmatica.storage.StorageCondition.Kind;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Turns every condition a storage instance holds that needs an operator ([StorageCondition]) into an operator event
/// when it appears, and into its recovery when it goes away (A0b). One event per transition: a condition that stays is
/// not repeated by the next look.
///
/// The conditions come from [StorageSetup#conditions]: what boot left standing (a disk tier that could not open at a
/// default path, a memory tier cut to the heap budget) and what the instance's managers hold now (demotion refused, the
/// metadata snapshot failing, an older snapshot restored). The boot ones have no end inside the process, so no recovery
/// follows them; the others recover when their manager stops reporting them.
///
/// The subject of an event is `<instance>/<condition>`, so two conditions of one instance do not close each other.
/// [#observe] runs on the storage maintenance tick, and once when the watch is built, so what boot left standing is
/// reported before the first tick.
public final class StorageAttentionWatch {
    private static final Logger log = LoggerFactory.getLogger(StorageAttentionWatch.class);

    private final Map<String, StorageSetup> setups;
    private final OperatorWarningSink sink;
    private final Map<String, Set<Kind>> raised = new ConcurrentHashMap<>();

    private StorageAttentionWatch(Map<String, StorageSetup> setups, OperatorWarningSink sink) {
        this.setups = setups;
        this.sink = sink;
    }

    /// Watches every instance of `setups` and reports what is standing now.
    public static StorageAttentionWatch storageAttentionWatch(Map<String, StorageSetup> setups,
                                                              OperatorWarningSink sink) {
        var watch = new StorageAttentionWatch(Map.copyOf(setups), sink);

        watch.observe();

        return watch;
    }

    /// Nothing watched: for a driver built without storage setups (tests of other behaviour).
    public static StorageAttentionWatch noWatch() {
        return new StorageAttentionWatch(Map.of(), OperatorWarningSink.logOnly());
    }

    /// Looks at each watched instance once, and raises or recovers what changed since the last look.
    public synchronized Unit observe() {
        setups.forEach((instance, setup) -> observe(instance, setup.conditions()));

        return unit();
    }

    private Unit observe(String instance, List<StorageCondition> now) {
        var before = raised.getOrDefault(instance, Set.of());
        var current = now.stream()
                         .collect(Collectors.toMap(StorageCondition::kind, StorageCondition::detail, (a, _) -> a, HashMap::new));

        now.stream()
           .filter(condition -> !before.contains(condition.kind()))
           .forEach(condition -> raise(instance, condition));
        before.stream()
              .filter(kind -> !current.containsKey(kind))
              .forEach(kind -> recover(instance, kind));
        raised.put(instance, Set.copyOf(current.keySet()));

        return unit();
    }

    private Unit raise(String instance, StorageCondition condition) {
        return OperatorWarnings.raise(log,
                                      sink,
                                      OperatorWarningCode.STORAGE_DEGRADED,
                                      subject(instance, condition.kind()),
                                      "{}",
                                      condition.detail());
    }

    private Unit recover(String instance, Kind kind) {
        return OperatorWarnings.raise(log,
                                      sink,
                                      OperatorWarningCode.STORAGE_RECOVERED,
                                      subject(instance, kind),
                                      "Storage '{}' recovered: {}",
                                      instance,
                                      recovery(kind));
    }

    static String subject(String instance, Kind kind) {
        return instance + "/" + kind.name().toLowerCase().replace('_', '-');
    }

    private static String recovery(Kind kind) {
        return switch (kind) {
            case DEMOTION_REFUSED -> "the tier is back under its high watermark and demotion is not refused";
            case SNAPSHOT_WRITE_FAILING -> "a metadata snapshot was written";
            case SNAPSHOT_FELL_BACK -> "a complete current metadata snapshot was written after the restore from an older one";
            case DISK_TIER_UNAVAILABLE -> "the disk tier is available";
            case MEMORY_TIER_CLAMPED -> "the memory tier is at its configured size";
        };
    }
}
