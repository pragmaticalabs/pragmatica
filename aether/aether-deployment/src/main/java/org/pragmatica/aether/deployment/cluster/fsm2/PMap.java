// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Option;


/// A persistent hash map: every update returns a new map and shares structure with the old one, so the
/// cluster FSM's model can be an immutable value while a single input still costs O(log n), never a copy
/// (spec R-7.3.1: no single-input handler scans the store).
///
/// The trie is binary over the key's hash bits; keys with an equal hash share a collision bucket. Iteration
/// ([#entries], [#keys], [#values]) is a scan and is counted in [#scans], which is how a test proves an
/// ordinary input iterates nothing (NT-78).
public final class PMap<K, V> {
    private static final PMap<?, ?> EMPTY = new PMap<>(Node.EMPTY, 0);
    private static final AtomicLong SCANS = new AtomicLong();

    private final Node root;
    private final int size;

    private PMap(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> PMap<K, V> empty() {
        return (PMap<K, V>) EMPTY;
    }

    /// The number of iterations ([#entries], [#keys], [#values]) taken so far by any map in this process.
    public static long scans() {
        return SCANS.get();
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    @SuppressWarnings("unchecked")
    public Option<V> get(K key) {
        return Option.option((V) lookup(root, 0, hash(key), key));
    }

    public PMap<K, V> put(K key, V value) {
        var hash = hash(key);
        var grown = new boolean[1];
        var updated = insert(root, 0, hash, key, value, grown);

        return new PMap<>(updated,
                          grown[0]
                          ? size + 1
                          : size);
    }

    public PMap<K, V> remove(K key) {
        var shrunk = new boolean[1];
        var updated = delete(root, 0, hash(key), key, shrunk);

        return shrunk[0]
               ? new PMap<>(updated, size - 1)
               : this;
    }

    public List<Map.Entry<K, V>> entries() {
        SCANS.incrementAndGet();
        var out = new ArrayList<Map.Entry<K, V>>(size);

        collect(root, out);

        return out;
    }

    public List<K> keys() {
        return entries().stream()
                      .map(Map.Entry::getKey)
                      .toList();
    }

    public List<V> values() {
        return entries().stream()
                      .map(Map.Entry::getValue)
                      .toList();
    }

    private static int hash(Object key) {
        var h = key.hashCode();

        return h ^ (h >>> 16);
    }

    private sealed interface Node {
        Node EMPTY = new Empty();

        record Empty() implements Node {}

        record Leaf(int hash, Object key, Object value) implements Node {}

        record Collision(int hash, List<Leaf> leaves) implements Node {}

        record Branch(Node zero, Node one) implements Node {}
    }

    private static Object lookup(Node node, int depth, int hash, Object key) {
        return switch (node) {
            case Node.Empty _ -> null;
            case Node.Leaf leaf -> leaf.hash() == hash && leaf.key().equals(key)
                                   ? leaf.value()
                                   : null;
            case Node.Collision collision -> collision.hash() == hash
                                             ? lookupBucket(collision, key)
                                             : null;
            case Node.Branch branch -> lookup(bit(hash, depth) == 0
                                              ? branch.zero()
                                              : branch.one(),
                                              depth + 1,
                                              hash,
                                              key);
        };
    }

    private static Object lookupBucket(Node.Collision collision, Object key) {
        return collision.leaves()
                        .stream()
                        .filter(leaf -> leaf.key()
                                            .equals(key))
                        .map(Node.Leaf::value)
                        .findFirst()
                        .orElse(null);
    }

    private static int bit(int hash, int depth) {
        return (hash >>> depth) & 1;
    }

    private static Node insert(Node node, int depth, int hash, Object key, Object value, boolean[] grown) {
        return switch (node) {
            case Node.Empty _ -> grow(new Node.Leaf(hash, key, value), grown);
            case Node.Leaf leaf -> insertIntoLeaf(leaf, depth, hash, key, value, grown);
            case Node.Collision collision -> insertIntoBucket(collision, depth, hash, key, value, grown);
            case Node.Branch branch -> bit(hash, depth) == 0
                                       ? new Node.Branch(insert(branch.zero(), depth + 1, hash, key, value, grown),
                                                         branch.one())
                                       : new Node.Branch(branch.zero(),
                                                         insert(branch.one(), depth + 1, hash, key, value, grown));
        };
    }

    private static Node grow(Node node, boolean[] grown) {
        grown[0] = true;

        return node;
    }

    private static Node insertIntoLeaf(Node.Leaf leaf, int depth, int hash, Object key, Object value, boolean[] grown) {
        if (leaf.hash() == hash) {
            return leaf.key()
                       .equals(key)
                   ? new Node.Leaf(hash, key, value)
                   : grow(new Node.Collision(hash,
                                             List.of(leaf, new Node.Leaf(hash, key, value))),
                          grown);
        }

        return split(leaf, leaf.hash(), depth, hash, key, value, grown);
    }

    private static Node insertIntoBucket(Node.Collision bucket,
                                         int depth,
                                         int hash,
                                         Object key,
                                         Object value,
                                         boolean[] grown) {
        if (bucket.hash() != hash) {
            return split(bucket, bucket.hash(), depth, hash, key, value, grown);
        }

        var leaves = new ArrayList<>(bucket.leaves());
        var at = indexOfKey(leaves, key);

        if (at >= 0) {
            leaves.set(at, new Node.Leaf(hash, key, value));

            return new Node.Collision(hash, List.copyOf(leaves));
        }

        leaves.add(new Node.Leaf(hash, key, value));

        return grow(new Node.Collision(hash, List.copyOf(leaves)),
                    grown);
    }

    private static int indexOfKey(List<Node.Leaf> leaves, Object key) {
        for (var i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).key().equals(key)) {
                return i;
            }
        }

        return -1;
    }

