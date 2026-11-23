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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.dht.storage.StorageEngine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.carriesKey;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.otherTargetKey;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.sameDeliverer;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345: a re-plan pages neither an undeliverable key nor an absent one; only the exit re-read revives an absent key. Each test
/// keeps one other target silent for the whole window (so no exit re-read runs) and forces a re-plan from its second send.
class DepartureReplanExclusionTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    @Test
    @Timeout(20)
    void replan_undeliverableKey_isNotPagedAgain() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var good = soleKey(ring, "rx-good");
        var bad = sameDeliverer(ring, good, "rx-bad");
        var silent = otherTargetKey(ring, delivererOf(ring, good, Set.of(LOCAL)), "rx-silent");
        Set.of(good, bad, silent).forEach(key -> node.putLocal(key, value("payload")).await());
        var handle = new AtomicReference<DeparturePushRun>();
        var silentSends = new AtomicInteger();
        var network = ScriptedNetwork.scripted((_, sent) -> carriesKey(sent, silent)
                                                            ? replanAtSecond(silentSends, handle, () -> {})
                                                            : carriesKey(sent, bad)
                                                              ? Reply.refuse(id -> new WriteOutcome.EncodeFailed(id, "MigrationDataResponse"))
                                                              : Reply.ACK);
        var run = direct(node, network, 800, 100, Set::of, handle);

        assertThat(silentSends).as("the re-plan was forced").hasValueGreaterThanOrEqualTo(2);
        assertThat(run.report().undeliverable()).isEqualTo(1);
        assertThat(network.sends()).as("the undeliverable key's own page is sent once, never re-paged")
                                   .filteredOn(sent -> sent.response().entries().size() == 1 && carriesKey(sent, bad))
                                   .hasSize(1);
    }

    @Test
    @Timeout(20)
    void replan_absentKeyWrittenAgain_isNotPagedBeforeAnExitReRead() {
        var ring = fiveNodeRing();
        var removed = soleKey(ring, "rx-removed");
        var memory = memoryStorageEngine();
        var node = dhtNode(LOCAL, removeAfterStamps(memory, removed), ring, RF1);
        var silent = otherTargetKey(ring, delivererOf(ring, removed, Set.of(LOCAL)), "rx-kept");
        node.putLocal(removed, value("payload")).await();
        node.putLocal(silent, value("payload")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var silentSends = new AtomicInteger();
        var network = ScriptedNetwork.scripted((_, sent) -> carriesKey(sent, silent)
                                                            ? replanAtSecond(silentSends, handle, () -> node.putLocal(removed, value("again")).await())
                                                            : Reply.ACK);
        var run = direct(node, network, 800, 100, Set::of, handle);

        assertThat(silentSends).as("the re-plan was forced").hasValueGreaterThanOrEqualTo(2);
        assertThat(network.sends()).as("absent, so no re-plan pages it").noneMatch(sent -> carriesKey(sent, removed));
        assertThat(run.report().owed()).isEqualTo(2);
    }

    /// The silent target never answers; at its second send (the first resend) `before` runs and the run re-plans.
    private static Reply replanAtSecond(AtomicInteger sends, AtomicReference<DeparturePushRun> handle, Runnable before) {
        if (sends.getAndIncrement() == 1) {
            before.run();
            handle.get().onViewChange();
        }
        return Reply.DROP;
    }

    /// `memory`, except that enumerating its stamps removes `key` right after the read: the key is absent at its page's read.
    private static StorageEngine removeAfterStamps(StorageEngine memory, byte[] key) {
        return (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
                                                      new Class<?>[]{StorageEngine.class},
                                                      (_, method, args) -> method.getName().equals("stamps") && (args == null || args.length == 0)
                                                                           ? memory.stamps().flatMap(stamps -> memory.remove(key).map(_ -> stamps))
                                                                           : invoke(method, memory, args));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
