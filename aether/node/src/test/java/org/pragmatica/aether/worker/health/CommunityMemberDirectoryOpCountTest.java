// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.worker.governor.CommunityMembershipFilter;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins deterministically how many index entries a candidate lookup touches (#1840): O(community), not
/// O(all directives). The directory's maps and sets are replaced by counting ones; every BULK access
/// (iteration, stream, forEach, toArray, key/value/entry views) adds the container's size, while point
/// operations (get, contains, add, remove) add nothing. A lookup that scanned every assignment would add N.
class CommunityMemberDirectoryOpCountTest {
    private static final int COMMUNITY_SIZE = 10;
    private static final String OBSERVED = "c-0";
    private static final int[] SIZES = {200, 1_000, 10_000};

    private static final class Counter {
        final AtomicLong touched = new AtomicLong();
    }

    private static final class CountingSet<T> extends HashSet<T> {
        private final Counter counter;

        CountingSet(Counter counter) {
            this.counter = counter;
        }

        @Override public Iterator<T> iterator() { counter.touched.addAndGet(size()); return super.iterator(); }
        @Override public Stream<T> stream() { counter.touched.addAndGet(size()); return super.stream(); }
        @Override public Object[] toArray() { counter.touched.addAndGet(size()); return super.toArray(); }
        @Override public <A> A[] toArray(A[] a) { counter.touched.addAndGet(size()); return super.toArray(a); }
        @Override public void forEach(java.util.function.Consumer<? super T> action) { counter.touched.addAndGet(size()); super.forEach(action); }
    }

    private static final class CountingMap<K, V> extends HashMap<K, V> {
        private final Counter counter;

        CountingMap(Counter counter) {
            this.counter = counter;
        }

        @Override public Set<Map.Entry<K, V>> entrySet() { counter.touched.addAndGet(size()); return super.entrySet(); }
        @Override public Set<K> keySet() { counter.touched.addAndGet(size()); return super.keySet(); }
        @Override public Collection<V> values() { counter.touched.addAndGet(size()); return super.values(); }
        @Override public void forEach(java.util.function.BiConsumer<? super K, ? super V> action) { counter.touched.addAndGet(size()); super.forEach(action); }
    }

    private static CommunityMemberDirectory countingDirectory(Counter counter) {
        return CommunityMemberDirectory.communityMemberDirectory(new CommunityMemberDirectory.Containers() {
            @Override public <K, V> Map<K, V> map() { return new CountingMap<>(counter); }
            @Override public <T> Set<T> set() { return new CountingSet<>(counter); }
        });
    }

    private static NodeId node(int i) {
        return NodeId.nodeId("worker-" + i).unwrap();
    }

    private static CommunityMemberDirectory populated(Counter counter, int n) {
        var directory = countingDirectory(counter);

        for (int i = 0; i < n; i++) {
            directory.put(node(i), ActivationDirectiveValue.worker("c-" + i / COMMUNITY_SIZE, ""));
        }

        return directory;
    }

    @Test
    void lookup_touchesTheCommunityOnly_whateverTheDirectorySize() {
        for (var n : SIZES) {
            var counter = new Counter();
            var directory = populated(counter, n);

            counter.touched.set(0);
            var candidates = directory.governorCandidates(OBSERVED);

            assertThat(candidates).hasSize(COMMUNITY_SIZE);
            assertThat(counter.touched.get()).as("entries touched by one candidate lookup at N=%d", n)
                                             .isEqualTo(COMMUNITY_SIZE);
        }
    }

    @Test
    void filterEdge_touchesTheCommunityOnly_whateverTheDirectorySize() {
        for (var n : SIZES) {
            var counter = new Counter();
            var directory = populated(counter, n);
            var view = new ArrayList<SwimMember>();

            for (int i = 0; i < COMMUNITY_SIZE; i++) {
                view.add(SwimMember.swimMember(node(i), SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0)));
            }

            counter.touched.set(0);

            for (int edge = 0; edge < 100; edge++) {
                assertThat(CommunityMembershipFilter.communityAliveMembers(view, directory, OBSERVED)).hasSize(COMMUNITY_SIZE);
            }

            assertThat(counter.touched.get()).as("entries touched by 100 edges at N=%d", n)
                                             .isEqualTo(100L * COMMUNITY_SIZE);
        }
    }

    /// Control: the counter does count a bulk access (communities() copies the 100 community keys), so a zero or a small number above is not an artifact.
    @Test
    void control_aFullScanOfTheAssignmentsIsCounted() {
        var counter = new Counter();
        var directory = populated(counter, 1_000);

        counter.touched.set(0);
        directory.communities();

        assertThat(counter.touched.get()).as("communities() copies the community keys (100 of them)").isEqualTo(100);
    }
}