    /// Two nodes with different hashes meet at `depth`: branch on the first bit where they differ.
    private static Node split(Node existing,
                              int existingHash,
                              int depth,
                              int hash,
                              Object key,
                              Object value,
                              boolean[] grown) {
        var added = new Node.Leaf(hash, key, value);

        grown[0] = true;

        return branchOver(existing, existingHash, added, hash, depth);
    }

    private static Node branchOver(Node first, int firstHash, Node second, int secondHash, int depth) {
        var firstBit = bit(firstHash, depth);
        var secondBit = bit(secondHash, depth);

        if (firstBit != secondBit) {
            return firstBit == 0
                   ? new Node.Branch(first, second)
                   : new Node.Branch(second, first);
        }

        var deeper = branchOver(first, firstHash, second, secondHash, depth + 1);

        return firstBit == 0
               ? new Node.Branch(deeper, Node.EMPTY)
               : new Node.Branch(Node.EMPTY, deeper);
    }

    private static Node delete(Node node, int depth, int hash, Object key, boolean[] shrunk) {
        return switch (node) {
            case Node.Empty _ -> node;
            case Node.Leaf leaf -> leaf.hash() == hash && leaf.key().equals(key)
                                   ? shrink(Node.EMPTY, shrunk)
                                   : node;
            case Node.Collision collision -> deleteFromBucket(collision, hash, key, shrunk);
            case Node.Branch branch -> deleteFromBranch(branch, depth, hash, key, shrunk);
        };
    }

    private static Node shrink(Node node, boolean[] shrunk) {
        shrunk[0] = true;

        return node;
    }

    private static Node deleteFromBucket(Node.Collision bucket, int hash, Object key, boolean[] shrunk) {
        if (bucket.hash() != hash) {
            return bucket;
        }

        var leaves = new ArrayList<>(bucket.leaves());
        var at = indexOfKey(leaves, key);

        if (at < 0) {
            return bucket;
        }

        leaves.remove(at);
        shrunk[0] = true;

        return leaves.size() == 1
               ? leaves.getFirst()
               : new Node.Collision(hash, List.copyOf(leaves));
    }

    private static Node deleteFromBranch(Node.Branch branch, int depth, int hash, Object key, boolean[] shrunk) {
        var zero = bit(hash, depth) == 0
                   ? delete(branch.zero(), depth + 1, hash, key, shrunk)
                   : branch.zero();
        var one = bit(hash, depth) == 0
                  ? branch.one()
                  : delete(branch.one(), depth + 1, hash, key, shrunk);

        if (!shrunk[0]) {
            return branch;
        }

        return collapse(zero, one);
    }

    /// A branch with one empty side and a leaf (or bucket) on the other carries no information: it is the leaf.
    private static Node collapse(Node zero, Node one) {
        if (zero instanceof Node.Empty && !(one instanceof Node.Branch)) {
            return one;
        }

        if (one instanceof Node.Empty && !(zero instanceof Node.Branch)) {
            return zero;
        }

        return new Node.Branch(zero, one);
    }

    @SuppressWarnings("unchecked")
    private void collect(Node node, List<Map.Entry<K, V>> out) {
        switch (node) {
            case Node.Empty _ -> {}
            case Node.Leaf leaf -> out.add(Map.entry((K) leaf.key(), (V) leaf.value()));
            case Node.Collision collision -> collision.leaves().forEach(leaf -> out.add(Map.entry((K) leaf.key(),
                                                                                                  (V) leaf.value())));
            case Node.Branch branch -> {
                collect(branch.zero(), out);
                collect(branch.one(), out);
            }
        }
    }
}
