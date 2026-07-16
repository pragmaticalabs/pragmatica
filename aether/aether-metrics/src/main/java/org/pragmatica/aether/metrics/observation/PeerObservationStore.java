// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.observation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.lang.Contract;
import org.pragmatica.consensus.NodeId;


public final class PeerObservationStore implements PeerObservationBuffer {
    private static final int DEFAULT_CAP = 64;

    private final Object healthLock = new Object();
    private final Deque<PeerHealthObservation> healthBuffer = new ArrayDeque<>();
    private final Object connectivityLock = new Object();
    private final Deque<PeerConnectivityObservation> connectivityBuffer = new ArrayDeque<>();

    private final CopyOnWriteArrayList<Consumer<PeerHealthObservation>> healthSubscribers = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Consumer<PeerConnectivityObservation>> connectivitySubscribers = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<NodeId, Integer> pingMisses = new ConcurrentHashMap<>();
    private volatile IntSupplier capSupplier = () -> DEFAULT_CAP;

    private PeerObservationStore() {}

    public static PeerObservationStore peerObservationStore() {
        return new PeerObservationStore();
    }

    @Contract
    public void setCapSupplier(IntSupplier supplier) {
        capSupplier = supplier;
    }

    @Override
    @Contract
    public void pushHealth(PeerHealthObservation observation) {
        boolean delivered;

        synchronized (healthLock) {
            if (healthSubscribers.isEmpty()) {
                if (healthBuffer.size() >= capSupplier.getAsInt()) {
                    healthBuffer.pollFirst();
                }

                healthBuffer.offerLast(observation);
                delivered = false;
            } else {
                delivered = true;
            }
        }

        if (delivered) {
            notifyHealth(observation);
        }
    }

    @Override
    @Contract
    public void pushConnectivity(PeerConnectivityObservation observation) {
        boolean delivered;

        synchronized (connectivityLock) {
            if (connectivitySubscribers.isEmpty()) {
                if (connectivityBuffer.size() >= capSupplier.getAsInt()) {
                    connectivityBuffer.pollFirst();
                }

                connectivityBuffer.offerLast(observation);
                delivered = false;
            } else {
                delivered = true;
            }
        }

        if (delivered) {
            notifyConnectivity(observation);
        }
    }

    @Override
    public List<PeerHealthObservation> drainHealth() {
        synchronized (healthLock) {
            if (healthBuffer.isEmpty()) {
                return List.of();
            }

            var drained = new ArrayList<>(healthBuffer);

            healthBuffer.clear();

            return List.copyOf(drained);
        }
    }

    @Override
    public List<PeerConnectivityObservation> drainConnectivity() {
        synchronized (connectivityLock) {
            if (connectivityBuffer.isEmpty()) {
                return List.of();
            }

            var drained = new ArrayList<>(connectivityBuffer);

            connectivityBuffer.clear();

            return List.copyOf(drained);
        }
    }

    @Contract
    public void clear() {
        synchronized (healthLock) {
            healthBuffer.clear();
        }

        synchronized (connectivityLock) {
            connectivityBuffer.clear();
        }
    }

    public int recordPingMiss(NodeId peer) {
        return pingMisses.merge(peer, 1, Integer::sum);
    }

    @Contract
    public void clearPingMisses(NodeId peer) {
        pingMisses.remove(peer);
    }

    public int pingMissCount(NodeId peer) {
        return pingMisses.getOrDefault(peer, 0);
    }

    @Contract
    public void retainPingMisses(Set<NodeId> liveCore) {
        pingMisses.keySet().retainAll(liveCore);
    }

    @Contract
    public void clearAllPingMisses() {
        pingMisses.clear();
    }

    public Subscription subscribeHealth(Consumer<PeerHealthObservation> callback) {
        healthSubscribers.add(callback);

        return () -> healthSubscribers.remove(callback);
    }

    public Subscription subscribeConnectivity(Consumer<PeerConnectivityObservation> callback) {
        connectivitySubscribers.add(callback);

        return () -> connectivitySubscribers.remove(callback);
    }

    public DrainAndSubscribe<PeerHealthObservation> subscribeHealthAndDrain(Consumer<PeerHealthObservation> callback) {
        synchronized (healthLock) {
            healthSubscribers.add(callback);
            var drained = healthBuffer.isEmpty()
                          ? List.<PeerHealthObservation> of()
                          : List.copyOf(new ArrayList<>(healthBuffer));

            healthBuffer.clear();
            Subscription sub = () -> healthSubscribers.remove(callback);

            return new DrainAndSubscribe<>(drained, sub);
        }
    }

    public DrainAndSubscribe<PeerConnectivityObservation> subscribeConnectivityAndDrain(Consumer<PeerConnectivityObservation> callback) {
        synchronized (connectivityLock) {
            connectivitySubscribers.add(callback);
            var drained = connectivityBuffer.isEmpty()
                          ? List.<PeerConnectivityObservation> of()
                          : List.copyOf(new ArrayList<>(connectivityBuffer));

            connectivityBuffer.clear();
            Subscription sub = () -> connectivitySubscribers.remove(callback);

            return new DrainAndSubscribe<>(drained, sub);
        }
    }

    public record DrainAndSubscribe<T>(List<T> drained, Subscription subscription) {
        public DrainAndSubscribe {
            drained = List.copyOf(drained);
        }
    }

    private void notifyHealth(PeerHealthObservation observation) {
        healthSubscribers.forEach(subscriber -> subscriber.accept(observation));
    }

    private void notifyConnectivity(PeerConnectivityObservation observation) {
        connectivitySubscribers.forEach(subscriber -> subscriber.accept(observation));
    }

    public interface Subscription {
        @Contract
        void unsubscribe();
    }
}
