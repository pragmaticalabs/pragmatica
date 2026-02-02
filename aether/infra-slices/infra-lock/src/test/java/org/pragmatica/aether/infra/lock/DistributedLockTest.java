package org.pragmatica.aether.infra.lock;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DistributedLockTest {
    private DistributedLock lock;

    @BeforeEach
    void setUp() {
        lock = DistributedLock.inMemory();
    }

    @Test
    void acquire_succeeds_when_lock_available() {
        lock.acquire("test-lock", timeSpan(1).seconds())
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(handle -> {
                assertThat(handle.lockId()).isEqualTo("test-lock");
                assertThat(handle.fencingToken()).isNotNull();
                assertThat(handle.acquiredAt()).isNotNull();
            });
    }

    @Test
    void tryAcquire_returns_empty_when_lock_held() {
        lock.acquire("test-lock", timeSpan(1).seconds())
            .await()
            .onFailureRun(Assertions::fail);

        lock.tryAcquire("test-lock")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(option -> assertThat(option.isEmpty()).isTrue());
    }

    @Test
    void tryAcquire_succeeds_when_lock_available() {
        lock.tryAcquire("test-lock")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(option -> {
                assertThat(option.isPresent()).isTrue();
                option.onPresent(handle -> assertThat(handle.lockId()).isEqualTo("test-lock"));
            });
    }

    @Test
    void release_allows_reacquisition() {
        lock.acquire("test-lock", timeSpan(1).seconds())
            .flatMap(LockHandle::release)
            .await()
            .onFailureRun(Assertions::fail);

        lock.tryAcquire("test-lock")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(option -> assertThat(option.isPresent()).isTrue());
    }

    @Test
    void withLock_releases_automatically() {
        var executed = new AtomicBoolean(false);

        lock.withLock("test-lock", timeSpan(1).seconds(), () -> {
                executed.set(true);
                return Promise.success("result");
            })
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(result -> assertThat(result).isEqualTo("result"));

        assertThat(executed.get()).isTrue();

        lock.tryAcquire("test-lock")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(option -> assertThat(option.isPresent()).isTrue());
    }

    @Test
    void withLock_releases_on_failure() {
        lock.withLock("test-lock", timeSpan(1).seconds(),
                      () -> new LockError.LockNotHeld("test").promise())
            .await()
            .onSuccessRun(Assertions::fail);

        lock.tryAcquire("test-lock")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(option -> assertThat(option.isPresent()).isTrue());
    }

    @Test
    void fencing_tokens_are_unique() {
        var token1 = lock.tryAcquire("lock1")
                         .await()
                         .fold(c -> "", opt -> opt.map(LockHandle::fencingToken).or(""));

        lock.tryAcquire("lock1")
            .await()
            .onSuccess(opt -> opt.onPresent(LockHandle::release));

        var token2 = lock.tryAcquire("lock2")
                         .await()
                         .fold(c -> "", opt -> opt.map(LockHandle::fencingToken).or(""));

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    void extend_succeeds_when_holding_lock() {
        lock.acquire("test-lock", timeSpan(1).seconds())
            .flatMap(handle -> handle.extend(timeSpan(5).seconds()))
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(result -> assertThat(result).isTrue());
    }

    @Test
    void concurrent_locks_are_serialized() throws InterruptedException {
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        Runnable task = () -> {
            lock.withLock("shared-lock", timeSpan(5).seconds(), () -> {
                    counter.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Promise.success(counter.get());
                })
                .await();
            latch.countDown();
        };

        new Thread(task).start();
        new Thread(task).start();

        latch.await();
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void acquire_times_out_when_lock_held() {
        lock.acquire("test-lock", timeSpan(1).seconds())
            .await()
            .onFailureRun(Assertions::fail);

        lock.acquire("test-lock", timeSpan(100).millis())
            .await()
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause).isInstanceOf(LockError.AcquisitionTimeout.class));
    }
}
