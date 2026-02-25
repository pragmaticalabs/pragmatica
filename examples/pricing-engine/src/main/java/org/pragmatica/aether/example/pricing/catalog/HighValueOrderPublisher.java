package org.pragmatica.aether.example.pricing.catalog;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Publisher qualifier for high-value order events.
@ResourceQualifier(type = Publisher.class, config = "messaging.high-value-orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface HighValueOrderPublisher {}
