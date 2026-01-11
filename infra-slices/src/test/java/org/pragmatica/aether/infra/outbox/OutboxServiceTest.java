package org.pragmatica.aether.infra.outbox;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.infra.outbox.OutboxMessage.outboxMessage;
import static org.pragmatica.aether.infra.outbox.OutboxService.outboxService;
import static org.pragmatica.lang.Option.option;

class OutboxServiceTest {
    private OutboxService service;

    @BeforeEach
    void setUp() {
        service = outboxService();
    }

    // ========== Add Tests ==========

    @Test
    void add_succeeds_withValidMessage() {
        var message = outboxMessage("msg-1", "orders", "payload".getBytes());

        service.add(message)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(added -> {
                   assertThat(added.id()).isEqualTo("msg-1");
                   assertThat(added.topic()).isEqualTo("orders");
                   assertThat(added.status()).isEqualTo(OutboxMessageStatus.PENDING);
               });
    }

    @Test
    void add_fails_whenMessageAlreadyExists() {
        var message = outboxMessage("msg-1", "orders", "payload".getBytes());
        service.add(message).await();

        service.add(message)
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(OutboxError.MessageAlreadyExists.class));
    }

    @Test
    void add_succeeds_withKey() {
        var message = outboxMessage("msg-1", "orders", option("order-123"), "payload".getBytes());

        service.add(message)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(added -> {
                   assertThat(added.key().isPresent()).isTrue();
                   assertThat(added.key().fold(() -> "", k -> k)).isEqualTo("order-123");
               });
    }

    // ========== Get Tests ==========

    @Test
    void get_returnsMessage_whenExists() {
        var message = outboxMessage("msg-1", "orders", "payload".getBytes());
        service.add(message).await();

        service.get("msg-1")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> {
                   assertThat(opt.isPresent()).isTrue();
                   assertThat(opt.fold(() -> "", m -> m.id())).isEqualTo("msg-1");
               });
    }

    @Test
    void get_returnsEmpty_whenNotExists() {
        service.get("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
    }

    // ========== GetReady Tests ==========

    @Test
    void getReady_returnsOnlyReadyMessages() {
        var ready1 = outboxMessage("msg-1", "orders", "payload".getBytes());
        var ready2 = outboxMessage("msg-2", "orders", "payload".getBytes());
        var scheduled = outboxMessage("msg-3", "orders", option("key"), "payload".getBytes(), Instant.now().plusSeconds(3600));

        service.add(ready1).await();
        service.add(ready2).await();
        service.add(scheduled).await();

        service.getReady(10)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> {
                   assertThat(messages).hasSize(2);
                   assertThat(messages.stream().map(OutboxMessage::id)).containsExactlyInAnyOrder("msg-1", "msg-2");
               });
    }

    @Test
    void getReady_respectsLimit() {
        for (int i = 0; i < 10; i++) {
            service.add(outboxMessage("msg-" + i, "orders", "payload".getBytes())).await();
        }

        service.getReady(3)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> assertThat(messages).hasSize(3));
    }

    // ========== GetByTopic Tests ==========

    @Test
    void getByTopic_returnsMessagesForTopic() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-2", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-3", "payments", "payload".getBytes())).await();

        service.getByTopic("orders")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> {
                   assertThat(messages).hasSize(2);
                   assertThat(messages.stream().allMatch(m -> m.topic().equals("orders"))).isTrue();
               });
    }

    // ========== GetByStatus Tests ==========

    @Test
    void getByStatus_returnsMessagesWithStatus() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-2", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();

        service.getByStatus(OutboxMessageStatus.PENDING)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(messages -> {
                   assertThat(messages).hasSize(1);
                   assertThat(messages.getFirst().id()).isEqualTo("msg-2");
               });
    }

    // ========== MarkProcessing Tests ==========

    @Test
    void markProcessing_succeeds_forPendingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();

        service.markProcessing("msg-1")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(message -> {
                   assertThat(message.status()).isEqualTo(OutboxMessageStatus.PROCESSING);
                   assertThat(message.processedAt().isPresent()).isTrue();
               });
    }

    @Test
    void markProcessing_fails_forNonPendingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();

        service.markProcessing("msg-1")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(OutboxError.InvalidMessageState.class));
    }

    @Test
    void markProcessing_fails_forNonexistentMessage() {
        service.markProcessing("nonexistent")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(OutboxError.MessageNotFound.class));
    }

    // ========== MarkDelivered Tests ==========

    @Test
    void markDelivered_succeeds_forProcessingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();

        service.markDelivered("msg-1")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(message -> assertThat(message.status()).isEqualTo(OutboxMessageStatus.DELIVERED));
    }

    @Test
    void markDelivered_fails_forNonProcessingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();

        service.markDelivered("msg-1")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(OutboxError.InvalidMessageState.class));
    }

    // ========== MarkFailed Tests ==========

    @Test
    void markFailed_succeeds_forProcessingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();

        service.markFailed("msg-1", "Connection timeout")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(message -> {
                   assertThat(message.status()).isEqualTo(OutboxMessageStatus.FAILED);
                   assertThat(message.errorMessage().isPresent()).isTrue();
                   assertThat(message.retryCount()).isEqualTo(1);
               });
    }

    // ========== Reschedule Tests ==========

    @Test
    void reschedule_succeeds_whenRetriesRemain() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        var nextAttempt = Instant.now().plusSeconds(60);

        service.reschedule("msg-1", nextAttempt)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(message -> {
                   assertThat(message.status()).isEqualTo(OutboxMessageStatus.PENDING);
                   assertThat(message.scheduledAt()).isEqualTo(nextAttempt);
                   assertThat(message.retryCount()).isEqualTo(1);
               });
    }

    @Test
    void reschedule_fails_whenMaxRetriesExceeded() {
        var config = OutboxConfig.outboxConfig(100, 1, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofDays(7)).unwrap();
        var svc = outboxService(config);
        svc.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        svc.reschedule("msg-1", Instant.now().plusSeconds(30)).await();

        svc.reschedule("msg-1", Instant.now().plusSeconds(60))
           .await()
           .onSuccessRun(Assertions::fail)
           .onFailure(cause -> assertThat(cause).isInstanceOf(OutboxError.InvalidMessageState.class));
    }

    // ========== Cancel Tests ==========

    @Test
    void cancel_succeeds_forPendingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();

        service.cancel("msg-1")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(message -> assertThat(message.status()).isEqualTo(OutboxMessageStatus.CANCELLED));
    }

    @Test
    void cancel_fails_forNonPendingMessage() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();

        service.cancel("msg-1")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(OutboxError.InvalidMessageState.class));
    }

    // ========== Delete Tests ==========

    @Test
    void delete_returnsTrue_whenMessageExists() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();

        service.delete("msg-1")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(result -> assertThat(result).isTrue());
    }

    @Test
    void delete_returnsFalse_whenMessageNotExists() {
        service.delete("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(result -> assertThat(result).isFalse());
    }

    // ========== Cleanup Tests ==========

    @Test
    void cleanup_removesOldDeliveredMessages() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-2", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();
        service.markDelivered("msg-1").await();

        service.cleanup(Instant.now().plusSeconds(1))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(count -> assertThat(count).isEqualTo(1));

        service.get("msg-1")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());

        service.get("msg-2")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
    }

    // ========== Count Tests ==========

    @Test
    void countByStatus_returnsCorrectCount() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-2", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-3", "orders", "payload".getBytes())).await();
        service.markProcessing("msg-1").await();

        service.countByStatus(OutboxMessageStatus.PENDING)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(count -> assertThat(count).isEqualTo(2));

        service.countByStatus(OutboxMessageStatus.PROCESSING)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(count -> assertThat(count).isEqualTo(1));
    }

    @Test
    void count_returnsTotalCount() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-2", "orders", "payload".getBytes())).await();

        service.count()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(count -> assertThat(count).isEqualTo(2));
    }

    // ========== Lifecycle Tests ==========

    @Test
    void stop_clearsAllMessages() {
        service.add(outboxMessage("msg-1", "orders", "payload".getBytes())).await();
        service.add(outboxMessage("msg-2", "orders", "payload".getBytes())).await();

        service.stop().await();

        service.count()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(count -> assertThat(count).isEqualTo(0));
    }

    // ========== Config Tests ==========

    @Test
    void config_validates_batchSize() {
        OutboxConfig.outboxConfig(0, 3, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofDays(7))
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Batch size"));
    }

    @Test
    void config_validates_maxRetries() {
        OutboxConfig.outboxConfig(100, -1, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofDays(7))
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Max retries"));
    }

    @Test
    void config_validates_retryDelay() {
        OutboxConfig.outboxConfig(100, 3, Duration.ZERO, Duration.ofMinutes(5), Duration.ofDays(7))
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Retry delay"));
    }

    @Test
    void config_calculatesExponentialBackoff() {
        var config = OutboxConfig.DEFAULT;

        assertThat(config.retryDelayFor(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.retryDelayFor(1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.retryDelayFor(2)).isEqualTo(Duration.ofSeconds(120));
    }

    // ========== Message Tests ==========

    @Test
    void message_canRetry_respectsMaxRetries() {
        var message = outboxMessage("msg-1", "orders", "payload".getBytes());

        assertThat(message.canRetry(3)).isTrue();

        var retried1 = message.reschedule(Instant.now());
        assertThat(retried1.canRetry(3)).isTrue();

        var retried2 = retried1.reschedule(Instant.now());
        assertThat(retried2.canRetry(3)).isTrue();

        var retried3 = retried2.reschedule(Instant.now());
        assertThat(retried3.canRetry(3)).isFalse();
    }

    @Test
    void message_isReady_checksStatusAndSchedule() {
        var ready = outboxMessage("msg-1", "orders", "payload".getBytes());
        assertThat(ready.isReady()).isTrue();

        var scheduled = outboxMessage("msg-2", "orders", option("key"), "payload".getBytes(), Instant.now().plusSeconds(3600));
        assertThat(scheduled.isReady()).isFalse();

        var processing = ready.markProcessing();
        assertThat(processing.isReady()).isFalse();
    }
}
