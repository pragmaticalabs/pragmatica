package org.pragmatica.aether.example.composition;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Qualifier annotation for order event subscriptions.
///
/// When placed on a method in a step interface, the annotation processor
/// detects it transitively through the step dependency chain:
///
/// 1. OrderProcessor depends on OrderEventListener (plain interface parameter)
/// 2. OrderEventListener.onOrderPlaced() is annotated with @OnOrderEvent
/// 3. @OnOrderEvent declares @ResourceQualifier(type = Subscriber.class, ...)
/// 4. The processor generates a subscription entry in the slice manifest
/// 5. At runtime, Aether dispatches events to the annotated method
///
/// This is the transitive method-level annotation pattern — the @Slice
/// (OrderProcessor) never mentions subscriptions directly; they are
/// discovered through its step dependencies.
@ResourceQualifier(type = Subscriber.class, config = "messaging.order-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnOrderEvent {}
