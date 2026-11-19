/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Promise;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.carriesKey;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.sameDeliverer;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 exit re-read (R4, G3): owed keys that are all absent do not hold the exit back; the exit's re-read drops a key the store
/// no longer has, and revives an absent key the store has again, which the re-plan after it pages. Each run ends long before its
/// 3 s window: a key kept absent, or kept in the inventory, would hold it to the deadline with the key owed.
class DepartureExitRereadTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long WINDOW_MS = 3_000;

    /// `removed` is gone at its page's read (absent); `kept` is acked. The first exit attempt's re-read misses `removed`, so it leaves
    /// the inventory: one probe round, nothing owed.
    @Test
    @Timeout(20)
    void absentKeyRemovedForGood_exitReReadDropsIt_nothingOwed() {
        var ring = fiveNodeRing();
        var kept = soleKey(ring, "xr-kept");
        var removed = sameDeliverer(ring, kept, "xr-removed");
        var memory = memoryStorageEngine();
        var node = dhtNode(LOCAL, stampsScripted(memory, removed, false), ring, RF1);
        node.putLocal(kept, value("payload")).await();
        node.putLocal(removed, value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var started = System.nanoTime();
        var direct = direct(node, network, WINDOW_MS, 100, Set::of);

        assertThat(network.sends()).as("absent at its read, never sent").noneMatch(sent -> carriesKey(sent, removed));
        assertThat(direct.report().owed()).isZero();
        assertThat(network.probes()).as("one probe round").hasSize(1);
        assertThat(elapsedMs(started)).as("closed by the exit, not the deadline").isLessThan(WINDOW_MS / 2);
    }

    /// As above, but the store has `removed` again by the first exit re-read: it is no longer absent, the re-plan after the re-read
    /// pages it, its ack makes it safe, and only then does a probe round run.
    @Test
    @Timeout(20)
    void absentKeyWrittenAgain_exitReReadRevivesIt_pagedAndDelivered() {
        var ring = fiveNodeRing();
        var kept = soleKey(ring, "xw-kept");
        var removed = sameDeliverer(ring, kept, "xw-removed");
        var memory = memoryStorageEngine();
        var node = dhtNode(LOCAL, stampsScripted(memory, removed, true), ring, RF1);
        node.putLocal(kept, value("payload")).await();
        node.putLocal(removed, value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var started = System.nanoTime();
        var direct = direct(node, network, WINDOW_MS, 100, Set::of);

        assertThat(network.sends()).as("paged after the re-read").filteredOn(sent -> carriesKey(sent, removed)).hasSize(1);
        assertThat(direct.report().owed()).isZero();
        assertThat(network.probes()).as("one probe round, after the revived key was acked").hasSize(1);
        assertThat(elapsedMs(started)).as("closed by an exit, not the deadline").isLessThan(WINDOW_MS / 2);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /// `memory`, except for its stamps: the first enumeration removes `key` right after the read (absent at its page's read); with
    /// `writeAgain`, the second (the first exit re-read) writes it again before reading.
    private static StorageEngine stampsScripted(StorageEngine memory, byte[] key, boolean writeAgain) {
        var calls = new AtomicInteger();
        return (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
                                                      new Class<?>[]{StorageEngine.class},
                                                      (_, method, args) -> method.getName().equals("stamps") && (args == null || args.length == 0)
                                                                           ? stamps(memory, key, writeAgain, calls.getAndIncrement())
                                                                           : invoke(method, memory, args));
    }

    private static Promise<?> stamps(StorageEngine memory, byte[] key, boolean writeAgain, int call) {
        return switch (call) {
            case 0 -> memory.stamps().flatMap(stamps -> memory.remove(key).map(_ -> stamps));
            case 1 -> writeAgain
                      ? memory.put(key, value("again")).flatMap(_ -> memory.stamps())
                      : memory.stamps();
            default -> memory.stamps();
        };
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
