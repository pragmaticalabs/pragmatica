package org.pragmatica.aether.example.pricing.analytics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


@ResourceQualifier(type = Subscriber.class, config = "high-value-orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HighValueOrderSubscription {}
