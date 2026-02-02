package org.pragmatica.aether.infra.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTest {
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Scheduler.scheduler();
        scheduler.start().await();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop().await();
    }

    @Test
    void schedule_executesOneShotTaskAfterDelay() throws InterruptedException {
        var latch = new CountDownLatch(1);

        scheduler.schedule("one-shot", TimeSpan.timeSpan(50).millis(), () -> {
            latch.countDown();
            return Promise.success(Unit.unit());
        }).await().onFailureRun(Assertions::fail);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void scheduleAtFixedRate_executesMultipleTimes() throws InterruptedException {
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(3);

        scheduler.scheduleAtFixedRate("periodic", TimeSpan.timeSpan(10).millis(), TimeSpan.timeSpan(50).millis(), () -> {
            counter.incrementAndGet();
            latch.countDown();
            return Promise.success(Unit.unit());
        }).await().onFailureRun(Assertions::fail);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void scheduleWithFixedDelay_executesWithDelay() throws InterruptedException {
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        scheduler.scheduleWithFixedDelay("delayed", TimeSpan.timeSpan(10).millis(), TimeSpan.timeSpan(50).millis(), () -> {
            counter.incrementAndGet();
            latch.countDown();
            return Promise.success(Unit.unit());
        }).await().onFailureRun(Assertions::fail);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void cancel_stopsTaskExecution() throws InterruptedException {
        var counter = new AtomicInteger(0);

        var handle = scheduler.scheduleAtFixedRate("cancelable", TimeSpan.timeSpan(10).millis(), TimeSpan.timeSpan(50).millis(), () -> {
            counter.incrementAndGet();
            return Promise.success(Unit.unit());
        }).await().fold(c -> null, v -> v);

        assertThat(handle).isNotNull();
        Thread.sleep(150);

        var cancelled = scheduler.cancel("cancelable").await().fold(c -> false, v -> v);
        assertThat(cancelled).isTrue();

        var countAfterCancel = counter.get();
        Thread.sleep(150);

        assertThat(counter.get()).isEqualTo(countAfterCancel);
    }

    @Test
    void getTask_returnsTaskHandle() {
        scheduler.schedule("findable", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await().onFailureRun(Assertions::fail);

        scheduler.getTask("findable")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> {
                assertThat(opt.isPresent()).isTrue();
                opt.onPresent(handle -> assertThat(handle.name()).isEqualTo("findable"));
            });
    }

    @Test
    void getTask_returnsEmptyForMissingTask() {
        scheduler.getTask("nonexistent")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
    }

    @Test
    void listTasks_returnsAllActiveTasks() {
        scheduler.schedule("task1", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await();
        scheduler.schedule("task2", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await();

        scheduler.listTasks()
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(tasks -> assertThat(tasks).hasSize(2));
    }

    @Test
    void isScheduled_returnsTrueForActiveTask() {
        scheduler.schedule("active-task", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await();

        scheduler.isScheduled("active-task")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(scheduled -> assertThat(scheduled).isTrue());
    }

    @Test
    void isScheduled_returnsFalseForCancelledTask() {
        scheduler.schedule("to-cancel", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await();
        scheduler.cancel("to-cancel").await();

        scheduler.isScheduled("to-cancel")
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(scheduled -> assertThat(scheduled).isFalse());
    }

    @Test
    void schedulingDuplicateName_fails() {
        scheduler.schedule("duplicate", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await();

        scheduler.schedule("duplicate", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await()
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause).isInstanceOf(SchedulerError.SchedulingFailed.class));
    }

    @Test
    void taskHandle_canBeCancelled() throws InterruptedException {
        var counter = new AtomicInteger(0);

        var handle = scheduler.scheduleAtFixedRate("handle-cancel", TimeSpan.timeSpan(10).millis(), TimeSpan.timeSpan(50).millis(), () -> {
            counter.incrementAndGet();
            return Promise.success(Unit.unit());
        }).await().fold(c -> null, v -> v);

        assertThat(handle).isNotNull();
        assertThat(handle.isActive()).isTrue();

        Thread.sleep(100);
        handle.cancel();

        assertThat(handle.isActive()).isFalse();
    }

    @Test
    void start_succeeds() {
        var newScheduler = Scheduler.scheduler();
        newScheduler.start()
            .await()
            .onFailureRun(Assertions::fail);
        newScheduler.stop().await();
    }

    @Test
    void stop_cancelsAllTasks() {
        var newScheduler = Scheduler.scheduler();
        newScheduler.start().await();

        newScheduler.schedule("will-be-cancelled", TimeSpan.timeSpan(1).seconds(), () -> Promise.success(Unit.unit()))
            .await();

        newScheduler.stop()
            .await()
            .onFailureRun(Assertions::fail);

        newScheduler.listTasks()
            .await()
            .onFailureRun(Assertions::fail)
            .onSuccess(tasks -> assertThat(tasks).isEmpty());
    }

    @Test
    void methods_returnsEmptyList() {
        assertThat(scheduler.methods()).isEmpty();
    }
}
