package org.pragmatica.lang.utils;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Functions.Fn0;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicStrategyTest {
    @Test
    void atomicStrategy_servesInitialStrategy_untilFirstSwap() {
        Fn0<String> initial = () -> "initial";
        var cell = AtomicStrategy.atomicStrategy(initial);

        assertThat(cell.strategy()).isSameAs(initial);
        assertThat(cell.strategy().apply()).isEqualTo("initial");
    }

    @Test
    void swap_replacesStrategy_wholesale() {
        var cell = AtomicStrategy.atomicStrategy((Fn0<String>) () -> "initial");
        Fn0<String> next = () -> "updated";

        cell.swap(next);

        assertThat(cell.strategy()).isSameAs(next);
        assertThat(cell.strategy().apply()).isEqualTo("updated");
    }

    @Test
    void atomicStrategy_rejectsNullInitial() {
        assertThatThrownBy(() -> AtomicStrategy.atomicStrategy(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void swap_rejectsNull() {
        var cell = AtomicStrategy.atomicStrategy((Fn0<String>) () -> "initial");

        assertThatThrownBy(() -> cell.swap(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void swap_isVisibleAcrossThreads() throws InterruptedException {
        var cell = AtomicStrategy.atomicStrategy((Fn0<String>) () -> "initial");
        var observed = new AtomicReference<String>();
        var reader = new Thread(() -> spinUntilUpdated(cell, observed));

        reader.start();
        cell.swap(() -> "updated");
        reader.join(5_000);

        assertThat(observed.get()).isEqualTo("updated");
    }

    private static void spinUntilUpdated(AtomicStrategy<Fn0<String>> cell, AtomicReference<String> out) {
        while ("initial".equals(cell.strategy().apply())) {
            Thread.onSpinWait();
        }
        out.set(cell.strategy().apply());
    }
}
