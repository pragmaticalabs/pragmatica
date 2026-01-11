package org.pragmatica.aether.infra.outbox;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of OutboxService.
 * Uses ConcurrentHashMap for thread-safe storage.
 */
final class InMemoryOutboxService implements OutboxService {
    private final OutboxConfig config;
    private final ConcurrentHashMap<String, OutboxMessage> messages = new ConcurrentHashMap<>();

    private InMemoryOutboxService(OutboxConfig config) {
        this.config = config;
    }

    static InMemoryOutboxService inMemoryOutboxService(OutboxConfig config) {
        return new InMemoryOutboxService(config);
    }

    @Override
    public Promise<OutboxMessage> add(OutboxMessage message) {
        return option(messages.putIfAbsent(message.id(),
                                           message))
                     .fold(() -> Promise.success(message),
                           existing -> OutboxError.messageAlreadyExists(message.id())
                                                  .promise());
    }

    @Override
    public Promise<Option<OutboxMessage>> get(String messageId) {
        return Promise.success(option(messages.get(messageId)));
    }

    @Override
    public Promise<List<OutboxMessage>> getReady(int limit) {
        var ready = messages.values()
                            .stream()
                            .filter(OutboxMessage::isReady)
                            .limit(limit)
                            .toList();
        return Promise.success(ready);
    }

    @Override
    public Promise<List<OutboxMessage>> getByTopic(String topic) {
        var byTopic = messages.values()
                              .stream()
                              .filter(m -> m.topic()
                                            .equals(topic))
                              .toList();
        return Promise.success(byTopic);
    }

    @Override
    public Promise<List<OutboxMessage>> getByStatus(OutboxMessageStatus status) {
        var byStatus = messages.values()
                               .stream()
                               .filter(m -> m.status() == status)
                               .toList();
        return Promise.success(byStatus);
    }

    @Override
    public Promise<OutboxMessage> markProcessing(String messageId) {
        return updateMessage(messageId, "mark processing", OutboxMessageStatus.PENDING, OutboxMessage::markProcessing);
    }

    @Override
    public Promise<OutboxMessage> markDelivered(String messageId) {
        return updateMessage(messageId, "mark delivered", OutboxMessageStatus.PROCESSING, OutboxMessage::markDelivered);
    }

    @Override
    public Promise<OutboxMessage> markFailed(String messageId, String error) {
        return updateMessage(messageId, "mark failed", OutboxMessageStatus.PROCESSING, m -> m.markFailed(error));
    }

    @Override
    public Promise<OutboxMessage> reschedule(String messageId, Instant nextAttempt) {
        return getMessageOrFail(messageId)
                               .flatMap(message -> validateReschedule(message, nextAttempt));
    }

    private Promise<OutboxMessage> validateReschedule(OutboxMessage message, Instant nextAttempt) {
        return message.canRetry(config.maxRetries())
               ? applyReschedule(message, nextAttempt)
               : OutboxError.invalidMessageState(message.id(),
                                                 message.status(),
                                                 "reschedule (max retries exceeded)")
                            .promise();
    }

    private Promise<OutboxMessage> applyReschedule(OutboxMessage message, Instant nextAttempt) {
        var updated = message.reschedule(nextAttempt);
        messages.put(message.id(), updated);
        return Promise.success(updated);
    }

    @Override
    public Promise<OutboxMessage> cancel(String messageId) {
        return getMessageOrFail(messageId)
                               .flatMap(this::validateAndCancel);
    }

    private Promise<OutboxMessage> validateAndCancel(OutboxMessage message) {
        return message.status() == OutboxMessageStatus.PENDING
               ? applyCancel(message)
               : OutboxError.invalidMessageState(message.id(),
                                                 message.status(),
                                                 "cancel")
                            .promise();
    }

    private Promise<OutboxMessage> applyCancel(OutboxMessage message) {
        var updated = message.withStatus(OutboxMessageStatus.CANCELLED);
        messages.put(message.id(), updated);
        return Promise.success(updated);
    }

    @Override
    public Promise<Boolean> delete(String messageId) {
        return Promise.success(option(messages.remove(messageId))
                                     .isPresent());
    }

    @Override
    public Promise<Integer> cleanup(Instant olderThan) {
        var toRemove = messages.values()
                               .stream()
                               .filter(m -> m.status() == OutboxMessageStatus.DELIVERED)
                               .filter(m -> m.processedAt()
                                             .fold(() -> false,
                                                   p -> p.isBefore(olderThan)))
                               .map(OutboxMessage::id)
                               .toList();
        toRemove.forEach(messages::remove);
        return Promise.success(toRemove.size());
    }

    @Override
    public Promise<Long> countByStatus(OutboxMessageStatus status) {
        var count = messages.values()
                            .stream()
                            .filter(m -> m.status() == status)
                            .count();
        return Promise.success(count);
    }

    @Override
    public Promise<Long> count() {
        return Promise.success((long) messages.size());
    }

    @Override
    public Promise<Unit> stop() {
        messages.clear();
        return Promise.success(unit());
    }

    private Promise<OutboxMessage> getMessageOrFail(String messageId) {
        return option(messages.get(messageId))
                     .fold(() -> OutboxError.messageNotFound(messageId)
                                            .<OutboxMessage> promise(),
                           Promise::success);
    }

    private Promise<OutboxMessage> updateMessage(String messageId,
                                                 String operation,
                                                 OutboxMessageStatus requiredStatus,
                                                 java.util.function.Function<OutboxMessage, OutboxMessage> updater) {
        return getMessageOrFail(messageId)
                               .flatMap(message -> validateAndUpdate(message, operation, requiredStatus, updater));
    }

    private Promise<OutboxMessage> validateAndUpdate(OutboxMessage message,
                                                     String operation,
                                                     OutboxMessageStatus requiredStatus,
                                                     java.util.function.Function<OutboxMessage, OutboxMessage> updater) {
        return message.status() == requiredStatus
               ? applyUpdate(message, updater)
               : OutboxError.invalidMessageState(message.id(),
                                                 message.status(),
                                                 operation)
                            .promise();
    }

    private Promise<OutboxMessage> applyUpdate(OutboxMessage message,
                                               java.util.function.Function<OutboxMessage, OutboxMessage> updater) {
        var updated = updater.apply(message);
        messages.put(message.id(), updated);
        return Promise.success(updated);
    }
}
