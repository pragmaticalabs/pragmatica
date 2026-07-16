package org.pragmatica.aether.example.pricing.catalog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


@ResourceQualifier(type = Publisher.class, config = "high-value-orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface HighValueOrderPublisher {}
